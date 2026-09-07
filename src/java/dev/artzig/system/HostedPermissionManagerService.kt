package dev.artzig.system

import android.companion.virtual.VirtualDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.permission.IPermissionManager
import android.permission.flags.Flags

/**
 * PermissionManagerService attribution registration and PermissionService metadata /
 * user-device selection over the session's installed package permission state.
 * There is one hosted user and no virtual devices. Remaining permission-manager
 * operations stay explicit unsupported Binder calls until their policy is hosted.
 */
class HostedPermissionManagerService(private val packages: HostedPackageManagerService) :
    IPermissionManager.Stub(), IPermissionManager by unsupported<IPermissionManager>() {
    override fun asBinder(): android.os.IBinder = this
    private val policy = HostedPermissions(packages)
    private val attributions = java.util.WeakHashMap<android.os.IBinder, android.content.AttributionSource>()

    override fun getAllPermissionGroups(flags: Int): android.content.pm.ParceledListSlice<*> =
        android.content.pm.ParceledListSlice(packages.allPermissionGroups(flags))

    override fun getPermissionGroupInfo(groupName: String, flags: Int): android.content.pm.PermissionGroupInfo? =
        packages.getPermissionGroupInfo(groupName, flags)

    override fun getPermissionInfo(permissionName: String, packageName: String, flags: Int): android.content.pm.PermissionInfo? =
        packages.permissionInfo(permissionName, packageName, flags)

    override fun queryPermissionsByGroup(groupName: String?, flags: Int): android.content.pm.ParceledListSlice<*>? =
        packages.permissionsByGroup(groupName, flags)?.let { android.content.pm.ParceledListSlice(it) }

    override fun getSplitPermissions(): List<android.content.pm.permission.SplitPermissionInfoParcelable> =
        android.permission.PermissionManager.splitPermissionInfoListToParcelableList(
            com.android.server.SystemConfig.getInstance().splitPermissions)

    // PermissionManagerService.AttributionSourceRegistry: retain the supplied token weakly,
    // check its complete value, and only extend a chain which its preceding app registered.
    // No isolated hotword-detection UID is hosted, so UID resolution is the identity mapping.
    override fun registerAttributionSource(state: android.content.AttributionSourceState): android.os.IBinder {
        val token = if (Flags.serverSideAttributionRegistration()) android.os.Binder() else requireNotNull(state.token)
        val source = android.content.AttributionSource(state).withToken(token)
        val caller = android.os.Binder.getCallingUid()
        if (source.uid != caller && !policy.has(android.Manifest.permission.UPDATE_APP_OPS_STATS)) {
            throw SecurityException("Cannot register attribution source for uid:${source.uid} from uid:$caller")
        }
        val user = UserHandle.getUserId(if (caller == android.os.Process.SYSTEM_UID) source.uid else caller)
        if (user != UserHandle.USER_SYSTEM || packages.getPackageUid(source.packageName, 0L, user) != source.uid) {
            throw SecurityException("Cannot register attribution source for package:${source.packageName} from uid:$caller")
        }
        val next = source.next
        synchronized(attributions) {
            if (next?.next != null && !isRegisteredAttributionSource(next.asState())) {
                throw SecurityException("Cannot register forged attribution source:$source")
            }
            attributions[token] = source.withDefaultToken()
        }
        return token
    }

    override fun isRegisteredAttributionSource(state: android.content.AttributionSourceState): Boolean =
        synchronized(attributions) {
            attributions[state.token]?.equalsExceptToken(android.content.AttributionSource(state)) == true
        }

    override fun getRegisteredAttributionSourceCount(uid: Int): Int {
        policy.enforce(android.Manifest.permission.UPDATE_APP_OPS_STATS, "Count attribution sources")
        System.gc()
        System.gc()
        return synchronized(attributions) { attributions.values.count { it.uid == uid } }
    }

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
