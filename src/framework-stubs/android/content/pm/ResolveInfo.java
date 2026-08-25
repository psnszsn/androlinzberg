/* artzig stub. */
package android.content.pm;
public class ResolveInfo implements android.os.Parcelable {
    public ActivityInfo activityInfo;
    public ServiceInfo serviceInfo;
    public int specificIndex = -1;
    public CharSequence loadLabel(PackageManager pm) { return ""; }
    public android.graphics.drawable.Drawable loadIcon(PackageManager pm) { return null; }
    public void dump(android.util.Printer pw, String prefix) {}
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<ResolveInfo> CREATOR =
            new android.os.Parcelable.Creator<ResolveInfo>() {
        public ResolveInfo createFromParcel(android.os.Parcel in) { return new ResolveInfo(); }
        public ResolveInfo[] newArray(int size) { return new ResolveInfo[size]; }
    };
}
