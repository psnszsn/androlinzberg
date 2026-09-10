// Hosted ZygoteConnection admission. SO_PEERCRED alone can name a dead/reused
// PID, and the outer Linux UID is not Android system authority. Pin the socket
// peer's kernel lifetime around the broker's active-system-role query. This C
// boundary owns both temporary descriptors; no descriptor escapes to Zig/JNI.
#define _GNU_SOURCE
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

static int peer_alive(int pidfd) {
    struct pollfd descriptor = { .fd = pidfd, .events = POLLIN };
    return poll(&descriptor, 1, 0) == 0 && descriptor.revents == 0;
}

__attribute__((visibility("hidden")))
int azk_binder_is_system_socket_peer(int fd, int (*authorize)(void *, int), void *context) {
    int socket = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (socket < 0) return 0;
    int pidfd = -1;
    socklen_t size = sizeof(pidfd);
    int accepted = 0;
    if (getsockopt(socket, SOL_SOCKET, SO_PEERPIDFD, &pidfd, &size) != 0) goto done;
    if (size != sizeof(pidfd) || pidfd < 0) goto done;
    struct ucred peer;
    size = sizeof(peer);
    if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &peer, &size) != 0 ||
        size != sizeof(peer) || peer.pid <= 0) goto done;
    if (peer_alive(pidfd) && authorize(context, peer.pid) && peer_alive(pidfd)) accepted = 1;
done:
    if (pidfd >= 0) close(pidfd);
    close(socket);
    return accepted;
}
