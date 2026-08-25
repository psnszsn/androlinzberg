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
        authority = orig.authority;
        readPermission = orig.readPermission;
        writePermission = orig.writePermission;
        grantUriPermissions = orig.grantUriPermissions;
        multiprocess = orig.multiprocess;
        initOrder = orig.initOrder;
        isSyncable = orig.isSyncable;
    }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) { dest.writeString(authority); }
    public static final Parcelable.Creator<ProviderInfo> CREATOR = new Parcelable.Creator<ProviderInfo>() {
        public ProviderInfo createFromParcel(Parcel in) {
            ProviderInfo p = new ProviderInfo();
            p.authority = in.readString();
            return p;
        }
        public ProviderInfo[] newArray(int size) { return new ProviderInfo[size]; }
    };
}
