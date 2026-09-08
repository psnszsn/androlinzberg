package com.android.server.wm

import android.app.Activity
import android.app.ActivityManager
import android.app.IApplicationThread
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import dev.artzig.system.HostedPermissionController

/** ActivityStarter.executeRequest has already resolved the activity and checked
 * launch permissions/firewall/policy. The host has no PermissionController APK:
 * GrantPermissionsActivity.onCreate/setResultIfNeeded are adapted to return the
 * administrator-managed grants, using stock ActivityRecord.sendResult for both
 * immediate and deferred lifecycle delivery. Called under the ATMS global lock.
 */
object HostedActivityOperations {
    @JvmStatic fun complete(info: ActivityInfo?, intent: Intent, caller: IApplicationThread?,
        callingUid: Int, callingPid: Int, callingPackage: String?, resultToken: IBinder?,
        resultWho: String?, requestCode: Int, launchAllowed: Boolean): Int? {
        if (info == null || ComponentName(info.packageName, info.name) != HostedPermissionController.component)
            return null
        check(info.applicationInfo.uid == Process.SYSTEM_UID) {
            "Platform permission activity has uid ${info.applicationInfo.uid}"
        }
        val target = resultToken?.let { ActivityRecord.isInAnyTask(it) }
            ?.takeUnless { it.finishing } ?: return ActivityManager.START_CANCELED
        if (target.packageName != callingPackage || target.uid != callingUid ||
            target.app?.pid != callingPid || caller == null ||
            target.app?.thread?.asBinder() != caller.asBinder()) {
            throw SecurityException("Permission request does not own its result activity")
        }
        if (!launchAllowed) {
            target.sendResult(Process.SYSTEM_UID, resultWho, requestCode, Activity.RESULT_CANCELED,
                null, null, null)
            return ActivityManager.START_ABORTED
        }
        val packages = IPackageManager.Stub.asInterface(checkNotNull(ServiceManager.checkService("package")))
        val data = HostedPermissionController.result(packages, intent, target.packageName, callingUid)
        target.sendResult(Process.SYSTEM_UID, resultWho, requestCode, Activity.RESULT_OK, data, null, null)
        // No activity was created. ActivityStarter.getExternalResult maps this
        // to START_SUCCESS for the caller, while its metrics/lifecycle code
        // correctly avoids dereferencing an absent started ActivityRecord.
        return ActivityManager.START_ABORTED
    }
}
