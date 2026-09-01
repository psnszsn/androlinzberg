/* artzig stub: the subset of android.app.ActivityManagerInternal the reused
 * services reference (the AOSP abstract class drags dozens of am types). The
 * host has a single user, so UserManager.supportsMultipleUsers() is false and
 * handleIncomingUser is never reached; no implementation is registered. */
package android.app;
public abstract class ActivityManagerInternal {
    public static final int ALLOW_NON_FULL = 0;
    public static final int ALLOW_NON_FULL_IN_PROFILE = 1;
    public static final int ALLOW_FULL_ONLY = 2;
    public abstract int handleIncomingUser(int callingPid, int callingUid, int userId,
            boolean allowAll, int allowMode, String name, String callerPackage);
    /** android 16: uid process-state query (clipboard service logging). */
    public abstract int getUidProcessState(int uid);
}
