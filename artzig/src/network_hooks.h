#pragma once
#include <netdb.h>
#include <sys/socket.h>
#ifdef __cplusplus
extern "C" {
#endif
// Bionic's NetdClientDispatch equivalent at the hosted libc boundary. Before
// framework loading, ordinary host networking is available. The installed
// original NetdClient owns policy as soon as its library enters the process.
int azk_host_socket(int, int, int) __THROW;
int azk_host_connect(int, const struct sockaddr*, socklen_t);
int azk_host_accept4(int, struct sockaddr*, socklen_t*, int);
int azk_host_accept(int, struct sockaddr*, socklen_t*);
int azk_host_getaddrinfo(const char*, const char*, const struct addrinfo*, struct addrinfo**);
int azk_host_getaddrinfofornet(const char*, const char*, const struct addrinfo*, unsigned, unsigned, struct addrinfo**);
#ifdef __cplusplus
}
#endif
