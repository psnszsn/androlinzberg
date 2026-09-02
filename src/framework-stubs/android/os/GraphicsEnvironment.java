/* artzig stub of AOSP android.os.GraphicsEnvironment: the real class drives
 * the updatable-driver/ANGLE natives (libgraphicsenv) and the GameManager
 * service; this host has neither. Layer paths and driver setup are no-ops. */
package android.os;
public class GraphicsEnvironment {
    private static final GraphicsEnvironment sInstance = new GraphicsEnvironment();
    public static GraphicsEnvironment getInstance() { return sInstance; }
    public void setup(android.content.Context context, Bundle coreSettings) {}
    public void setLayerPaths(ClassLoader classLoader, String searchPaths, String permittedPaths) {}
    public String getDebugLayerPathsFromSettings(Bundle coreSettings, android.content.pm.IPackageManager pm, String packageName, android.content.pm.ApplicationInfo ai) { return null; }
    public void hintActivityLaunch(android.content.Context context) {}
    public void toggleAngleAsSystemDriver(boolean enabled) {}
    public void showAngleInUseDialogBox(android.content.Context context) {}
}
