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
    public static final String USER_TYPE_FULL_SECONDARY = "android.os.usertype.full.SECONDARY";
    public static final String USER_TYPE_FULL_GUEST = "android.os.usertype.full.GUEST";
    public static final String USER_TYPE_FULL_DEMO = "android.os.usertype.full.DEMO";
    public static final String USER_TYPE_FULL_RESTRICTED = "android.os.usertype.full.RESTRICTED";
    public static final String USER_TYPE_SYSTEM_HEADLESS = "android.os.usertype.system.HEADLESS";
    public static final String USER_TYPE_PROFILE_CLONE = "android.os.usertype.profile.CLONE";
    public static final String USER_TYPE_PROFILE_PRIVATE = "android.os.usertype.profile.PRIVATE";
    public static final String USER_TYPE_PROFILE_COMMUNAL = "android.os.usertype.profile.COMMUNAL";
    public static final String USER_TYPE_PROFILE_SUPERVISING = "android.os.usertype.profile.SUPERVISING";
    public static final String DISALLOW_MODIFY_ACCOUNTS = "no_modify_accounts";
    public static final int USER_OPERATION_SUCCESS = 1;
    public static final int USER_OPERATION_ERROR_UNKNOWN = 2;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface UserOperationResult {}
    public static boolean isUserTypeDemo(String userType) { return USER_TYPE_FULL_DEMO.equals(userType); }
    public static boolean isUserTypeRestricted(String userType) { return USER_TYPE_FULL_RESTRICTED.equals(userType); }
    public static boolean isUserTypeCloneProfile(String userType) { return USER_TYPE_PROFILE_CLONE.equals(userType); }
    public static boolean isUserTypePrivateProfile(String userType) { return USER_TYPE_PROFILE_PRIVATE.equals(userType); }
    public static boolean isUserTypeCommunalProfile(String userType) { return USER_TYPE_PROFILE_COMMUNAL.equals(userType); }
    public static boolean isUserTypeSupervisingProfile(String userType) { return USER_TYPE_PROFILE_SUPERVISING.equals(userType); }
    public boolean isPrivateProfile() { return false; }
    public static boolean isDeviceInDemoMode(android.content.Context context) { return false; }
    public Bundle getUserRestrictions(UserHandle userHandle) { return new Bundle(); }
    public List<UserHandle> getUserProfiles() { return getUserHandles(true); }
    /** The one hosted user's record, null for any other id. */
    public UserInfo getUserInfo(int userId) {
        for (UserInfo user : getUsers()) if (user.id == userId) return user;
        return null;
    }
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
