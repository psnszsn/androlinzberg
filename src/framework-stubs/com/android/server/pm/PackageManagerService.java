/* artzig stub: only the debug flags the vendored ComponentResolver statically
 * imports; the real service is inseparable from system_server (spike result —
 * the hosted service is dev.artzig.system.HostedPackageManagerService). */
package com.android.server.pm;
public final class PackageManagerService {
    public static final String PLATFORM_PACKAGE_NAME = "android";
    // Pinned PMS URI scheme consumed by the stock package-monitor dispatcher.
    static final String PACKAGE_SCHEME = "package";
    static final String TAG = "PackageManager";
    // Pinned PMS constants used by intent matching and activity interception.
    static final boolean DEBUG_INTENT_MATCHING = false;
    public static final String PLATFORM_PACKAGE_NAME = "android";
    public static final boolean DEBUG_SETTINGS = false;
    public static final boolean DEBUG_DEXOPT = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    public static final boolean DEBUG_REMOVE = false;
    public static void reportSettingsProblem(int priority, String message) {
        android.util.Slog.println(priority, TAG, message);
    }
    private PackageManagerService() {}
}
