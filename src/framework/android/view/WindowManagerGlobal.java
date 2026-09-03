/* artzig: hand-written WindowManagerGlobal (ATL pattern) — single-window registry,
 * no WindowManagerService. */
package android.view;
import java.util.ArrayList;
public final class WindowManagerGlobal {
    private static WindowManagerGlobal sInstance;
    private final ArrayList<ViewRootImpl> mRoots = new ArrayList<>();
    private WindowManagerGlobal() {}
    public static WindowManagerGlobal getInstance() {
        synchronized (WindowManagerGlobal.class) {
            if (sInstance == null) sInstance = new WindowManagerGlobal();
            return sInstance;
        }
    }
    private static final IWindowManager sWindowManager = new IWindowManager() {};
    public static IWindowManager getWindowManagerService() { return sWindowManager; }
    /** The hosted window path never opens a WindowManagerService session: the AIDL Default
     * (every call a no-op) stands in for wallpaper offsets, drags and the other session calls. */
    private static final IWindowSession sWindowSession = new IWindowSession.Default();
    public static IWindowSession getWindowSession() { return sWindowSession; }
    public void addRoot(ViewRootImpl root) { mRoots.add(root); }
    public ArrayList<ViewRootImpl> getRootViews() { return mRoots; }
    public ArrayList<ViewRootImpl> getRootViews(android.os.IBinder token) { return mRoots; }
    public ArrayList<View> getWindowViews() { return new ArrayList<>(); }
    /** android 16: WindowInspector debug listeners; the hosted global tracks no window views. */
    public void addWindowViewsListener(java.util.concurrent.Executor executor,
            java.util.function.Consumer<java.util.List<View>> consumer) {}
    public void removeWindowViewsListener(java.util.function.Consumer<java.util.List<View>> consumer) {}
    public static boolean useBLAST() { return false; }
    public View getWindowView(android.os.IBinder windowToken) { return null; }
}
