/* artzig stub (auto-generated placeholder; members added on demand). */
package android.widget.inline;
public class InlineContentView extends android.view.ViewGroup {
    public InlineContentView(android.content.Context context) { super(context); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public interface SurfacePackageUpdater {
        void onSurfacePackageReleased();
        void getSurfacePackage(java.util.function.Consumer<android.view.SurfaceControlViewHost.SurfacePackage> consumer);
    }
    public void setChildSurfacePackageUpdater(SurfacePackageUpdater updater) {}
}
