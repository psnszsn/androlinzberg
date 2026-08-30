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
    /** AOSP ResolveInfo.writeToParcel: a component-kind tag, then the component. */
    public void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        if (activityInfo != null) {
            dest.writeInt(1);
            activityInfo.writeToParcel(dest, parcelableFlags);
        } else if (serviceInfo != null) {
            dest.writeInt(2);
            serviceInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(specificIndex);
    }
    protected ResolveInfo(android.os.Parcel source) {
        switch (source.readInt()) {
            case 1: activityInfo = ActivityInfo.CREATOR.createFromParcel(source); break;
            case 2: serviceInfo = ServiceInfo.CREATOR.createFromParcel(source); break;
            default: break;
        }
        specificIndex = source.readInt();
    }
    public ResolveInfo() {}
    public static final android.os.Parcelable.Creator<ResolveInfo> CREATOR =
            new android.os.Parcelable.Creator<ResolveInfo>() {
        public ResolveInfo createFromParcel(android.os.Parcel in) { return new ResolveInfo(in); }
        public ResolveInfo[] newArray(int size) { return new ResolveInfo[size]; }
    };
}
