package dev.artzig.system;

import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import dev.artzig.system.HostedInstalledPackage;

/**
 * The session-wide package manager (the subset of android.content.pm
 * IPackageManager this host needs; the real interface has hundreds of methods
 * plus ParceledListSlice and the installer session closure).
 */
interface IHostedPackageManager {
    /** Registers a package parsed by the calling process; idempotent per package. */
    void registerPackage(in HostedInstalledPackage pkg);
    /** Parses and registers an APK in the system process; used for respawning. */
    HostedInstalledPackage installPackage(String apkPath);
    HostedInstalledPackage getPackage(String packageName);
    List<ResolveInfo> queryIntentServices(in Intent intent, int flags);
    List<ResolveInfo> queryIntentActivities(in Intent intent, int flags);
    int checkPermission(String permission, String packageName);
    int checkUidPermission(String permission, int uid);
    String[] getPackagesForUid(int uid);
    int getPackageUid(String packageName);
    String getApkPath(String packageName);
    String[] getInstalledPackageNames();
    /** Runtime grant state (AOSP PermissionManagerService.grantRuntimePermission
     * shape, reduced); callable only by the system uid. */
    void grantRuntimePermission(String packageName, String permission);
    void revokeRuntimePermission(String packageName, String permission);
    /** Session-wide authority resolution (AOSP resolveContentProvider). */
    ProviderInfo resolveContentProvider(String authority, int flags);
}
