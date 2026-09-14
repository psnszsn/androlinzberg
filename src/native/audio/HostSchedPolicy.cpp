// libprocessgroup sched_policy.cpp, non-Android branch: no cgroup controllers
// exist on this host, so thread scheduling groups are reported as SP_SYSTEM.
// The stock file cannot be compiled here because it includes the Android
// cgroup map even for that branch.
#include <processgroup/sched_policy.h>

int set_sched_policy(int, SchedPolicy) {
    return 0;
}

int get_sched_policy(int, SchedPolicy* policy) {
    *policy = SP_SYSTEM_DEFAULT;
    return 0;
}
