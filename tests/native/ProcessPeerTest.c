// Verify the kernel lifetime half of hosted Zygote socket authorization. Broker
// role/delegation policy is exercised by real-process broker_test.zig peers.
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

extern int azk_binder_is_system_socket_peer(int, int (*)(void *, int), void *);

struct authority { pid_t pid; int allowed; int calls; int exit_during_query; };
static int authorize(void *context, int pid) {
    struct authority *authority = context;
    assert(pid == authority->pid);
    authority->calls++;
    if (authority->exit_during_query) {
        assert(kill(pid, SIGKILL) == 0);
        assert(waitpid(pid, NULL, 0) == pid);
    }
    return authority->allowed;
}

int main(void) {
    alarm(15);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    assert(snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
        "azk-process-peer-%d", getpid()) > 0);
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(address.sun_path + 1);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(server >= 0 && bind(server, (struct sockaddr *)&address, length) == 0);
    assert(listen(server, 1) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        close(server);
        int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        assert(socket_fd >= 0 && connect(socket_fd, (struct sockaddr *)&address, length) == 0);
        for (;;) pause();
    }
    int peer = accept4(server, NULL, NULL, SOCK_CLOEXEC);
    assert(peer >= 0);
    struct authority authority = { .pid = child, .allowed = 1 };
    for (int i = 0; i < 100; ++i) {
        assert(azk_binder_is_system_socket_peer(peer, authorize, &authority) == 1);
        authority.allowed = 0;
        assert(azk_binder_is_system_socket_peer(peer, authorize, &authority) == 0);
        authority.allowed = 1;
    }
    assert(authority.calls == 200);
    authority.exit_during_query = 1;
    assert(azk_binder_is_system_socket_peer(peer, authorize, &authority) == 0);
    assert(authority.calls == 201);
    // SO_PEERCRED still names the old child; no further authorization query is
    // permitted after its pidfd reports exit, even if the callback would allow it.
    assert(azk_binder_is_system_socket_peer(peer, authorize, &authority) == 0);
    assert(azk_binder_is_system_socket_peer(-1, authorize, &authority) == 0);
    assert(authority.calls == 201);
    close(peer);
    close(server);
    return 0;
}
