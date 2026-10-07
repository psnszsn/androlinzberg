package dev.artzig.permissioncontroller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * PermissionController's GrantPermissionsActivity result contract
 * (GrantPermissionsActivity.onCreate / setResultIfNeeded) without its
 * interactive grant dialog: the host keeps its administrator-managed grants and
 * returns their current state; a request never grants a permission.
 */
class GrantPermissionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent
        val deviceId = request.getIntExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_DEVICE_ID, Context.DEVICE_ID_DEFAULT)
        val target = callingPackage
        if (request.action != PackageManager.ACTION_REQUEST_PERMISSIONS || target == null ||
            deviceId != Context.DEVICE_ID_DEFAULT) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val names = request.getStringArrayExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES)
            .orEmpty().filterNot { it.isNullOrEmpty() }.toTypedArray()
        val results = names.map { packageManager.checkPermission(it, target) }.toIntArray()
        setResult(RESULT_OK, Intent(PackageManager.ACTION_REQUEST_PERMISSIONS)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, names)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, results)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_DEVICE_ID, deviceId)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, target))
        finish()
    }
}

/** ManagePermissionsActivity has no hosted settings UI; it finishes unchanged. */
class ManagePermissionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        finish()
    }
}

/**
 * PermissionControllerService for a host whose runtime grants are
 * administrator-managed: there are no default role grants to apply, no
 * interactive revocations, and no permission-usage history. Each request is
 * answered through its stock callback so system callers never wait.
 */
class HostedPermissionControllerService : android.permission.PermissionControllerService() {
    override fun onRevokeRuntimePermissions(requests: Map<String, List<String>>, doDryRun: Boolean, reason: Int,
        callerPackageName: String, callback: java.util.function.Consumer<Map<String, List<String>>>) =
        callback.accept(emptyMap())

    override fun onGetRuntimePermissionsBackup(user: android.os.UserHandle, backup: java.io.OutputStream,
        callback: Runnable) {
        backup.close()
        callback.run()
    }

    override fun onGetAppPermissions(packageName: String,
        callback: java.util.function.Consumer<List<android.permission.RuntimePermissionPresentationInfo>>) =
        callback.accept(emptyList())

    override fun onRevokeRuntimePermission(packageName: String, permissionName: String, callback: Runnable) =
        callback.run()

    override fun onCountPermissionApps(permissionNames: List<String>, flags: Int,
        callback: java.util.function.IntConsumer) = callback.accept(0)

    override fun onGetPermissionUsages(countSystem: Boolean, numMillis: Long,
        callback: java.util.function.Consumer<List<android.permission.RuntimePermissionUsageInfo>>) =
        callback.accept(emptyList())

    override fun onGrantOrUpgradeDefaultRuntimePermissions(callback: Runnable) = callback.run()

    override fun onSetRuntimePermissionGrantStateByDeviceAdmin(callerPackageName: String, packageName: String,
        permission: String, grantState: Int, callback: java.util.function.Consumer<Boolean>) = callback.accept(false)

    // Stock PermissionPolicyService asks for these on every user start and app
    // install; the host has no user-sensitive flags, one-time sessions or
    // hibernation to maintain.
    override fun onUpdateUserSensitivePermissionFlags(uid: Int, executor: java.util.concurrent.Executor, callback: Runnable) =
        executor.execute(callback)

    override fun onOneTimePermissionSessionTimeout(packageName: String) { }

    override fun onGetPlatformPermissionsForGroup(permissionGroupName: String,
        callback: java.util.function.Consumer<List<String>>) = callback.accept(emptyList())

    override fun onGetGroupOfPlatformPermission(permissionName: String,
        callback: java.util.function.Consumer<String?>) = callback.accept(null)

    override fun onGetUnusedAppCount(callback: java.util.function.IntConsumer) = callback.accept(0)

    override fun onGetHibernationEligibility(packageName: String, callback: java.util.function.IntConsumer) =
        callback.accept(android.permission.PermissionControllerManager.HIBERNATION_ELIGIBILITY_UNKNOWN)
}
