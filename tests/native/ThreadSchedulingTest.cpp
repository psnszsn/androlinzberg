#include <cassert>
#include <cerrno>
#include <climits>
#include <sys/resource.h>
#include <unistd.h>

extern "C" int azk_setpriority(__priority_which_t, id_t, int);
static int rejection, actual, expected_priority, logs, checks;
static bool own_thread;
extern "C" int azk_test_setpriority(__priority_which_t, id_t, int priority) noexcept {
    assert(priority == expected_priority);
    if (rejection) { errno = rejection; return -1; }
    actual = priority;
    return 0;
}
extern "C" int azk_test_getpriority(__priority_which_t which, id_t) noexcept {
    assert(which == PRIO_PROCESS);
    return actual;
}
extern "C" int azk_test_tgkill(pid_t process, pid_t thread, int signal) noexcept {
    assert(process == getpid() && thread > 0 && signal == 0);
    ++checks;
    if (own_thread) return 0;
    errno = ESRCH;
    return -1;
}
extern "C" int azk_test_log(int, const char*, const char*, ...) { ++logs; return 0; }

int main() {
    own_thread = true;
    actual = 0;
    expected_priority = -2;
    rejection = 0;
    assert(azk_setpriority(PRIO_PROCESS, 0, -2) == 0 && actual == -2 && checks == 0);
    actual = 0;
    rejection = EACCES;
    assert(azk_setpriority(PRIO_PROCESS, 0, -2) == 0 && actual == 0 && errno == 0);
    rejection = EPERM;
    assert(azk_setpriority(PRIO_PROCESS, gettid(), -2) == 0 && actual == 0 && logs == 1);
    own_thread = false;
    assert(azk_setpriority(PRIO_PROCESS, 123, -2) == -1 && errno == EPERM);
    rejection = ESRCH;
    assert(azk_setpriority(PRIO_PROCESS, INT_MAX, -2) == -1 && errno == ESRCH);
    rejection = EINVAL;
    assert(azk_setpriority(PRIO_PROCESS, 0, -2) == -1 && errno == EINVAL);
    own_thread = true;
    rejection = EACCES;
    assert(azk_setpriority(PRIO_USER, 0, -2) == -1 && errno == EACCES);
    expected_priority = 10; // A denied non-boost is not this fallback's contract.
    assert(azk_setpriority(PRIO_PROCESS, 0, 10) == -1 && errno == EACCES);
    rejection = 0;
    assert(azk_setpriority(PRIO_PROCESS, 0, 10) == 0 && actual == 10);
}
