// Bionic's netdb ABI over the host resolver. bionic_translation converts the
// addrinfo layout only; pinned bionic/libc/include/netdb.h numbers AI_*, NI_*
// and EAI_* differently from glibc. Android native code sees Bionic's values
// and validation order; host-ABI callers keep using azk_host_getaddrinfo.
#include "network_hooks.h"
#include <errno.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>

enum {
    BIONIC_AI_PASSIVE = 0x1,
    BIONIC_AI_CANONNAME = 0x2,
    BIONIC_AI_NUMERICHOST = 0x4,
    BIONIC_AI_NUMERICSERV = 0x8,
    BIONIC_AI_ADDRCONFIG = 0x400,
    BIONIC_AI_MASK = BIONIC_AI_PASSIVE | BIONIC_AI_CANONNAME | BIONIC_AI_NUMERICHOST |
        BIONIC_AI_NUMERICSERV | BIONIC_AI_ADDRCONFIG,
    BIONIC_NI_NOFQDN = 0x1,
    BIONIC_NI_NUMERICHOST = 0x2,
    BIONIC_NI_NAMEREQD = 0x4,
    BIONIC_NI_NUMERICSERV = 0x8,
    BIONIC_NI_DGRAM = 0x10,
    BIONIC_EAI_ADDRFAMILY = 1,
    BIONIC_EAI_AGAIN,
    BIONIC_EAI_BADFLAGS,
    BIONIC_EAI_FAIL,
    BIONIC_EAI_FAMILY,
    BIONIC_EAI_MEMORY,
    BIONIC_EAI_NODATA,
    BIONIC_EAI_NONAME,
    BIONIC_EAI_SERVICE,
    BIONIC_EAI_SOCKTYPE,
    BIONIC_EAI_SYSTEM,
    BIONIC_EAI_BADHINTS,
    BIONIC_EAI_PROTOCOL,
    BIONIC_EAI_OVERFLOW,
    BIONIC_EAI_MAX,
};

static int bionic_eai(int status) {
    switch (status) {
    case 0: return 0;
    case EAI_ADDRFAMILY: return BIONIC_EAI_ADDRFAMILY;
    case EAI_AGAIN: return BIONIC_EAI_AGAIN;
    case EAI_BADFLAGS: return BIONIC_EAI_BADFLAGS;
    case EAI_FAIL: return BIONIC_EAI_FAIL;
    case EAI_FAMILY: return BIONIC_EAI_FAMILY;
    case EAI_MEMORY: return BIONIC_EAI_MEMORY;
    case EAI_NODATA: return BIONIC_EAI_NODATA;
    case EAI_NONAME: return BIONIC_EAI_NONAME;
    case EAI_SERVICE: return BIONIC_EAI_SERVICE;
    case EAI_SOCKTYPE: return BIONIC_EAI_SOCKTYPE;
    case EAI_SYSTEM: return BIONIC_EAI_SYSTEM;
    case EAI_OVERFLOW: return BIONIC_EAI_OVERFLOW;
    case EAI_INTR:
        // Bionic has no EAI_INTR; its resolver reports the interrupted call.
        errno = EINTR;
        return BIONIC_EAI_SYSTEM;
    default: return BIONIC_EAI_FAIL;
    }
}

static int host_ai_flags(int flags) {
    return (flags & (BIONIC_AI_PASSIVE | BIONIC_AI_CANONNAME | BIONIC_AI_NUMERICHOST)) |
        (flags & BIONIC_AI_NUMERICSERV ? AI_NUMERICSERV : 0) |
        (flags & BIONIC_AI_ADDRCONFIG ? AI_ADDRCONFIG : 0);
}

// bionic_translation's bionic_getaddrinfo has already moved ai_canonname and
// ai_addr into glibc positions; ai_flags still carries Bionic's values.
int azk_bionic_getaddrinfo(const char* host, const char* service,
        const struct addrinfo* hints, struct addrinfo** result) {
    // Pinned android_getaddrinfofornetcontext: this check precedes *res = NULL.
    if (host == NULL && service == NULL) return BIONIC_EAI_NONAME;
    // Bionic uses zero flags without hints; glibc would add AI_V4MAPPED|AI_ADDRCONFIG.
    struct addrinfo host_hints = { .ai_family = AF_UNSPEC };
    if (hints != NULL) {
        int error = 0;
        if (hints->ai_addrlen || hints->ai_canonname || hints->ai_addr || hints->ai_next)
            error = BIONIC_EAI_BADHINTS;
        else if (hints->ai_flags & ~BIONIC_AI_MASK)
            error = BIONIC_EAI_BADFLAGS;
        else if (hints->ai_family != AF_UNSPEC && hints->ai_family != AF_INET && hints->ai_family != AF_INET6)
            error = BIONIC_EAI_FAMILY;
        // Bionic's explore table pairs datagram with UDP and stream with TCP.
        else if (hints->ai_socktype != 0 && hints->ai_protocol != 0 &&
                ((hints->ai_socktype == SOCK_DGRAM && hints->ai_protocol != IPPROTO_UDP) ||
                 (hints->ai_socktype == SOCK_STREAM && hints->ai_protocol != IPPROTO_TCP)))
            error = BIONIC_EAI_BADHINTS;
        if (error != 0) {
            *result = NULL;
            return error;
        }
        host_hints.ai_flags = host_ai_flags(hints->ai_flags);
        host_hints.ai_family = hints->ai_family;
        host_hints.ai_socktype = hints->ai_socktype;
        host_hints.ai_protocol = hints->ai_protocol;
    }
    return bionic_eai(azk_host_getaddrinfo(host, service, &host_hints, result));
}

// Pinned getnameinfo ignores flags it does not define and always appends a
// nonzero IPv6 scope, so only the five standard flags reach the host.
int bionic_getnameinfo(const struct sockaddr* address, socklen_t size, char* host, size_t host_size,
        char* service, size_t service_size, int flags) {
    switch (address->sa_family) {
    case AF_INET:
    case AF_INET6:
    case AF_LOCAL:
        break;
    default:
        return BIONIC_EAI_FAMILY;
    }
    const int host_flags = (flags & BIONIC_NI_NOFQDN ? NI_NOFQDN : 0) |
        (flags & BIONIC_NI_NUMERICHOST ? NI_NUMERICHOST : 0) |
        (flags & BIONIC_NI_NAMEREQD ? NI_NAMEREQD : 0) |
        (flags & BIONIC_NI_NUMERICSERV ? NI_NUMERICSERV : 0) |
        (flags & BIONIC_NI_DGRAM ? NI_DGRAM : 0);
    return bionic_eai(getnameinfo(address, size, host, host_size > UINT32_MAX ? UINT32_MAX : (socklen_t)host_size,
        service, service_size > UINT32_MAX ? UINT32_MAX : (socklen_t)service_size, host_flags));
}

const char* bionic_gai_strerror(int code) {
    // Pinned getaddrinfo.c ai_errlist.
    static const char* const messages[] = {
        "Success",
        "Address family for hostname not supported",
        "Temporary failure in name resolution",
        "Invalid value for ai_flags",
        "Non-recoverable failure in name resolution",
        "ai_family not supported",
        "Memory allocation failure",
        "No address associated with hostname",
        "hostname nor servname provided, or not known",
        "servname not supported for ai_socktype",
        "ai_socktype not supported",
        "System error returned in errno",
        "Invalid value for hints",
        "Resolved protocol is unknown",
        "Argument buffer overflow",
        "Unknown error",
    };
    return messages[code < 0 || code > BIONIC_EAI_MAX ? BIONIC_EAI_MAX : code];
}
