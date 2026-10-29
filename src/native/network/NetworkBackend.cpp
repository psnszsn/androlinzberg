// Host boundary for pinned NetdClient/FwmarkClient. Linux owns actual routing;
// Android netd routing tables, VPN exemptions and BPF accounting are absent.
// Keep original policy atomics and socket validation; never invent those effects.
#include <NetdClient.h>
#include <FwmarkCommand.h>
#include <DnsProxydProtocol.h>
#include <cerrno>
#include <mutex>
#include <netdb.h>
#include <sys/socket.h>
#include <cstdint>
#include <unistd.h>

using Socket = int (*)(int, int, int);
using Connect = int (*)(int, const sockaddr*, socklen_t);
using Accept = int (*)(int, sockaddr*, socklen_t*, int);
using ResolverNetwork = unsigned (*)(unsigned);
extern "C" void netdClientInitSocket(Socket*);
extern "C" void netdClientInitConnect(Connect*);
extern "C" void netdClientInitAccept4(Accept*);
extern "C" void netdClientInitNetIdForResolv(ResolverNetwork*);
static Socket socketCall = ::socket;
static Connect connectCall = ::connect;
static Accept acceptCall = ::accept4;
static ResolverNetwork resolverNetwork = nullptr;
static std::once_flag initialized;
// ABI shared with LinuxNetworkSource: the inherited host network namespace,
// whose routes Linux owns. No per-interface Android routing tables are claimed.
static constexpr unsigned hostNamespaceNetworkId = 0xfffe;
static uint64_t hostNamespaceCookie = 0;
static int hostNamespaceStatus = -EAGAIN;
static int checkHostNamespace(int fd) {
    if (hostNamespaceStatus != 0) return hostNamespaceStatus;
    uint64_t socketNamespace = 0;
    socklen_t size = sizeof(socketNamespace);
    if (getsockopt(fd, SOL_SOCKET, SO_NETNS_COOKIE, &socketNamespace, &size) != 0) return -errno;
    return socketNamespace == hostNamespaceCookie ? 0 : -EXDEV;
}
// Bionic installs these callbacks at libc startup. Install them when the host
// platform DSO loads, before JNI clients can concurrently call NetdClient.
static void __attribute__((constructor)) initialize() {
    std::call_once(initialized, [] {
        const int probe = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (probe < 0) {
            hostNamespaceStatus = -errno;
        } else {
            socklen_t size = sizeof(hostNamespaceCookie);
            hostNamespaceStatus = getsockopt(probe, SOL_SOCKET, SO_NETNS_COOKIE,
                    &hostNamespaceCookie, &size) == 0 ? 0 : -errno;
            close(probe);
        }
        netdClientInitSocket(&socketCall);
        netdClientInitConnect(&connectCall);
        netdClientInitAccept4(&acceptCall);
        netdClientInitNetIdForResolv(&resolverNetwork);
    });
}
extern "C" int azk_netd_socket(int domain, int type, int protocol) {
    initialize();
    return socketCall(domain, type, protocol);
}
extern "C" int azk_netd_connect(int fd, const sockaddr* address, socklen_t size) {
    initialize();
    return connectCall(fd, address, size);
}
extern "C" int azk_netd_accept4(int fd, sockaddr* address, socklen_t* size, int flags) {
    initialize();
    return acceptCall(fd, address, size, flags);
}
extern "C" int azk_netd_host_command(const FwmarkCommand* command, int fd) {
    if (!command) return -EINVAL;
    if (command->cmdId == FwmarkCommand::QUERY_USER_ACCESS) return -EOPNOTSUPP;
    int family = 0;
    socklen_t size = sizeof(family);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &family, &size) != 0) return -errno;
    if (!FwmarkCommand::isSupportedFamily(family)) return -EAFNOSUPPORT;
    switch (command->cmdId) {
        case FwmarkCommand::SELECT_NETWORK: {
            if (command->netId != 0 && command->netId != hostNamespaceNetworkId) return -ENONET;
            if (command->netId == hostNamespaceNetworkId) {
                const int status = checkHostNamespace(fd);
                if (status != 0) return status;
            }
            unsigned mark = 0;
            size = sizeof(mark);
            if (getsockopt(fd, SOL_SOCKET, SO_MARK, &mark, &size) != 0) return -errno;
            // An already unmarked socket uses the real host routing namespace.
            // Clearing an existing mark must succeed in the kernel, not a cache.
            if (mark == 0) return 0;
            mark = 0;
            return setsockopt(fd, SOL_SOCKET, SO_MARK, &mark, sizeof(mark)) == 0 ? 0 : -errno;
        }
        case FwmarkCommand::ON_ACCEPT:
        case FwmarkCommand::ON_CONNECT:
        case FwmarkCommand::ON_CONNECT_COMPLETE:
        case FwmarkCommand::ON_SENDMMSG:
        case FwmarkCommand::ON_SENDMSG:
        case FwmarkCommand::ON_SENDTO:
            // No Android routing overlay: preserve host socket routing/marks.
            return 0;
        default:
            return -EOPNOTSUPP;
    }
}
extern "C" int azk_netd_getaddrinfofornet(const char* host, const char* service,
        const addrinfo* hints, unsigned netid, unsigned mark, addrinfo** result) {
    // Use the original permission gate, also before glibc resolves numeric names
    // or serves a cached answer. Unix-domain sockets remain usable when denied.
    int probe = azk_netd_socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (probe < 0) return EAI_SYSTEM;
    const unsigned selected = resolverNetwork(netid) & ~NETID_USE_LOCAL_NAMESERVERS;
    const int status = selected == hostNamespaceNetworkId ? checkHostNamespace(probe) : 0;
    close(probe);
    if (status != 0 || (selected != 0 && selected != hostNamespaceNetworkId) || mark != 0) {
        errno = status != 0 ? -status : ENONET;
        return EAI_SYSTEM;
    }
    return getaddrinfo(host, service, hints, result);
}
extern "C" int android_getaddrinfofornet(const char* host, const char* service,
        const addrinfo* hints, unsigned netid, unsigned mark, addrinfo** result) {
    const int status = azk_netd_getaddrinfofornet(host, service, hints, netid, mark, result);
    // Bionic stores NULL on every failure after its NULL host/service check.
    if (status != 0 && (host != nullptr || service != nullptr)) *result = nullptr;
    return status;
}

// dnsproxyd's network context, restricted to the inherited Linux namespace.
// NetdClient has already selected the process/network DNS override and checked
// its networking permission before opening the proxy connection.
extern "C" int azk_netd_dns_network(unsigned netid) {
    const unsigned selected = netid & ~NETID_USE_LOCAL_NAMESERVERS;
    if (selected != 0 && selected != hostNamespaceNetworkId) return -ENONET;
    const int fd = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    const int status = checkHostNamespace(fd);
    close(fd);
    return status == 0 ? static_cast<int>(hostNamespaceNetworkId) : status;
}
