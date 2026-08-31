/* artzig shim: android.app.ResourcesManager — single-process singleton subset. */
package android.app;
import android.content.res.Configuration;
import android.content.res.Resources;
public class ResourcesManager {
    private static ResourcesManager sInstance;
    private final Configuration mConfig = new Configuration();
    public static ResourcesManager getInstance() {
        synchronized (ResourcesManager.class) {
            if (sInstance == null) sInstance = new ResourcesManager();
            return sInstance;
        }
    }
    public Configuration getConfiguration() { return mConfig; }
    public void registerAllResourcesReference(Resources resources) {}
    public static void registerResourcePaths(String uniqueId, android.content.pm.ApplicationInfo appInfo) {}
    public android.content.res.ApkAssets loadApkAssets(ApkKey key) throws java.io.IOException {
        return android.content.res.ApkAssets.loadFromPath(key.path);
    }
    public android.util.Pair<android.content.res.AssetManager, Integer>
            updateResourceImplAssetsWithRegisteredLibs(
                    android.content.res.AssetManager assets, boolean rebase) {
        return android.util.Pair.create(assets, 0);
    }
    public android.app.LocaleConfig getLocaleConfig() { return null; }
    public boolean isSameResourcesOverrideConfig(android.os.IBinder token, Configuration config) { return true; }
    public static final class ApkKey {
        public final String path;
        public final boolean sharedLib, overlay;
        public ApkKey(String path, boolean sharedLib, boolean overlay) {
            this.path = path; this.sharedLib = sharedLib; this.overlay = overlay;
        }
        @Override public int hashCode() { return path.hashCode(); }
        @Override public boolean equals(Object o) {
            return o instanceof ApkKey && ((ApkKey) o).path.equals(path)
                    && ((ApkKey) o).sharedLib == sharedLib && ((ApkKey) o).overlay == overlay;
        }
        @Override public String toString() { return path; }
    }
    /** AOSP overload used by ParsingPackageUtils' overlayable-resource path
     * (not taken on this host). */
    public android.content.res.Resources getResources(
            android.os.IBinder activityToken,
            String resDir,
            String[] splitResDirs,
            String[] legacyOverlayDirs,
            String[] overlayPaths,
            String[] libDirs,
            Integer overrideDisplayId,
            android.content.res.Configuration overrideConfig,
            android.content.res.CompatibilityInfo compatInfo,
            ClassLoader classLoader,
            java.util.List<android.content.res.loader.ResourcesLoader> loaders) {
        return null;
    }
}
