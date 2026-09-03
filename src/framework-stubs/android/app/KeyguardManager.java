/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class KeyguardManager {
    /** AOSP KeyguardDismissCallback: no keyguard on the host, a dismiss request succeeds at once. */
    public static abstract class KeyguardDismissCallback {
        public void onDismissError() {}
        public void onDismissSucceeded() {}
        public void onDismissCancelled() {}
    }
    public void requestDismissKeyguard(Activity activity, KeyguardDismissCallback callback) {
        if (callback != null) callback.onDismissSucceeded();
    }
    public void requestDismissKeyguard(Activity activity, CharSequence message, KeyguardDismissCallback callback) {
        requestDismissKeyguard(activity, callback);
    }

    public boolean inKeyguardRestrictedInputMode() { return false; }
    public boolean isKeyguardLocked() { return false; }
    public boolean isKeyguardSecure() { return false; }
    public boolean isDeviceLocked(int userId) { return false; }
    public boolean isDeviceLocked(int userId, int deviceId) { return false; }
    public boolean isDeviceLocked() { return false; }
}
