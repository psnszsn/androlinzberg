/* artzig stub of AOSP android.window.WindowTokenClientController: attaches window
 * tokens to display areas through WindowManagerService. Without a WMS every
 * attach succeeds locally (the token stays a plain Binder) and detach is a no-op. */
package android.window;
public class WindowTokenClientController {
    private static final WindowTokenClientController sInstance = new WindowTokenClientController();
    public static WindowTokenClientController getInstance() { return sInstance; }
    public boolean attachToDisplayArea(WindowTokenClient client, int type, int displayId, android.os.Bundle options) { return true; }
    public boolean attachToDisplayContent(WindowTokenClient client, int displayId) { return true; }
    public void attachToWindowToken(WindowTokenClient client, android.os.IBinder windowToken) {}
    public void detachIfNeeded(WindowTokenClient client) {}
    public void onWindowContextInfoChanged(android.os.IBinder clientToken, android.window.WindowContextInfo info) {}
    public void onWindowContextWindowRemoved(android.os.IBinder clientToken) {}
}
