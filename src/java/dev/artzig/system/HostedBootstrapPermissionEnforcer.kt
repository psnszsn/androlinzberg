package dev.artzig.system

import android.app.ActivityThread
import android.content.AttributionSource
import android.content.PermissionChecker
import android.content.pm.PackageManager
import android.os.PermissionEnforcer
import android.permission.PermissionCheckerManager

/** Pinned PermissionEnforcer.checkPermission, with Context lookup deferred to the request.
 * The process-local display/window/input services must exist before ActivityThread.attach
 * can create its system context; permission checks run only once that context exists. */
internal class HostedBootstrapPermissionEnforcer : PermissionEnforcer() {
    private val context: android.content.Context get() = ActivityThread.currentActivityThread().systemContext
    override fun checkPermission(permission: String, source: AttributionSource): Int =
        PermissionChecker.checkPermissionForDataDelivery(context,
            permission, PermissionChecker.PID_UNKNOWN, source, "")

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (context.checkPermission(permission, pid, uid) ==
            PackageManager.PERMISSION_GRANTED) PermissionCheckerManager.PERMISSION_GRANTED
        else PermissionCheckerManager.PERMISSION_HARD_DENIED
}
