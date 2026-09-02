package dev.artzig.system

import android.content.AttributionSourceState
import android.content.pm.PackageManager
import android.permission.IPermissionChecker

/**
 * The session's `permission_checker` service (source note:
 * `com.android.server.pm.permission.PermissionCheckerService`, reduced): a
 * permission is granted when the requesting uid of the attribution chain holds
 * it in the hosted package manager. App-ops are not an enforcement layer on
 * this host, so data-delivery bookkeeping and op checks always allow.
 */
class HostedPermissionCheckerService(private val packages: HostedPackageManagerService) : IPermissionChecker.Stub() {
    override fun checkPermission(
        permission: String?,
        attributionSource: AttributionSourceState?,
        message: String?,
        forDataDelivery: Boolean,
        startDataDelivery: Boolean,
        fromDatasource: Boolean,
        attributedOp: Int,
    ): Int {
        var state = attributionSource ?: return IPermissionChecker.PERMISSION_HARD_DENIED
        // AOSP checks every link of the chain; a datasource skips its own link.
        if (fromDatasource) state = state.next?.firstOrNull() ?: return IPermissionChecker.PERMISSION_GRANTED
        while (true) {
            if (packages.checkUidPermission(permission, state.uid) != PackageManager.PERMISSION_GRANTED) {
                return IPermissionChecker.PERMISSION_HARD_DENIED
            }
            state = state.next?.firstOrNull() ?: return IPermissionChecker.PERMISSION_GRANTED
        }
    }

    override fun finishDataDelivery(op: Int, attributionSource: AttributionSourceState?, fromDatasource: Boolean) {}

    override fun checkOp(
        op: Int,
        attributionSource: AttributionSourceState?,
        message: String?,
        forDataDelivery: Boolean,
        startDataDelivery: Boolean,
    ): Int = IPermissionChecker.PERMISSION_GRANTED
}
