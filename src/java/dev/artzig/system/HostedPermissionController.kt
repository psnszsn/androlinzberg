package dev.artzig.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.internal.pm.pkg.component.ParsedActivityImpl
import com.android.internal.pm.pkg.component.ParsedIntentInfoImpl

/**
 * Host permission policy endpoint. Source: PermissionController's
 * GrantPermissionsActivity.onCreate / setResultIfNeeded. The standalone controller
 * APK and its interactive grant UI are absent here. The host retains its existing
 * administrator-managed grants and returns their current state; a request never
 * grants a permission. Stock Activity receives the normal activity-result protocol.
 * PackageManager registers this service-owned operation as an activity so stock
 * intent resolution also sees it. TaskManager executes it without starting a UI.
 */
object HostedPermissionController {
    const val PACKAGE = "android"
    val component = ComponentName(PACKAGE, HostedPermissionController::class.java.name)

    fun activity() = ParsedActivityImpl().apply {
        setPackageName(PACKAGE)
        setName(component.className)
        setProcessName("system")
        setExported(true)
        setTheme(android.R.style.Theme_NoDisplay)
        addIntent(ParsedIntentInfoImpl().apply {
            setHasDefault(true)
            intentFilter.addAction(PackageManager.ACTION_REQUEST_PERMISSIONS)
            intentFilter.addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    fun result(packages: android.content.pm.IPackageManager, intent: Intent, target: String, uid: Int): Intent {
        require(intent.action == PackageManager.ACTION_REQUEST_PERMISSIONS) {
            "Permission controller requires ACTION_REQUEST_PERMISSIONS"
        }
        val deviceId = intent.getIntExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_DEVICE_ID,
            Context.DEVICE_ID_DEFAULT)
        require(deviceId == Context.DEVICE_ID_DEFAULT) { "Unknown permission device $deviceId" }
        val names = intent.getStringArrayExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES)
            .orEmpty().filterNot { it.isNullOrEmpty() }.toTypedArray()
        val results = names.map { packages.checkPermission(it, target, UserHandle.getUserId(uid)) }.toIntArray()
        return Intent(PackageManager.ACTION_REQUEST_PERMISSIONS)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, names)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, results)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_DEVICE_ID, deviceId)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, target)
    }
}
