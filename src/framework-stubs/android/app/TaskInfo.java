/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class TaskInfo implements android.os.Parcelable {
    public int taskId, displayId, userId;
    public android.content.ComponentName topActivity, baseActivity;
    public android.content.Intent baseIntent;
    public boolean isRunning = true, isVisible = true;
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final Creator<TaskInfo> CREATOR = new Creator<TaskInfo>() {
        public TaskInfo createFromParcel(android.os.Parcel in) { return new TaskInfo(); }
        public TaskInfo[] newArray(int size) { return new TaskInfo[size]; }
    };
}
