/* artzig stub: android.content.pm.UserInfo for the single hosted user. The AOSP class depends on
 * UserManager's user-type tables, which this host does not model. */
package android.content.pm;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
public class UserInfo implements Parcelable {
    public static final int FLAG_PRIMARY = 0x00000001;
    public static final int FLAG_ADMIN = 0x00000002;
    public static final int FLAG_FULL = 0x00000400;
    public static final int FLAG_SYSTEM = 0x00000800;
    public static final int FLAG_PROFILE = 0x00001000;
    public static final int FLAG_MAIN = 0x00004000;
    public static final int NO_PROFILE_GROUP_ID = UserHandle.USER_NULL;
    public int id;
    public int serialNumber;
    public String name;
    public String iconPath;
    public int flags;
    public long creationTime;
    public long lastLoggedInTime;
    public String lastLoggedInFingerprint;
    public String userType;
    public int profileGroupId = NO_PROFILE_GROUP_ID;
    public int restrictedProfileParentId = UserHandle.USER_NULL;
    public int profileBadge;
    public boolean partial;
    public boolean guestToRemove;
    public boolean preCreated;
    public boolean convertedFromPreCreated;
    public UserInfo(int id, String name, int flags) { this(id, name, null, flags, null); }
    public UserInfo(int id, String name, String iconPath, int flags) { this(id, name, iconPath, flags, null); }
    public UserInfo(int id, String name, String iconPath, int flags, String userType) {
        this.id = id; this.name = name; this.flags = flags; this.iconPath = iconPath; this.userType = userType;
    }
    public UserInfo(UserInfo orig) {
        this(orig.id, orig.name, orig.iconPath, orig.flags, orig.userType);
        serialNumber = orig.serialNumber; creationTime = orig.creationTime;
        lastLoggedInTime = orig.lastLoggedInTime; profileGroupId = orig.profileGroupId;
    }
    public UserHandle getUserHandle() { return UserHandle.of(id); }
    public boolean isPrimary() { return (flags & FLAG_PRIMARY) == FLAG_PRIMARY; }
    public boolean isAdmin() { return (flags & FLAG_ADMIN) == FLAG_ADMIN; }
    public boolean isFull() { return (flags & FLAG_FULL) == FLAG_FULL; }
    public boolean isMain() { return (flags & FLAG_MAIN) == FLAG_MAIN; }
    public boolean isProfile() { return (flags & FLAG_PROFILE) != 0; }
    public boolean isManagedProfile() { return android.os.UserManager.isUserTypeManagedProfile(userType); }
    public boolean isGuest() { return false; }
    public boolean isRestricted() { return false; }
    public boolean isEnabled() { return true; }
    public boolean isEphemeral() { return false; }
    public boolean supportsSwitchTo() { return true; }
    public boolean supportsSwitchToByUser() { return true; }
    @Override public String toString() { return "UserInfo{" + id + ":" + name + ":" + Integer.toHexString(flags) + "}"; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(id); dest.writeString(name); dest.writeString(iconPath); dest.writeInt(flags);
        dest.writeString(userType); dest.writeInt(serialNumber); dest.writeInt(profileGroupId);
    }
    public static final Parcelable.Creator<UserInfo> CREATOR = new Parcelable.Creator<UserInfo>() {
        public UserInfo createFromParcel(Parcel source) {
            UserInfo u = new UserInfo(source.readInt(), source.readString(), source.readString(), source.readInt(), source.readString());
            u.serialNumber = source.readInt(); u.profileGroupId = source.readInt();
            return u;
        }
        public UserInfo[] newArray(int size) { return new UserInfo[size]; }
    };
}
