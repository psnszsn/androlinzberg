package dev.artzig.system

import android.companion.virtual.VirtualDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.permission.IPermissionManager
import android.permission.flags.Flags

/**
 * PermissionManagerService.checkPermission/checkUidPermission and PermissionService's
 * user/device selection over the session's existing package permission state.
 * There is one hosted user and no virtual devices. Remaining permission-manager
 * operations stay explicit unsupported Binder calls until their policy is hosted.
 */
class HostedPermissionManagerService(private val packages: HostedPackageManagerService) :
    IPermissionManager.Stub(), IPermissionManager by unsupported<IPermissionManager>() {
    private fun usesHostedPermissionState(permission: String, device: String?): Boolean =
        !Flags.deviceAwarePermissionApisEnabled() || !Flags.deviceAwarePermissionsEnabled() ||
            device == VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT ||
            (permission != android.Manifest.permission.CAMERA && permission != android.Manifest.permission.RECORD_AUDIO)

    override fun checkPermission(packageName: String?, permissionName: String?, persistentDeviceId: String?, userId: Int): Int {
        if (packageName == null || permissionName == null || userId != UserHandle.USER_SYSTEM ||
            !usesHostedPermissionState(permissionName, persistentDeviceId)) return PackageManager.PERMISSION_DENIED
        return packages.checkPermission(permissionName, packageName, userId)
    }

    override fun checkUidPermission(uid: Int, permissionName: String?, deviceId: Int): Int {
        val persistentId = if (deviceId == Context.DEVICE_ID_DEFAULT) VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT else null
        if (permissionName == null || UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM ||
            !usesHostedPermissionState(permissionName, persistentId)) return PackageManager.PERMISSION_DENIED
        return packages.checkUidPermission(permissionName, uid)
    }
}
