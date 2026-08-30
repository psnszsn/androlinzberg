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
    /** AOSP PackageInfo.writeToParcel order for the fields this shim has. */
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString8(packageName);
        dest.writeStringArray(splitNames);
        dest.writeInt(versionCode);
        dest.writeString8(versionName);
        dest.writeString8(sharedUserId);
        dest.writeInt(sharedUserLabel);
        if (applicationInfo != null) {
            dest.writeInt(1);
            applicationInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeLong(firstInstallTime);
        dest.writeLong(lastUpdateTime);
        dest.writeIntArray(gids);
        dest.writeTypedArray(activities, parcelableFlags);
        dest.writeTypedArray(receivers, parcelableFlags);
        dest.writeTypedArray(services, parcelableFlags);
        dest.writeTypedArray(providers, parcelableFlags);
        dest.writeStringArray(requestedPermissions);
        dest.writeIntArray(requestedPermissionsFlags);
    }
    protected PackageInfo(Parcel source) {
        packageName = source.readString8();
        splitNames = source.createStringArray();
        versionCode = source.readInt();
        versionName = source.readString8();
        sharedUserId = source.readString8();
        sharedUserLabel = source.readInt();
        if (source.readInt() != 0) applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);
        firstInstallTime = source.readLong();
        lastUpdateTime = source.readLong();
        gids = source.createIntArray();
        activities = source.createTypedArray(ActivityInfo.CREATOR);
        receivers = source.createTypedArray(ActivityInfo.CREATOR);
        services = source.createTypedArray(ServiceInfo.CREATOR);
        providers = source.createTypedArray(ProviderInfo.CREATOR);
        requestedPermissions = source.createStringArray();
        requestedPermissionsFlags = source.createIntArray();
    }
    public static final Parcelable.Creator<PackageInfo> CREATOR = new Parcelable.Creator<PackageInfo>() {
        public PackageInfo createFromParcel(Parcel in) { return new PackageInfo(in); }
        public PackageInfo[] newArray(int size) { return new PackageInfo[size]; }
    };
}
