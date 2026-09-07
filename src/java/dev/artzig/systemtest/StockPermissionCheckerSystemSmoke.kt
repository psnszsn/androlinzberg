package dev.artzig.systemtest

import android.app.AppOpsManager
import android.content.AttributionSource
import android.content.Context
import android.content.PermissionChecker
import android.os.Binder
import android.os.Process
import android.os.ServiceManager
import android.permission.IPermissionChecker
import android.permission.PermissionManager
import com.android.internal.app.IAppOpsService

/** PermissionChecker's public Binder contract against the real AppOps service. */
internal object StockPermissionCheckerSystemSmoke {
    fun run(context: Context) {
        val checker = IPermissionChecker.Stub.asInterface(ServiceManager.getService("permission_checker"))
        val ops = IAppOpsService.Stub.asInterface(ServiceManager.getService(Context.APP_OPS_SERVICE))
        val manager = context.getSystemService(PermissionManager::class.java)!!
        val uid = Process.myUid()
        val pkg = context.packageName
        val op = AppOpsManager.OP_VIBRATE
        val source = AttributionSource.Builder(uid).setPackageName(pkg).build().withToken(Binder())
        fun checkOp(delivery: Boolean = false, start: Boolean = false, chain: AttributionSource = source) =
            checker.checkOp(op, chain.asState(), "permission-checker smoke", delivery, start)
        try {
            check(checker.checkOp(-1, source.asState(), null, false, false) == PermissionChecker.PERMISSION_HARD_DENIED)
            check(checker.checkOp(op, AttributionSource.Builder(uid).build().asState(), null, false, false) == PermissionChecker.PERMISSION_HARD_DENIED)
            check(checker.checkPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES, source.asState(), null,
                false, false, false, AppOpsManager.OP_NONE) == PermissionChecker.PERMISSION_GRANTED)
            check(checker.checkPermission("dev.artzig.missing.permission", source.asState(), null,
                false, false, false, AppOpsManager.OP_NONE) == PermissionChecker.PERMISSION_HARD_DENIED)
            check(checker.checkPermission(android.Manifest.permission.CAMERA, source.asState(), null,
                true, false, false, AppOpsManager.OP_NONE) == PermissionChecker.PERMISSION_HARD_DENIED)
            ops.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED)
            check(checkOp() == PermissionChecker.PERMISSION_GRANTED)
            check(!ops.isOperationActive(op, uid, pkg)) // Preflight never starts accounting.
            ops.setMode(op, uid, pkg, AppOpsManager.MODE_IGNORED)
            check(checkOp() == PermissionChecker.PERMISSION_SOFT_DENIED)
            check(checkOp(true, true) == PermissionChecker.PERMISSION_SOFT_DENIED)
            check(!ops.isOperationActive(op, uid, pkg))
            ops.setMode(op, uid, pkg, AppOpsManager.MODE_ERRORED)
            check(checkOp() == PermissionChecker.PERMISSION_HARD_DENIED)
            ops.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED)
            check(checkOp(true, true) == PermissionChecker.PERMISSION_GRANTED)
            check(ops.isOperationActive(op, uid, pkg))
            checker.finishDataDelivery(op, source.asState(), false)
            check(!ops.isOperationActive(op, uid, pkg))
            val two = AttributionSource.Builder(source).setNext(source).build()
            check(checkOp(chain = two) == PermissionChecker.PERMISSION_HARD_DENIED)
            val trusted = manager.registerAttributionSource(two)
            check(checkOp(chain = trusted) == PermissionChecker.PERMISSION_GRANTED)
            val forged = AttributionSource.Builder(trusted).setAttributionTag("forged").build()
            check(checkOp(chain = forged) == PermissionChecker.PERMISSION_HARD_DENIED)
            val nullPermission = runCatching {
                checker.checkPermission(null, source.asState(), null, false, false, false, AppOpsManager.OP_NONE)
            }.exceptionOrNull()
            check(nullPermission is NullPointerException)
        } finally {
            if (ops.isOperationActive(op, uid, pkg)) checker.finishDataDelivery(op, source.asState(), false)
            ops.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED)
        }
        System.err.println("stock-permission-checker: PASS mode enforcement, data-delivery accounting and chain trust")
    }

    /** Run after the stock network scorer grants peer A its coarse-location permission. */
    fun runRuntimeDelivery(context: Context) {
        val checker = IPermissionChecker.Stub.asInterface(ServiceManager.getService("permission_checker"))
        val ops = IAppOpsService.Stub.asInterface(ServiceManager.getService(Context.APP_OPS_SERVICE))
        val permission = android.Manifest.permission.ACCESS_COARSE_LOCATION
        val op = AppOpsManager.OP_COARSE_LOCATION
        val source = AttributionSource.Builder(Process.myUid()).setPackageName(context.packageName)
            .build().withToken(Binder())
        fun active() = ops.isOperationActive(op, source.uid, source.packageName)
        fun checkPermission(delivery: Boolean, start: Boolean) = checker.checkPermission(permission,
            source.asState(), "runtime permission accounting", delivery, start, false, AppOpsManager.OP_NONE)
        check(checkPermission(false, false) == PermissionChecker.PERMISSION_GRANTED)
        check(!active())
        var starts = 0
        try {
            repeat(2) {
                check(checkPermission(true, true) == PermissionChecker.PERMISSION_GRANTED)
                starts++
                check(active())
            }
            checker.finishDataDelivery(op, source.asState(), false)
            starts--
            check(active())
            checker.finishDataDelivery(op, source.asState(), false)
            starts--
            check(!active())
        } finally {
            repeat(starts) { checker.finishDataDelivery(op, source.asState(), false) }
        }
        System.err.println("stock-permission-checker: PASS runtime permission preflight and nested data delivery")
    }

}
