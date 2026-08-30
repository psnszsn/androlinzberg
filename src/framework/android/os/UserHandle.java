/* artzig shim: android.os.UserHandle. */
package android.os;
public final class UserHandle implements Parcelable {
    public static final int USER_OWNER = 0, USER_SYSTEM = 0, USER_ALL = -1, USER_CURRENT = -2, USER_CURRENT_OR_SELF = -3, USER_NULL = -10000;
    public static final UserHandle OWNER = new UserHandle(0);
    public static final UserHandle SYSTEM = OWNER;
    public static final UserHandle ALL = new UserHandle(USER_ALL);
    public static final UserHandle CURRENT = new UserHandle(USER_CURRENT);
    public static final UserHandle CURRENT_OR_SELF = new UserHandle(USER_CURRENT_OR_SELF);
    private final int mHandle;
    public UserHandle(int h) { mHandle = h; }
    public static UserHandle of(int userId) { return userId == 0 ? SYSTEM : new UserHandle(userId); }
    public int getIdentifier() { return mHandle; }
    public static int myUserId() { return 0; }
    public static int getCallingUserId() { return 0; }
    public static int getUserId(int uid) { return uid / 100000; }
    public static int getAppId(int uid) { return uid % 100000; }
    public static int getUid(int userId, int appId) { return userId * 100000 + appId; }
    public static boolean isSameApp(int uid1, int uid2) { return getAppId(uid1) == getAppId(uid2); }
    public static void writeToParcel(UserHandle h, Parcel out) { out.writeInt(h != null ? h.mHandle : USER_NULL); }
    public static UserHandle readFromParcel(Parcel in) {
        int h = in.readInt();
        return h != USER_NULL ? new UserHandle(h) : null;
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeInt(mHandle); }
    @Override public boolean equals(Object o) { return o instanceof UserHandle && ((UserHandle) o).mHandle == mHandle; }
    @Override public int hashCode() { return mHandle; }
    public static final Creator<UserHandle> CREATOR = new Creator<UserHandle>() {
        public UserHandle createFromParcel(Parcel in) { return new UserHandle(in.readInt()); }
        public UserHandle[] newArray(int size) { return new UserHandle[size]; }
    };
}
