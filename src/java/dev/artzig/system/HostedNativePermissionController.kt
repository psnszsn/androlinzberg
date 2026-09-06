package dev.artzig.system

import android.app.AppOpsManager
import android.app.IActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.IPermissionController
import android.util.Log
import com.android.internal.app.IAppOpsService

/**
 * ActivityManagerService.PermissionController over the session's existing AM,
 * package and app-op authorities. Stock C++ PermissionCache uses this distinct
 * android.os interface; android.permission.IPermissionController serves the
 * permission-management app and is a different protocol.
 */
class HostedNativePermissionController(
    private val context: Context,
    private val activities: IActivityManager,
    private val appOps: IAppOpsService,
) : IPermissionController.Stub() {
    override fun checkPermission(permission: String?, pid: Int, uid: Int): Boolean =
        activities.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_GRANTED

    override fun noteOp(op: String?, uid: Int, packageName: String?): Int =
        checkNotNull(appOps.noteOperation(AppOpsManager.strOpToOp(op ?: throw IllegalArgumentException("Unknown operation string: null")), uid, packageName,
            null, false, "", false)).opMode

    override fun getPackagesForUid(uid: Int): Array<String>? = context.packageManager.getPackagesForUid(uid)

    override fun isRuntimePermission(permission: String?): Boolean = try {
        val info = context.packageManager.getPermissionInfo(permission ?: throw PackageManager.NameNotFoundException(), 0)
        info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
    } catch (missing: PackageManager.NameNotFoundException) {
        Log.e("PermissionController", "No such permission: $permission", missing)
        false
    }

    override fun getPackageUid(packageName: String?, flags: Int): Int = try {
        context.packageManager.getPackageUid(packageName ?: throw PackageManager.NameNotFoundException(), flags)
    } catch (missing: PackageManager.NameNotFoundException) {
        -1
    }
}
