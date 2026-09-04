/* artzig stub: only the debug flags the vendored ComponentResolver statically
 * imports; the real service is inseparable from system_server (spike result —
 * the hosted service is dev.artzig.system.HostedPackageManagerService). */
package com.android.server.pm;
public final class PackageManagerService {
    static final String TAG = "PackageManager";
    public static final boolean DEBUG_SETTINGS = false;
    public static final boolean DEBUG_DEXOPT = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    public static final boolean DEBUG_REMOVE = false;
    public static void reportSettingsProblem(int priority, String message) {
        android.util.Slog.println(priority, TAG, message);
    }
    private PackageManagerService() {}
}
