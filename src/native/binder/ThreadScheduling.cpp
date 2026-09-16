// Host setpriority boundary beneath AOSP libutils Threads.cpp. Android init
// grants RLIMIT_NICE=40; an ordinary desktop login may grant no boosts. Try the
// real operation first and tolerate only a denied boost of our own live thread.
// Queries always return the kernel priority; no requested priority is cached.
#include <atomic>
#include <cerrno>
#include <climits>
#include <signal.h>
#include <sys/resource.h>
#include <unistd.h>
#include <android/log.h>

extern "C" int azk_setpriority(__priority_which_t which, id_t who, int priority) {
    const int result = setpriority(which, who, priority);
    if (result == 0) return 0;
    const int failure = errno;
    if (which == PRIO_PROCESS && who <= INT_MAX && (failure == EACCES || failure == EPERM)) {
        const pid_t tid = who == 0 ? gettid() : static_cast<pid_t>(who);
        // Signal zero validates membership without delivering a signal. Do not
        // turn a cross-process permission failure into a successful operation.
        if (tgkill(getpid(), tid, 0) == 0) {
            errno = 0;
            const int actual = getpriority(PRIO_PROCESS, who);
            if (errno == 0 && priority < actual) {
                static std::atomic_flag reported = ATOMIC_FLAG_INIT;
                if (!reported.test_and_set()) {
                    __android_log_print(ANDROID_LOG_INFO, "HostScheduling",
                        "Priority boost denied; keeping kernel nice %d. "
                        "Configure RLIMIT_NICE for Android priority boosts.", actual);
                }
                errno = 0;
                return 0;
            }
        }
    }
    errno = failure;
    return result;
}
