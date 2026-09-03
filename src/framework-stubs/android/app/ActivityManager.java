/* artzig stub. */
package android.app;
public class ActivityManager {
    public static class AppTask {
        public void finishAndRemoveTask() {}
        public void setExcludeFromRecents(boolean exclude) {}
        public void moveToFront() {}
    }
    public java.util.List<AppTask> getAppTasks() { return new java.util.ArrayList<>(); }
    /** AOSP ActivityManager.TaskDescription (label/icon only). */
    public static class TaskDescription implements android.os.Parcelable {
        private String mLabel; private int mIconRes;
        public TaskDescription() {}
        public TaskDescription(String label) { mLabel = label; }
        public TaskDescription(String label, int iconRes) { mLabel = label; mIconRes = iconRes; }
        public String getLabel() { return mLabel; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeString(mLabel); dest.writeInt(mIconRes); }
        public void readFromParcel(android.os.Parcel source) { mLabel = source.readString(); mIconRes = source.readInt(); }
        public static final Creator<TaskDescription> CREATOR = new Creator<TaskDescription>() {
            @Override public TaskDescription createFromParcel(android.os.Parcel source) { TaskDescription t = new TaskDescription(); t.readFromParcel(source); return t; }
            @Override public TaskDescription[] newArray(int size) { return new TaskDescription[size]; }
        };
    }
    /** AOSP ActivityManager.PendingIntentInfo. */
    public static final class PendingIntentInfo implements android.os.Parcelable {
        private final String mCreatorPackage; private final int mCreatorUid; private final boolean mImmutable; private final int mIntentSenderType;
        public PendingIntentInfo(String creatorPackage, int creatorUid, boolean immutable, int intentSenderType) {
            mCreatorPackage = creatorPackage; mCreatorUid = creatorUid; mImmutable = immutable; mIntentSenderType = intentSenderType;
        }
        public String getCreatorPackage() { return mCreatorPackage; }
        public int getCreatorUid() { return mCreatorUid; }
        public boolean isImmutable() { return mImmutable; }
        public int getIntentSenderType() { return mIntentSenderType; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeString(mCreatorPackage); dest.writeInt(mCreatorUid); dest.writeBoolean(mImmutable); dest.writeInt(mIntentSenderType); }
        public static final Creator<PendingIntentInfo> CREATOR = new Creator<PendingIntentInfo>() {
            @Override public PendingIntentInfo createFromParcel(android.os.Parcel in) { return new PendingIntentInfo(in.readString(), in.readInt(), in.readBoolean(), in.readInt()); }
            @Override public PendingIntentInfo[] newArray(int size) { return new PendingIntentInfo[size]; }
        };
    }
    public int getMemoryClass() { return 256; }
    public int getLargeMemoryClass() { return 512; }
    public static boolean isUserAMonkey() { return false; }

    /**
     * AOSP ActivityManager.isBackgroundRestricted(). Android asks system_server whether the user
     * applied a per-application background restriction. The hosted runtime has no system_server or
     * host-side equivalent policy, so applications are observably unrestricted.
     */
    public boolean isBackgroundRestricted() { return false; }

