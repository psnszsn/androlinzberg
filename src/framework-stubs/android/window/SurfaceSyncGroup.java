/* artzig stub: surface sync — no-op on host. */
package android.window;
public final class SurfaceSyncGroup {
    public SurfaceSyncGroup(String name) {}
    public boolean add(Object part, Runnable runnable) { if (runnable != null) runnable.run(); return true; }
    public boolean addSyncCompleteCallback(java.util.concurrent.Executor executor, Runnable runnable) {
        if (runnable != null) executor.execute(runnable);
        return true;
    }
    public void markSyncReady() {}
    public void addTransaction(android.view.SurfaceControl.Transaction t) {}
    public void toggleTimeout(boolean enable) {}
}
