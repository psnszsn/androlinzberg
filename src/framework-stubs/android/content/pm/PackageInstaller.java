/* artzig stub: installer sessions are not hosted; only the constants and
 * annotation InstallSourceInfo references. */
package android.content.pm;
public class PackageInstaller {
    public static final int PACKAGE_SOURCE_UNSPECIFIED = 0;
    public static final int PACKAGE_SOURCE_OTHER = 1;
    public static final int PACKAGE_SOURCE_STORE = 2;
    public static final int PACKAGE_SOURCE_LOCAL_FILE = 3;
    public static final int PACKAGE_SOURCE_DOWNLOADED_FILE = 4;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PackageSourceType {}
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    /** Referenced by PackageManager javadoc and status mapping only. */
    public static class SessionParams {
        private SessionParams() {}
    }
    /** AOSP SessionCallback: install-session progress for launchers; no session ever exists here. */
    public static abstract class SessionCallback {
        public abstract void onCreated(int sessionId);
        public abstract void onBadgingChanged(int sessionId);
        public abstract void onActiveChanged(int sessionId, boolean active);
        public abstract void onProgressChanged(int sessionId, float progress);
        public abstract void onFinished(int sessionId, boolean success);
    }
    /** AOSP SessionCallbackDelegate: the binder that forwards IPackageInstallerCallback to a SessionCallback. */
    public static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub {
        final SessionCallback mCallback;
        final java.util.concurrent.Executor mExecutor;
        public SessionCallbackDelegate(SessionCallback callback, java.util.concurrent.Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }
        @Override public void onSessionCreated(int sessionId) { mExecutor.execute(() -> mCallback.onCreated(sessionId)); }
        @Override public void onSessionBadgingChanged(int sessionId) { mExecutor.execute(() -> mCallback.onBadgingChanged(sessionId)); }
        @Override public void onSessionActiveChanged(int sessionId, boolean active) { mExecutor.execute(() -> mCallback.onActiveChanged(sessionId, active)); }
        @Override public void onSessionProgressChanged(int sessionId, float progress) { mExecutor.execute(() -> mCallback.onProgressChanged(sessionId, progress)); }
        @Override public void onSessionFinished(int sessionId, boolean success) { mExecutor.execute(() -> mCallback.onFinished(sessionId, success)); }
    }
    /** No install session ever reports; the callbacks are accepted and never invoked. */
    public void registerSessionCallback(SessionCallback callback) {}
    public void registerSessionCallback(SessionCallback callback, android.os.Handler handler) {}
    public void unregisterSessionCallback(SessionCallback callback) {}
    /** ILauncherApps parcels session details; no install session ever exists here. */
    public static class SessionInfo implements android.os.Parcelable {
        public int sessionId;
        public String installerPackageName;
        public String appPackageName;
        public SessionInfo() {}
        public int getSessionId() { return sessionId; }
        public String getInstallerPackageName() { return installerPackageName; }
        public String getAppPackageName() { return appPackageName; }
        public android.os.UserHandle getUser() { return android.os.UserHandle.SYSTEM; }
        public int getUserId() { return android.os.UserHandle.USER_SYSTEM; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeString(installerPackageName);
            dest.writeString(appPackageName);
        }
        public static final Creator<SessionInfo> CREATOR = new Creator<SessionInfo>() {
            @Override public SessionInfo createFromParcel(android.os.Parcel in) {
                SessionInfo info = new SessionInfo();
                info.sessionId = in.readInt();
                info.installerPackageName = in.readString();
                info.appPackageName = in.readString();
                return info;
            }
            @Override public SessionInfo[] newArray(int size) { return new SessionInfo[size]; }
        };
    }
    private PackageInstaller() {}
    /** ApplicationPackageManager constructs this; sessions are not hosted, so
     * every session API is absent and callers fail at the missing methods. */
    public PackageInstaller(IPackageInstaller installer, String installerPackageName,
            String installerAttributionTag, int userId) {}
}
