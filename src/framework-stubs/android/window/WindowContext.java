/* artzig stub of AOSP android.window.WindowContext: a window context whose token
 * WindowManagerService would attach to a display area. No WMS here: the base
 * context (ContextImpl.createWindowContextBase) already carries the token, so
 * attach/destroy are local no-ops and the type/options are remembered. */
package android.window;
public class WindowContext extends android.content.ContextWrapper {
    private final int mType;
    private final android.os.Bundle mOptions;
    public WindowContext(android.content.Context base, int type, android.os.Bundle options) {
        super(base);
        mType = type;
        mOptions = options;
    }
    public void attachToDisplayArea() {}
    public void destroy() {}
    public int getWindowType() { return mType; }
    public android.os.Bundle getWindowContextOptions() { return mOptions; }
    @Override public android.os.IBinder getWindowContextToken() { return getBaseContext().getWindowContextToken(); }
}
