// Linux transport for the pinned NetdClient dnsproxyd socket protocol.
// Keep NetworkUtils JNI, DnsResolver's Looper readiness/cancellation, and
// NetdClient's permission gate, request encoding and result ownership stock.
// Android's DNS daemon is replaced at its socket seam, not in framework Java.
#include <arpa/inet.h>
#include <arpa/nameser.h>
#include <atomic>
#include <array>
#include <cerrno>
#include <charconv>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <pthread.h>
#include <resolv.h>
#include <string_view>
#include <sys/socket.h>
#include <unistd.h>
#include <DnsProxydProtocol.h>

extern "C" int azk_netd_dns_network(unsigned);

namespace {
constexpr size_t commandLimit = 4096; // NetdClient / FrameworkListener
constexpr size_t packetLimit = 8192; // DnsResolver.MAXPACKETSIZE
std::atomic<unsigned> activeQueries{0};

bool sendAll(int fd, const void* data, size_t size) {
    auto bytes = static_cast<const char*>(data);
    while (size) {
        const ssize_t sent = send(fd, bytes, size, MSG_NOSIGNAL);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return false; // Cancellation closes the peer.
        bytes += sent;
        size -= sent;
    }
    return true;
}
bool sendInt(int fd, int32_t value) {
    const uint32_t wire = htonl(static_cast<uint32_t>(value));
    return sendAll(fd, &wire, sizeof(wire));
}
bool number(std::string_view text, unsigned& value) {
    if (text.empty()) return false;
    const auto result = std::from_chars(text.data(), text.data() + text.size(), value);
    return result.ec == std::errc{} && result.ptr == text.data() + text.size();
}
std::string_view take(std::string_view& text) {
    const auto space = text.find(' ');
    const auto token = text.substr(0, space);
    text = space == text.npos ? std::string_view{} : text.substr(space + 1);
    return token;
}

void serve(int fd) {
    // Stock clients send one NUL-terminated command. Bound incomplete requests;
    // result delivery uses the same timeout if the client abandons a query.
    const timeval timeout{10, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    std::array<char, commandLimit> command{};
    size_t used = 0;
    while (used < commandLimit) {
        char byte;
        const auto count = recv(fd, &byte, 1, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count != 1) return;
        if (byte == '\0') break;
        command[used++] = byte;
    }
    if (used == commandLimit) { sendInt(fd, -EMSGSIZE); return; }
    std::string_view rest(command.data(), used);
    const auto operation = take(rest);
    unsigned netid;
    const bool validNetwork = number(take(rest), netid);
    const int network = validNetwork ? azk_netd_dns_network(netid) : -EINVAL;
    if (operation == "getdnsnetid") {
        // SocketClient::sendCode(222), followed by the signed big-endian netid.
        if (!sendAll(fd, "222\0", 4)) return;
        sendInt(fd, !rest.empty() ? -EINVAL : network < 0 ? network :
                static_cast<int>(static_cast<unsigned>(network) |
                                 (netid & NETID_USE_LOCAL_NAMESERVERS)));
        return;
    }
    if (operation != "resnsend") { sendInt(fd, -EOPNOTSUPP); return; }
    if (network < 0) { sendInt(fd, network); return; }
    unsigned flags;
    if (!number(take(rest), flags) || (flags & ~7u) || rest.empty()) {
        sendInt(fd, -EINVAL);
        return;
    }
    std::array<unsigned char, packetLimit> query{};
    const int length = b64_pton(rest.data(), query.data(), query.size());
    if (length < 0) { sendInt(fd, -EILSEQ); return; }
    ns_msg parsed;
    if (ns_initparse(query.data(), length, &parsed) < 0 ||
            ns_msg_getflag(parsed, ns_f_qr) || ns_msg_getflag(parsed, ns_f_opcode) != ns_o_query ||
            ns_msg_count(parsed, ns_s_qd) != 1) {
        sendInt(fd, -EINVAL);
        return;
    }
    // Match DnsProxyListener: randomize the query ID and restore the caller's
    // ID in the answer. glibc owns resolv.conf, UDP retries and TCP fallback.
    const unsigned char id0 = query[0], id1 = query[1];
    const uint16_t id = static_cast<uint16_t>(arc4random_uniform(65536));
    query[0] = id >> 8;
    query[1] = id & 255;
    struct __res_state resolver{};
    if (res_ninit(&resolver) != 0) { sendInt(fd, -(errno ? errno : EIO)); return; }
    // This adapter has no answer cache, so NO_CACHE_STORE / NO_CACHE_LOOKUP
    // already hold. Upstream DNS servers retain their own caching semantics.
    if (flags & 1u) resolver.retry = 1; // FLAG_NO_RETRY
    std::array<unsigned char, packetLimit> answer{};
    const int size = res_nsend(&resolver, query.data(), length, answer.data(), answer.size());
    const int error = errno;
    res_nclose(&resolver);
    if (size < 0) { sendInt(fd, -(error ? error : EIO)); return; }
    if (size < NS_HFIXEDSZ || static_cast<size_t>(size) > answer.size()) {
        sendInt(fd, -EMSGSIZE);
        return;
    }
    answer[0] = id0;
    answer[1] = id1;
    // res_nsend preserves NXDOMAIN and empty answers as DNS packets.
    if (sendInt(fd, answer[3] & 15) && sendInt(fd, size)) sendAll(fd, answer.data(), size);
}

void* worker(void* context) {
    const int fd = *static_cast<int*>(context);
    delete static_cast<int*>(context);
    serve(fd);
    close(fd);
    activeQueries.fetch_sub(1);
    return nullptr;
}
} // namespace

extern "C" int azk_dns_open_proxy() {
    // Like DnsProxyListener's query limiter, bound abandoned or slow queries.
    if (activeQueries.fetch_add(1) >= 128) {
        activeQueries.fetch_sub(1);
        errno = EBUSY;
        return -1;
    }
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) {
        activeQueries.fetch_sub(1);
        return -1;
    }
    auto context = std::unique_ptr<int>(new (std::nothrow) int(pair[1]));
    pthread_t thread;
    const int error = context ? pthread_create(&thread, nullptr, worker, context.get()) : ENOMEM;
    if (error) {
        close(pair[0]);
        close(pair[1]);
        activeQueries.fetch_sub(1);
        errno = error;
        return -1;
    }
    context.release();
    pthread_detach(thread);
    return pair[0];
}
