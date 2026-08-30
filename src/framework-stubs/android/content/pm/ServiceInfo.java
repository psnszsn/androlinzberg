/* artzig stub: AOSP ServiceInfo.writeToParcel order for the fields this shim has. */
package android.content.pm;
public class ServiceInfo extends ComponentInfo implements android.os.Parcelable {
    public String permission;
    public int flags;
    public int foregroundServiceType;
    public ServiceInfo() {}
    public ServiceInfo(ServiceInfo orig) {
        super(orig);
        permission = orig.permission;
        flags = orig.flags;
        foregroundServiceType = orig.foregroundServiceType;
    }
    public void dump(android.util.Printer pw, String prefix) {}
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString8(permission);
        dest.writeInt(flags);
        dest.writeInt(foregroundServiceType);
    }
    protected ServiceInfo(android.os.Parcel source) {
        super(source);
        permission = source.readString8();
        flags = source.readInt();
        foregroundServiceType = source.readInt();
    }
    public static final android.os.Parcelable.Creator<ServiceInfo> CREATOR =
            new android.os.Parcelable.Creator<ServiceInfo>() {
        public ServiceInfo createFromParcel(android.os.Parcel in) { return new ServiceInfo(in); }
        public ServiceInfo[] newArray(int size) { return new ServiceInfo[size]; }
    };
}
