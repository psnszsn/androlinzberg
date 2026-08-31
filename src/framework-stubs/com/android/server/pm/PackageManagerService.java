/* artzig stub: only the debug flags the vendored ComponentResolver statically
 * imports; the real service is inseparable from system_server (spike result —
 * the hosted service is dev.artzig.system.HostedPackageManagerService). */
package com.android.server.pm;
public final class PackageManagerService {
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    public static final boolean DEBUG_REMOVE = false;
    private PackageManagerService() {}
}
