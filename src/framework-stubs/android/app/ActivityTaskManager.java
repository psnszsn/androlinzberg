/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class ActivityTaskManager {
    public static final int INVALID_TASK_ID = -1;
    /** AOSP ActivityTaskManager.RootTaskInfo (data shape; IActivityManager parcels it). */
    public static class RootTaskInfo extends TaskInfo implements android.os.Parcelable {
        public android.graphics.Rect bounds = new android.graphics.Rect();
        public int[] childTaskIds;
        public String[] childTaskNames;
        public android.graphics.Rect[] childTaskBounds;
        public int[] childTaskUserIds;
        public boolean visible;
        public int position;
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(taskId); dest.writeBoolean(visible); dest.writeInt(position);
        }
        public static final Creator<RootTaskInfo> CREATOR = new Creator<RootTaskInfo>() {
            @Override public RootTaskInfo createFromParcel(android.os.Parcel in) { RootTaskInfo r = new RootTaskInfo(); r.taskId = in.readInt(); r.visible = in.readBoolean(); r.position = in.readInt(); return r; }
            @Override public RootTaskInfo[] newArray(int size) { return new RootTaskInfo[size]; }
        };
    }
    /** No activity task manager service on this host (StrictMode null-checks it). */
    public static IActivityTaskManager getService() { return null; }
    public static final int INVALID_STACK_ID = -1;
    public static int getDefaultAppRecentsLimitStatic() { return 16; }
    public static boolean supportsMultiWindow(android.content.Context context) { return false; }
    public static boolean supportsSplitScreenMultiWindow(android.content.Context context) { return false; }
}
