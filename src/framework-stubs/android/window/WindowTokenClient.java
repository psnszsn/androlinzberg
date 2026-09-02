/* artzig stub of AOSP android.window.WindowTokenClient: the window-token client
 * that WindowManagerService drives with configuration updates. No WMS here; a
 * window context's token is a plain Binder and its context is remembered only. */
package android.window;
public class WindowTokenClient extends android.os.Binder {
    private java.lang.ref.WeakReference<android.content.Context> mContextRef;
    public WindowTokenClient() {}
    public void attachContext(android.content.Context context) {
        mContextRef = new java.lang.ref.WeakReference<>(context);
    }
    public android.content.Context getContext() { return mContextRef != null ? mContextRef.get() : null; }
    public void onConfigurationChanged(android.content.res.Configuration newConfig, int newDisplayId) {}
    public void onWindowTokenRemoved() {}
}
