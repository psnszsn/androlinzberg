/* artzig stub. */
package android.app;
public class ActivityManager {
    public static class AppTask {
        public void finishAndRemoveTask() {}
        public void setExcludeFromRecents(boolean exclude) {}
        public void moveToFront() {}
    }
    public java.util.List<AppTask> getAppTasks() { return new java.util.ArrayList<>(); }
    public static class TaskDescription {
        public TaskDescription() {}
        public TaskDescription(String label) {}
        public TaskDescription(String label, int iconRes) {}
        public String getLabel() { return null; }
    }
    public int getMemoryClass() { return 256; }
    public int getLargeMemoryClass() { return 512; }
    public static boolean isUserAMonkey() { return false; }

    public static boolean isHighEndGfx() { return true; }
    public static boolean isLowRamDeviceStatic() { return false; }
    public static int getCurrentUser() { return 0; }
    public static IActivityManager getService() { return null; }
    public static boolean isSystemReady() { return true; }
    public boolean isLowRamDevice() { return false; }
    public int getLauncherLargeIconDensity() { return 160; }
    public int getLauncherLargeIconSize() { return 48; }
    public static class RunningTaskInfo extends TaskInfo {
        public static final android.os.Parcelable.Creator<RunningTaskInfo> CREATOR =
                new android.os.Parcelable.Creator<RunningTaskInfo>() {
            public RunningTaskInfo createFromParcel(android.os.Parcel in) { return new RunningTaskInfo(); }
            public RunningTaskInfo[] newArray(int size) { return new RunningTaskInfo[size]; }
        };
    }
    public static class RunningAppProcessInfo {
        public String processName;
        public int pid, uid;
        public int importance = 100;
        public static final int IMPORTANCE_FOREGROUND = 100;
        public RunningAppProcessInfo() {}
        public RunningAppProcessInfo(String processName, int pid, String[] arr) {
            this.processName = processName; this.pid = pid;
        }
    }
    public java.util.List<RunningAppProcessInfo> getRunningAppProcesses() {
        RunningAppProcessInfo info = new RunningAppProcessInfo(
                ActivityThread.currentProcessName(), android.os.Process.myPid(), null);
        java.util.ArrayList<RunningAppProcessInfo> l = new java.util.ArrayList<>();
        l.add(info);
        return l;
    }
    /** AOSP API 30; the host has no system_server exit-history store. */
    public java.util.List getHistoricalProcessExitReasons(
            String packageName, int pid, int maxNum) {
        return new java.util.ArrayList();
    }
    public static class MemoryInfo {
        public long availMem, totalMem, threshold;
        public boolean lowMemory;
    }
    public void getMemoryInfo(MemoryInfo outInfo) {
        outInfo.availMem = 1L << 31; outInfo.totalMem = 1L << 32; outInfo.threshold = 1L << 28;
    }
}
