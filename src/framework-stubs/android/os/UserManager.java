/* artzig stub: single-user host. SystemService/SystemServiceManager use the static user-type
 * helpers and getUsers(); everything else is the pre-existing minimal surface. */
package android.os;
import android.content.Context;
import android.content.pm.UserInfo;
import java.util.ArrayList;
import java.util.List;
public class UserManager {
    /** Single hosted user: no profile parent, default user properties. */
    public UserHandle getProfileParent(UserHandle user) { return null; }
    /** AOSP returns the profile type's accessibility label; the single hosted user has none. */
    public String getProfileAccessibilityString(int userId) { return null; }
    public android.content.pm.UserProperties getUserProperties(UserHandle user) {
        return new android.content.pm.UserProperties.Builder().build();
    }
    // ApplicationPackageManager badging/direct-boot surface: the single
    // hosted user is unlocked and carries no badge.
    public boolean hasBadge(int userId) { return false; }
    public int getUserBadgeResId(int userId) { return 0; }
    public int getUserBadgeNoBackgroundResId(int userId) { return 0; }
    public int getUserIconBadgeResId(int userId) { return 0; }
    public int getUserBadgeColor(int userId) { return 0; }
    public int getUserBadgeDarkColor(int userId) { return 0; }
    public boolean isUserUnlockingOrUnlocked(int userId) { return true; }
    public CharSequence getBadgedLabelForUser(CharSequence label, UserHandle user) { return label; }
    public static final String USER_TYPE_FULL_SYSTEM = "android.os.usertype.full.SYSTEM";
    public static final String USER_TYPE_PROFILE_MANAGED = "android.os.usertype.profile.MANAGED";
    public static final String DISALLOW_CROSS_PROFILE_COPY_PASTE = "no_cross_profile_copy_paste";
    public static final String DISALLOW_SHARE_INTO_MANAGED_PROFILE = "no_sharing_into_profile";
    private static final UserManager sInstance = new UserManager();
    public static UserManager get(Context context) { return sInstance; }
    public boolean isUserUnlocked() { return true; }
    public boolean isVisibleBackgroundUsersSupported() { return false; }
    public int getMainDisplayIdAssignedToUser() { return 0; }
    public boolean isManagedProfile() { return false; }
    public boolean isManagedProfile(int userId) { return false; }
    public static boolean supportsMultipleUsers() { return false; }
    public static boolean isUserTypeManagedProfile(String userType) { return USER_TYPE_PROFILE_MANAGED.equals(userType); }
    public static boolean isUserTypeGuest(String userType) { return false; }
    /** The one hosted user: USER_SYSTEM, full, admin, main. */
    public List<UserInfo> getUsers() {
        List<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(UserHandle.USER_SYSTEM, "host", null,
                UserInfo.FLAG_PRIMARY | UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL | UserInfo.FLAG_SYSTEM | UserInfo.FLAG_MAIN,
                USER_TYPE_FULL_SYSTEM));
        return users;
    }
    public List<UserHandle> getUserHandles(boolean excludeDying) {
        List<UserHandle> handles = new ArrayList<>();
        for (UserInfo user : getUsers()) handles.add(UserHandle.of(user.id));
        return handles;
    }
    /** Nested type of the IUserManager surface. */
    public static class EnforcingUser implements Parcelable {
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Creator<EnforcingUser> CREATOR = new Creator<EnforcingUser>() {
            @Override public EnforcingUser createFromParcel(Parcel source) { return new EnforcingUser(); }
            @Override public EnforcingUser[] newArray(int size) { return new EnforcingUser[size]; }
        };
    }
}
