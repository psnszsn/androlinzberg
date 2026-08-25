/* artzig stub. */
package android.window;
public class TaskSnapshot implements android.os.Parcelable {
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final Creator<TaskSnapshot> CREATOR = new Creator<TaskSnapshot>() {
        public TaskSnapshot createFromParcel(android.os.Parcel in) { return new TaskSnapshot(); }
        public TaskSnapshot[] newArray(int size) { return new TaskSnapshot[size]; }
    };
}
