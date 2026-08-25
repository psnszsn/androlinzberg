/* artzig stub: no media session service on host (media = phase 2). */
package android.media.session;
public final class MediaSessionManager {
    public static final class RemoteUserInfo {
        private final String mPackageName;
        private final int mPid, mUid;
        public RemoteUserInfo(String packageName, int pid, int uid) {
            mPackageName = packageName; mPid = pid; mUid = uid;
        }
        public String getPackageName() { return mPackageName; }
        public int getPid() { return mPid; }
        public int getUid() { return mUid; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RemoteUserInfo)) return false;
            RemoteUserInfo r = (RemoteUserInfo) o;
            return mPid == r.mPid && mUid == r.mUid
                    && java.util.Objects.equals(mPackageName, r.mPackageName);
        }
        @Override public int hashCode() { return java.util.Objects.hash(mPackageName, mPid, mUid); }
    }
    public boolean isTrustedForMediaControl(RemoteUserInfo userInfo) { return true; }
    public interface OnMediaKeyEventDispatchedListener {}
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent) {}
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent, boolean needWakeLock) {}
    public void dispatchVolumeKeyEvent(android.view.KeyEvent keyEvent, int stream, boolean musicOnly) {}
    public void dispatchAdjustVolume(int suggestedStream, int direction, int flags) {}
    public void dispatchVolumeKeyEventToSessionAsSystemService(android.view.KeyEvent keyEvent, Object sessionToken) {}
    public void dispatchVolumeKeyEventAsSystemService(android.view.KeyEvent keyEvent, int streamType) {}
    public boolean dispatchMediaKeyEventToSessionAsSystemService(android.view.KeyEvent keyEvent, Object sessionToken) { return false; }
    public boolean dispatchMediaKeyEventAsSystemService(android.view.KeyEvent keyEvent) { return false; }
}
