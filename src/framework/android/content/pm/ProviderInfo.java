/* artzig shim */
package android.content.pm;
import android.os.Parcel;
import android.os.Parcelable;
public final class ProviderInfo extends ComponentInfo implements Parcelable {
    public String authority;
    public String readPermission;
    public String writePermission;
    public boolean grantUriPermissions;
    public boolean forceUriPermissions;
    public PathPermission[] pathPermissions;
    public boolean multiprocess;
    public int initOrder;
    public boolean isSyncable;
    public ProviderInfo() {}
    public ProviderInfo(ProviderInfo orig) {
        super(orig);
        authority = orig.authority;
        readPermission = orig.readPermission;
        writePermission = orig.writePermission;
        grantUriPermissions = orig.grantUriPermissions;
        forceUriPermissions = orig.forceUriPermissions;
        pathPermissions = orig.pathPermissions;
        multiprocess = orig.multiprocess;
        initOrder = orig.initOrder;
        isSyncable = orig.isSyncable;
    }
    public int describeContents() { return 0; }
    /** AOSP ProviderInfo.writeToParcel order for the fields this shim has. */
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString8(authority);
        dest.writeString8(readPermission);
        dest.writeString8(writePermission);
        dest.writeInt(grantUriPermissions ? 1 : 0);
        dest.writeInt(forceUriPermissions ? 1 : 0);
        dest.writeInt(0); // uriPermissionPatterns
        dest.writeInt(pathPermissions != null ? 0 : 0); // pathPermissions (not Parcelable here)
        dest.writeInt(multiprocess ? 1 : 0);
        dest.writeInt(initOrder);
        dest.writeInt(0); // flags
        dest.writeInt(isSyncable ? 1 : 0);
    }
    protected ProviderInfo(Parcel source) {
        super(source);
        authority = source.readString8();
        readPermission = source.readString8();
        writePermission = source.readString8();
        grantUriPermissions = source.readInt() != 0;
        forceUriPermissions = source.readInt() != 0;
        source.readInt();
        source.readInt();
        multiprocess = source.readInt() != 0;
        initOrder = source.readInt();
        source.readInt();
        isSyncable = source.readInt() != 0;
    }
    public static final Parcelable.Creator<ProviderInfo> CREATOR = new Parcelable.Creator<ProviderInfo>() {
        public ProviderInfo createFromParcel(Parcel in) { return new ProviderInfo(in); }
        public ProviderInfo[] newArray(int size) { return new ProviderInfo[size]; }
    };
}
