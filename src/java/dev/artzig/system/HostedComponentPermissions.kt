package dev.artzig.system

import android.app.AppGlobals
import android.content.pm.PackageManager
import android.os.RemoteException

/**
 * The hosted package service owns permissions in place of PermissionManagerService.
 * Stock ActivityManager retains the UID, owner and exported-component ordering.
 * This session has only the default Android device and grants no device-specific
 * permission on an unknown device.
 */
object HostedComponentPermissions {
    @JvmStatic
    fun check(permission: String?, uid: Int, owningUid: Int, exported: Boolean): Int =
        android.app.ActivityManager.checkComponentPermission(permission, uid, owningUid, exported)

    @JvmStatic
    @Throws(RemoteException::class)
    fun checkUid(permission: String?, uid: Int, deviceId: Int): Int =
        if (deviceId == android.content.Context.DEVICE_ID_DEFAULT)
            AppGlobals.getPackageManager().checkUidPermission(permission, uid)
        else PackageManager.PERMISSION_DENIED
}
