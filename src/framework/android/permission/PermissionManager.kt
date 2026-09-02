package android.permission

import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.os.RemoteException
import android.os.UserHandle

/**
 * Hosted adaptation of AOSP android.permission.PermissionManager (reduced —
 * the android.permission package is not vendored, and the hosted session has
 * no PermissionManagerService binder). Permission state lives in the hosted
 * package manager: checks go through `IPackageManager`, and permission
 * definitions are read from the platform package's `PackageInfo.permissions`
 * (real ParsedPermission data via `PackageInfoUtils`). Restricted-permission
 * allowlists, permission flags, and policy revocation do not exist here and
 * answer their fixed defaults.
 */
class PermissionManager {
    /** AOSP PermissionManager.SplitPermissionInfo. */
    class SplitPermissionInfo(
        private val splitPermission: String,
        private val newPermissions: List<String>,
        private val targetSdk: Int,
    ) {
        fun getSplitPermission(): String = splitPermission
        fun getNewPermissions(): List<String> = newPermissions
        fun getTargetSdk(): Int = targetSdk
    }

    private fun service(): IPackageManager = android.app.ActivityThread.getPackageManager()


    /** AOSP registers attribution sources with PermissionManagerService so a
     * receiver can trust a chain; the session's broker uid is the identity here,
     * so every source a process presents for itself is registered. */
    fun isRegisteredAttributionSource(source: android.content.AttributionSource?): Boolean = source != null
    fun registerAttributionSource(source: android.content.AttributionSource): android.content.AttributionSource = source

    /** Every installed package's permission definitions — the platform's (from
     * framework-res) and any declared by hosted apps. AOSP serves these from
     * PermissionManagerService's registry; the hosted service records them per
     * package, so this aggregates each package's GET_PERMISSIONS view. */
    private fun allPermissions(flags: Int): List<PermissionInfo> {
        val svc = service()
        val flagsLong = android.content.pm.PackageManager.GET_PERMISSIONS.toLong() or flags.toLong()
        val out = ArrayList<PermissionInfo>()
        try {
            for (name in svc.allPackages ?: emptyList()) {
                svc.getPackageInfo(name, flagsLong, 0)?.permissions?.let { out.addAll(it) }
            }
        } catch (e: RemoteException) {
            // Session teardown: return whatever was collected.
        }
        return out
    }

    fun getPermissionInfo(permName: String, flags: Int): PermissionInfo? =
        allPermissions(flags).firstOrNull { it.name == permName }

    fun queryPermissionsByGroup(groupName: String?, flags: Int): List<PermissionInfo>? {
        val matches = allPermissions(flags).filter { it.group == groupName }
        return matches.ifEmpty { null }
    }

    fun getPermissionGroupInfo(groupName: String?, flags: Int): PermissionGroupInfo? = try {
        service().getPermissionGroupInfo(groupName, flags)
    } catch (e: RemoteException) {
        null
    }

    fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> =
        allPermissions(0)
            .mapNotNull { it.group }
            .distinct()
            .mapNotNull {
                try {
                    service().getPermissionGroupInfo(it, flags)
                } catch (e: RemoteException) {
                    null
                }
            }

    fun checkPackageNamePermission(permName: String?, pkgName: String?, deviceId: Int, userId: Int): Int =
        try {
            service().checkPermission(permName, pkgName, userId)
        } catch (e: RemoteException) {
            PackageManager.PERMISSION_DENIED
        }

    fun grantRuntimePermission(packageName: String?, permissionName: String?, user: UserHandle?) {
        try {
            service().grantRuntimePermission(packageName, permissionName, user?.identifier ?: 0)
        } catch (e: RemoteException) {
            throw e.rethrowFromSystemServer()
        }
    }

    fun revokeRuntimePermission(packageName: String?, permName: String?, user: UserHandle?, reason: String?) {
        // The hosted package manager keeps revocation system-only and off the
        // generated surface (AOSP moved it to PermissionManagerService).
        throw SecurityException("runtime permission revocation is not exposed to hosted clients")
    }

    // ----- fixed defaults: no restricted-permission or policy machinery -----

    fun isPermissionRevokedByPolicy(pkgName: String?, permName: String?): Boolean = false
    /** AOSP PermissionManager.getPermissionRequestState: requestable (no policy denies it here). */
    fun getPermissionRequestState(packageName: String?, permissionName: String?, deviceId: Int): Int = 0
    fun addPermission(info: PermissionInfo?, async: Boolean): Boolean = false
    fun removePermission(name: String?) {}
    fun getPermissionFlags(packageName: String?, permName: String?, user: UserHandle?): Int = 0
    fun updatePermissionFlags(
        packageName: String?,
        permName: String?,
        flagMask: Int,
        flagValues: Int,
        user: UserHandle?,
    ) {}
    fun getAllowlistedRestrictedPermissions(packageName: String?, flags: Int): Set<String> = emptySet()
    fun addAllowlistedRestrictedPermission(packageName: String?, permName: String?, flags: Int): Boolean = false
    fun removeAllowlistedRestrictedPermission(packageName: String?, permName: String?, flags: Int): Boolean = false
    fun setAutoRevokeExempted(packageName: String?, exempted: Boolean): Boolean = false
    fun isAutoRevokeExempted(packageName: String?): Boolean = false
    fun shouldShowRequestPermissionRationale(permName: String?): Boolean = false
    fun addOnPermissionsChangeListener(listener: PackageManager.OnPermissionsChangedListener?) {}
    fun removeOnPermissionsChangeListener(listener: PackageManager.OnPermissionsChangedListener?) {}

    companion object {
        /** AOSP PermissionManager.checkPermission (ContextImpl.checkPermission): the uid's grant
         * state from the session package manager (root and system hold every permission). */
        @JvmStatic
        fun checkPermission(permission: String?, pid: Int, uid: Int): Int {
            if (permission == null) return PackageManager.PERMISSION_DENIED
            return try {
                android.app.ActivityThread.getPackageManager().checkUidPermission(permission, uid)
            } catch (e: android.os.RemoteException) {
                PackageManager.PERMISSION_DENIED
            }
        }

        /** AOSP overload with a device id: one default device on this host. */
        @JvmStatic
        fun checkPermission(permission: String?, pid: Int, uid: Int, deviceId: Int): Int =
            checkPermission(permission, pid, uid)

        /** AOSP: the device id a permission check should use (virtual devices are absent). */
        @JvmStatic
        fun resolveDeviceIdForPermissionCheck(context: android.content.Context?, deviceId: Int, permission: String?): Int =
            android.content.Context.DEVICE_ID_DEFAULT

        /** No split-permission table on this host. */
        @JvmStatic
        fun getSplitPermissions(): List<SplitPermissionInfo> = emptyList()

        /** PropertyInvalidatedCache key the AOSP PackageManager references
         * (the real constant: a system-module cache key). */
        const val CACHE_KEY_PACKAGE_INFO_CACHE = "cache_key.system_server.package_info"
    }
}
