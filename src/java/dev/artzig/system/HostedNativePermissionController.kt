package dev.artzig.system

import android.app.AppOpsManager
import android.app.IActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.IPermissionController
import com.android.internal.app.IAppOpsService

/** Pinned ActivityManagerService.PermissionController for native Binder clients.
 * AMS itself remains hosted; these methods delegate to the same package, permission and
 * original AppOps services as Java clients, including attribution and failure results. */
internal class HostedNativePermissionController(
    private val context: Context,
    private val activity: IActivityManager,
    private val appOps: IAppOpsService,
) : IPermissionController.Stub() {
    override fun checkPermission(permission: String?, pid: Int, uid: Int): Boolean =
        activity.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_GRANTED
    override fun noteOp(op: String, uid: Int, packageName: String?): Int =
        appOps.noteOperation(AppOpsManager.strOpToOp(op), uid, packageName, null, false, "", false).opMode
    override fun getPackagesForUid(uid: Int): Array<String>? = context.packageManager.getPackagesForUid(uid)
    override fun isRuntimePermission(permission: String): Boolean = try {
        val info = context.packageManager.getPermissionInfo(permission, 0)
        info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
    } catch (error: PackageManager.NameNotFoundException) {
        android.util.Slog.e("ActivityManager", "No such permission: $permission", error)
        false
    }
    override fun getPackageUid(packageName: String, flags: Int): Int = try {
        context.packageManager.getPackageUid(packageName, flags)
    } catch (_: PackageManager.NameNotFoundException) { -1 }
}
