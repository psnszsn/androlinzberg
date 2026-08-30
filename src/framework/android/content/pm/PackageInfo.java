/* artzig shim */
package android.content.pm;
import android.os.Parcel;
import android.os.Parcelable;
public class PackageInfo implements Parcelable {
    public static final int REQUESTED_PERMISSION_REQUIRED = 0x00000001;
    public static final int REQUESTED_PERMISSION_GRANTED = 0x00000002;
    public String packageName;
    public String[] splitNames;
    public int versionCode;
    public String versionName;
    public long firstInstallTime;
    public long lastUpdateTime;
    public int[] gids;
    public ActivityInfo[] activities;
    public ActivityInfo[] receivers;
    public ServiceInfo[] services;
    public ProviderInfo[] providers;
    public String[] requestedPermissions;
    public int[] requestedPermissionsFlags;
    public ApplicationInfo applicationInfo;
    public SigningInfo signingInfo;
    public android.content.pm.Signature[] signatures;
    public String sharedUserId;
    public int sharedUserLabel;
    public PackageInfo() {}
    public long getLongVersionCode() { return versionCode; }
    public void setLongVersionCode(long longVersionCode) { versionCode = (int) longVersionCode; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) { dest.writeString(packageName); }
    public static final Parcelable.Creator<PackageInfo> CREATOR = new Parcelable.Creator<PackageInfo>() {
        public PackageInfo createFromParcel(Parcel in) {
            PackageInfo p = new PackageInfo();
            p.packageName = in.readString();
            return p;
        }
        public PackageInfo[] newArray(int size) { return new PackageInfo[size]; }
    };
}
