package dev.artzig.system

import android.companion.virtual.VirtualDeviceManager
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.os.UserHandle
import android.permission.flags.Flags
import com.android.server.pm.permission.PermissionManagerServiceInterface

/**
 * PermissionManagerService's policy backend over the hosted installed-package
 * registry. The Android access-checking backend requires persistent system-server
 * permission state; this session supplies its own install-time grants. The stock
 * Binder facade owns attribution registration and permission/AppOps data delivery.
 * Policy mutations not implemented by this backend report structured unsupported calls.
 */
class HostedPermissionPolicy(private val packages: HostedPackageManagerService) :
    PermissionManagerServiceInterface by unsupported<PermissionManagerServiceInterface>() {
    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> =
        packages.allPermissionGroups(flags)

    override fun getPermissionGroupInfo(groupName: String, flags: Int): PermissionGroupInfo? =
        packages.getPermissionGroupInfo(groupName, flags)

    override fun getPermissionInfo(permissionName: String, flags: Int, packageName: String): PermissionInfo? =
        packages.permissionInfo(permissionName, packageName, flags)

    override fun queryPermissionsByGroup(groupName: String?, flags: Int): List<PermissionInfo>? =
        packages.permissionsByGroup(groupName, flags)

    override fun getSplitPermissions(): List<android.content.pm.permission.SplitPermissionInfoParcelable> =
        android.permission.PermissionManager.splitPermissionInfoListToParcelableList(
            com.android.server.SystemConfig.getInstance().splitPermissions)

    private fun usesHostedPermissionState(permission: String, device: String?): Boolean =
        !Flags.deviceAwarePermissionApisEnabled() || !Flags.deviceAwarePermissionsEnabled() ||
            device == VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT ||
            (permission != android.Manifest.permission.CAMERA && permission != android.Manifest.permission.RECORD_AUDIO)

    override fun checkPermission(packageName: String?, permissionName: String?, persistentDeviceId: String?, userId: Int): Int {
        if (packageName == null || permissionName == null || userId != UserHandle.USER_SYSTEM ||
            !usesHostedPermissionState(permissionName, persistentDeviceId)) return PackageManager.PERMISSION_DENIED
        return packages.checkPermission(permissionName, packageName, userId)
    }

    override fun checkUidPermission(uid: Int, permissionName: String?, persistentDeviceId: String?): Int {
        if (permissionName == null || UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM ||
            !usesHostedPermissionState(permissionName, persistentDeviceId)) return PackageManager.PERMISSION_DENIED
        return packages.checkUidPermission(permissionName, uid)
    }
}
