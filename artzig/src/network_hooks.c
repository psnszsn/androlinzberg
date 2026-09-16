// Host counterpart of Bionic NetdClientDispatch: route socket operations from
// original libcore and Android native libraries through one process policy.
// Look up on every call: a pre-framework lookup must not cache absence forever.
#include "network_hooks.h"
#include <dlfcn.h>
#include <errno.h>
#include <stddef.h>

int azk_host_socket(int domain, int type, int protocol) {
    int (*call)(int, int, int) = dlsym(RTLD_DEFAULT, "azk_netd_socket");
    return call ? call(domain, type, protocol) : socket(domain, type, protocol);
}
int azk_host_connect(int fd, const struct sockaddr* address, socklen_t size) {
    int (*call)(int, const struct sockaddr*, socklen_t) = dlsym(RTLD_DEFAULT, "azk_netd_connect");
    return call ? call(fd, address, size) : connect(fd, address, size);
}
int azk_host_accept4(int fd, struct sockaddr* address, socklen_t* size, int flags) {
    int (*call)(int, struct sockaddr*, socklen_t*, int) = dlsym(RTLD_DEFAULT, "azk_netd_accept4");
    return call ? call(fd, address, size, flags) : accept4(fd, address, size, flags);
}
int azk_host_accept(int fd, struct sockaddr* address, socklen_t* size) {
    return azk_host_accept4(fd, address, size, 0);
}
int azk_host_getaddrinfofornet(const char* host, const char* service,
        const struct addrinfo* hints, unsigned netid, unsigned mark, struct addrinfo** result) {
    int (*call)(const char*, const char*, const struct addrinfo*, unsigned, unsigned, struct addrinfo**) =
        dlsym(RTLD_DEFAULT, "azk_netd_getaddrinfofornet");
    if (call) return call(host, service, hints, netid, mark, result);
    if (netid != 0 || mark != 0) {
        errno = ENONET;
        return EAI_SYSTEM;
    }
    return getaddrinfo(host, service, hints, result);
}
int azk_host_getaddrinfo(const char* host, const char* service,
        const struct addrinfo* hints, struct addrinfo** result) {
    return azk_host_getaddrinfofornet(host, service, hints, 0, 0, result);
}
// bionic_translation resolves these before its generic libc forwarding table.
int bionic_socket(int domain, int type, int protocol) { return azk_host_socket(domain, type, protocol); }
int bionic_connect(int fd, const struct sockaddr* address, socklen_t size) { return azk_host_connect(fd, address, size); }
int bionic_accept4(int fd, struct sockaddr* address, socklen_t* size, int flags) { return azk_host_accept4(fd, address, size, flags); }
int bionic_accept(int fd, struct sockaddr* address, socklen_t* size) { return azk_host_accept(fd, address, size); }
