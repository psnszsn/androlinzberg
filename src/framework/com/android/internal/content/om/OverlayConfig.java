/* artzig stub: no RRO overlays on host. */
package com.android.internal.content.om;
public class OverlayConfig {
    private static final OverlayConfig sInstance = new OverlayConfig();
    public static OverlayConfig getZygoteInstance() { return sInstance; }
    public static OverlayConfig getSystemInstance() { return sInstance; }
    public String[] createImmutableFrameworkIdmapsInZygote() { return new String[0]; }
    public boolean isEnabled(String packageName) { return false; }
    /** AOSP nested provider interface; PackageImpl implements Package. */
    public interface PackageProvider {
        void forEachPackage(com.android.internal.util.function.TriConsumer<Package, Boolean, java.io.File> p);

        interface Package {
            String getBaseApkPath();
            int getOverlayPriority();
            String getOverlayTarget();
            String getPackageName();
            int getTargetSdkVersion();
            boolean isOverlayIsStatic();
        }
    }
}
