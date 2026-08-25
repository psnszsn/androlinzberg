/* artzig: hand-written WindowManagerImpl (ATL pattern) — single Wayland-backed window. */
package android.view;
import android.content.Context;
public final class WindowManagerImpl implements WindowManager {
    private final Context mContext;
    public WindowManagerImpl(Context context) { mContext = context; }
    @Override public void addView(View view, ViewGroup.LayoutParams params) {
        ArtzigWindowStack.add(mContext, view, params);
    }
    @Override public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        ArtzigWindowStack.update(view, params);
    }
    @Override public void removeView(View view) { ArtzigWindowStack.remove(view); }
    @Override public void removeViewImmediate(View view) { ArtzigWindowStack.remove(view); }
    @Override public Display getDefaultDisplay() { return ArtzigDisplay.get(); }
    public void setDefaultToken(android.os.IBinder token) {}
    public WindowManagerImpl createLocalWindowManager(Window parentWindow) { return this; }
    public WindowManagerImpl createPresentationWindowManager(android.content.Context displayContext) { return this; }
    @Override public android.graphics.Region getCurrentImeTouchRegion() { return new android.graphics.Region(); }
    @Override public WindowMetrics getCurrentWindowMetrics() {
        return new WindowMetrics(new android.graphics.Rect(0, 0, 1280, 720),
                android.view.WindowInsets.CONSUMED, 1.0f);
    }
    @Override public WindowMetrics getMaximumWindowMetrics() { return getCurrentWindowMetrics(); }
    @Override public void requestAppKeyboardShortcuts(KeyboardShortcutsReceiver receiver, int deviceId) {}
    @Override public void requestImeKeyboardShortcuts(KeyboardShortcutsReceiver receiver, int deviceId) {}
    @Override public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        return new KeyboardShortcutGroup("", new java.util.ArrayList<>());
    }
}
