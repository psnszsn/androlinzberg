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
    public static Object getWindowSession() { return null; }
    public void addRoot(ViewRootImpl root) { mRoots.add(root); }
    public ArrayList<ViewRootImpl> getRootViews() { return mRoots; }
    public ArrayList<ViewRootImpl> getRootViews(android.os.IBinder token) { return mRoots; }
    public ArrayList<View> getWindowViews() { return new ArrayList<>(); }
    public static boolean useBLAST() { return false; }
    public View getWindowView(android.os.IBinder windowToken) { return null; }
}