    public static boolean isHighEndGfx() { return true; }
    public static boolean isLowRamDeviceStatic() { return false; }
    public static int getCurrentUser() { return 0; }
    public static final int ASSIST_CONTEXT_BASIC = 0;
    public static final int ASSIST_CONTEXT_FULL = 1;
    public static final int ASSIST_CONTEXT_CONTENT = 2;
    public static final int ASSIST_CONTEXT_AUTOFILL = 3;
    /** Hosted processes run foreground; the single user is always the current one. */
    public int getUidImportance(int uid) { return RunningAppProcessInfo.IMPORTANCE_FOREGROUND; }
    public boolean isProfileForeground(android.os.UserHandle userHandle) { return userHandle != null && userHandle.getIdentifier() == getCurrentUser(); }
    /** AOSP: the activity manager binder ("activity"), cached once resolved. */
    private static volatile IActivityManager sService;
    public static IActivityManager getService() {
        IActivityManager s = sService;
        if (s != null) return s;
        android.os.IBinder b = android.os.ServiceManager.getService(android.content.Context.ACTIVITY_SERVICE);
        if (b == null) return null;
        s = IActivityManager.Stub.asInterface(b);
        sService = s;
        return s;
    }
    /** AOSP ActivityManager.INTENT_SENDER_* (PendingIntent kinds). */
    public static final int INTENT_SENDER_BROADCAST = 1, INTENT_SENDER_ACTIVITY = 2, INTENT_SENDER_ACTIVITY_RESULT = 3,
            INTENT_SENDER_SERVICE = 4, INTENT_SENDER_FOREGROUND_SERVICE = 5;
    /** AOSP IActivityManager start result codes (ActivityManager.START_*). */
    public static final int START_SUCCESS = 0, START_INTENT_NOT_RESOLVED = -1, START_CLASS_NOT_FOUND = -2,
            START_FORWARD_AND_REQUEST_CONFLICT = -3, START_PERMISSION_DENIED = -4, START_NOT_ACTIVITY = -5,
            START_CANCELED = -6, START_NOT_VOICE_COMPATIBLE = -7, START_VOICE_NOT_ACTIVE_SESSION = -8,
            START_VOICE_HIDDEN_SESSION = -9, START_ASSISTANT_NOT_ACTIVE_SESSION = -10, START_ASSISTANT_HIDDEN_SESSION = -11,
            START_RETURN_INTENT_TO_CALLER = 1, START_TASK_TO_FRONT = 2, START_DELIVERED_TO_TOP = 3,
            START_SWITCHES_CANCELED = 4, START_RETURN_LOCK_TASK_MODE_VIOLATION = 5, START_ABORTED = 102;
    /** AOSP ProcessStateEnum.UNKNOWN. */
    public static final int PROCESS_STATE_UNKNOWN = -1;
    public static final int PROCESS_STATE_PERSISTENT = 0, PROCESS_STATE_PERSISTENT_UI = 1;
    public static final int PROCESS_STATE_TOP = 2;
    public static final int PROCESS_STATE_BOUND_TOP = 3, PROCESS_STATE_FOREGROUND_SERVICE = 4,
            PROCESS_STATE_BOUND_FOREGROUND_SERVICE = 5;
    /** AOSP ActivityManager.isStartResultFatalError: START_* codes in [-100, -1]. */
    public static boolean isStartResultFatalError(int result) { return -100 <= result && result <= -1; }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ProcessState {}
    /** AOSP ActivityManager.RunningServiceInfo (data shape; IActivityManager parcels it). */
    public static class RunningServiceInfo implements android.os.Parcelable {
        public android.content.ComponentName service;
        public int pid, uid;
        public String process;
        public boolean foreground, started;
        public int clientCount, crashCount;
        public long activeSince, lastActivityTime, restarting;
        public int flags, clientLabel;
        public String clientPackage;
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            android.content.ComponentName.writeToParcel(service, dest);
            dest.writeInt(pid); dest.writeInt(uid); dest.writeString(process);
            dest.writeInt(foreground ? 1 : 0); dest.writeInt(started ? 1 : 0);
            dest.writeInt(clientCount); dest.writeInt(crashCount);
            dest.writeLong(activeSince); dest.writeLong(lastActivityTime); dest.writeLong(restarting);
            dest.writeInt(this.flags); dest.writeInt(clientLabel); dest.writeString(clientPackage);
        }
        public void readFromParcel(android.os.Parcel source) {
            service = android.content.ComponentName.readFromParcel(source);
            pid = source.readInt(); uid = source.readInt(); process = source.readString();
            foreground = source.readInt() != 0; started = source.readInt() != 0;
            clientCount = source.readInt(); crashCount = source.readInt();
            activeSince = source.readLong(); lastActivityTime = source.readLong(); restarting = source.readLong();
            flags = source.readInt(); clientLabel = source.readInt(); clientPackage = source.readString();
        }
        public static final Creator<RunningServiceInfo> CREATOR = new Creator<RunningServiceInfo>() {
            @Override public RunningServiceInfo createFromParcel(android.os.Parcel source) { RunningServiceInfo r = new RunningServiceInfo(); r.readFromParcel(source); return r; }
            @Override public RunningServiceInfo[] newArray(int size) { return new RunningServiceInfo[size]; }
        };
    }
    /** AOSP ActivityManager.ProcessErrorStateInfo. */
    public static class ProcessErrorStateInfo implements android.os.Parcelable {
        public static final int NO_ERROR = 0, CRASHED = 1, NOT_RESPONDING = 2;
        public int condition, pid, uid;
        public String processName, tag, shortMsg, longMsg, stackTrace;
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(condition); dest.writeString(processName); dest.writeInt(pid); dest.writeInt(uid);
            dest.writeString(tag); dest.writeString(shortMsg); dest.writeString(longMsg); dest.writeString(stackTrace);
        }
        public void readFromParcel(android.os.Parcel source) {
            condition = source.readInt(); processName = source.readString(); pid = source.readInt(); uid = source.readInt();
            tag = source.readString(); shortMsg = source.readString(); longMsg = source.readString(); stackTrace = source.readString();
        }
        public static final Creator<ProcessErrorStateInfo> CREATOR = new Creator<ProcessErrorStateInfo>() {
            @Override public ProcessErrorStateInfo createFromParcel(android.os.Parcel source) { ProcessErrorStateInfo r = new ProcessErrorStateInfo(); r.readFromParcel(source); return r; }
            @Override public ProcessErrorStateInfo[] newArray(int size) { return new ProcessErrorStateInfo[size]; }
        };
    }
    /** AOSP ActivityManager.RecentTaskInfo. */
    public static class RecentTaskInfo extends TaskInfo implements android.os.Parcelable {
        public int id, persistentId;
        public static final Creator<RecentTaskInfo> CREATOR = new Creator<RecentTaskInfo>() {
            @Override public RecentTaskInfo createFromParcel(android.os.Parcel source) { RecentTaskInfo r = new RecentTaskInfo(); r.id = source.readInt(); r.persistentId = source.readInt(); return r; }
            @Override public RecentTaskInfo[] newArray(int size) { return new RecentTaskInfo[size]; }
        };
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeInt(id); dest.writeInt(persistentId); }
    }
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
    public static class RunningAppProcessInfo implements android.os.Parcelable {
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
        public @interface Importance {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeString(processName); dest.writeInt(pid); dest.writeInt(uid); dest.writeInt(importance); }
        public void readFromParcel(android.os.Parcel source) { processName = source.readString(); pid = source.readInt(); uid = source.readInt(); importance = source.readInt(); }
        public static final Creator<RunningAppProcessInfo> CREATOR = new Creator<RunningAppProcessInfo>() {
            @Override public RunningAppProcessInfo createFromParcel(android.os.Parcel in) { RunningAppProcessInfo r = new RunningAppProcessInfo(); r.processName = in.readString(); r.pid = in.readInt(); r.uid = in.readInt(); r.importance = in.readInt(); return r; }
            @Override public RunningAppProcessInfo[] newArray(int size) { return new RunningAppProcessInfo[size]; }
        };
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
    public static class MemoryInfo implements android.os.Parcelable {
        public long availMem, totalMem, threshold;
        public boolean lowMemory;
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeLong(availMem); dest.writeLong(totalMem); dest.writeLong(threshold); dest.writeBoolean(lowMemory); }
        public void readFromParcel(android.os.Parcel source) { availMem = source.readLong(); totalMem = source.readLong(); threshold = source.readLong(); lowMemory = source.readBoolean(); }
        public static final Creator<MemoryInfo> CREATOR = new Creator<MemoryInfo>() {
            @Override public MemoryInfo createFromParcel(android.os.Parcel source) { MemoryInfo m = new MemoryInfo(); m.readFromParcel(source); return m; }
            @Override public MemoryInfo[] newArray(int size) { return new MemoryInfo[size]; }
        };
    }
    public void getMemoryInfo(MemoryInfo outInfo) {
        outInfo.availMem = 1L << 31; outInfo.totalMem = 1L << 32; outInfo.threshold = 1L << 28;
    }
}
