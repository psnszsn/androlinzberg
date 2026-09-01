/* artzig shim: android.os.Process. */
package android.os;
public class Process {
    public static final int FIRST_APPLICATION_UID = 10000, LAST_APPLICATION_UID = 19999,
            SYSTEM_UID = 1000, ROOT_UID = 0, SHELL_UID = 2000, MY_UID = 10001,
            NOBODY_UID = 9999,
            INVALID_UID = -1, INVALID_PID = -1;
    /** AOSP thread/process scheduling groups (Process.THREAD_GROUP_*). */
    public static final int THREAD_GROUP_DEFAULT = -1, THREAD_GROUP_BACKGROUND = 0,
            THREAD_GROUP_FOREGROUND = 1, THREAD_GROUP_SYSTEM = 2, THREAD_GROUP_AUDIO_APP = 3,
            THREAD_GROUP_AUDIO_SYS = 4, THREAD_GROUP_TOP_APP = 5, THREAD_GROUP_RT_APP = 6,
            THREAD_GROUP_RESTRICTED = 7;
    /** No isolated-uid sandboxing on this host. */
    public static boolean isIsolated() { return false; }
    /** No cgroup scheduling groups on this host: every thread reports the default group. */
    public static int getProcessGroup(int tid) { return THREAD_GROUP_DEFAULT; }
    public static final int THREAD_PRIORITY_DEFAULT = 0, THREAD_PRIORITY_LOWEST = 19,
            THREAD_PRIORITY_BACKGROUND = 10, THREAD_PRIORITY_FOREGROUND = -2,
            THREAD_PRIORITY_DISPLAY = -4, THREAD_PRIORITY_URGENT_DISPLAY = -8,
            THREAD_PRIORITY_VIDEO = -10, THREAD_PRIORITY_AUDIO = -16,
            THREAD_PRIORITY_URGENT_AUDIO = -19, THREAD_PRIORITY_MORE_FAVORABLE = -1,
            THREAD_PRIORITY_LESS_FAVORABLE = 1;
    public static int myPid() { try { return android.system.Os.getpid(); } catch (Throwable t) { return 1; } }
    public static int myTid() { return (int) Thread.currentThread().getId(); }
    /** The broker-assigned uid once this process's Binder connection exists; the constant before. */
    public static int myUid() {
        int assigned = dev.artzig.BinderSession.assignedUid();
        return assigned >= 0 ? assigned : MY_UID;
    }
    public static String myProcessName() { return "artzig"; }
    public static UserHandle myUserHandle() { return UserHandle.of(0); }
    public static void setThreadPriority(int priority) {}
    public static void setThreadPriority(int tid, int priority) {}
    public static int getThreadPriority(int tid) { return 0; }
    public static boolean is64Bit() { return true; }
    public static boolean isApplicationUid(int uid) { return uid >= FIRST_APPLICATION_UID; }
    public static long getElapsedCpuTime() { return System.nanoTime() / 1_000_000L; }
    public static void killProcess(int pid) { if (pid == myPid()) System.exit(0); }
}
