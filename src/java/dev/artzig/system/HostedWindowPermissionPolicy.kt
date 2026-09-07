package dev.artzig.system

import android.app.TaskInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.server.policy.PermissionPolicyInternal

/**
 * PermissionPolicyService.Internal's launch policy. The Android service also
 * owns PermissionController upgrades and permission/AppOps reconciliation;
 * this host installs its grants through HostedPackageManagerService instead.
 * Keep removed actions, intent mutation, task eligibility and initialization
 * ordering here, without making framework callers aware of the host.
 */
internal class HostedWindowPermissionPolicy(
    private val context: Context,
    private val packages: HostedPackageManagerService,
) : PermissionPolicyInternal() {
    private val lock = Any()
    private var initialized = false
    private var callback: OnInitializedCallback? = null

    fun startUser(userId: Int) {
        require(userId == UserHandle.USER_SYSTEM)
        val notify = synchronized(lock) {
            if (initialized) return
            initialized = true
            callback
        }
        notify?.onInitialized(userId)
    }

    override fun isInitialized(userId: Int): Boolean = synchronized(lock) {
        userId == UserHandle.USER_SYSTEM && initialized
    }

    override fun setOnInitializedCallback(value: OnInitializedCallback) {
        synchronized(lock) { callback = value }
    }

    override fun checkStartActivity(intent: Intent, callingUid: Int, callingPackage: String?): Boolean {
        if (callingPackage != null && (intent.action == android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER ||
                intent.action == android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)) {
            val info = packages.getApplicationInfo(callingPackage, 0L, UserHandle.getUserId(callingUid))
            if (info != null && info.targetSdkVersion >= android.os.Build.VERSION_CODES.Q) return false
            intent.putExtra(Intent.EXTRA_CALLING_PACKAGE, callingPackage)
        }
        return intent.action != PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER ||
            (callingUid == android.os.Process.SYSTEM_UID && callingPackage == "android")
    }

    override fun isIntentToPermissionDialog(intent: Intent): Boolean =
        intent.`package` == packages.permissionControllerPackageName &&
            (intent.action == PackageManager.ACTION_REQUEST_PERMISSIONS ||
                intent.action == PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER)

    private fun launcher(intent: Intent): Boolean = intent.action == Intent.ACTION_MAIN &&
        intent.categories?.any { it == Intent.CATEGORY_LAUNCHER ||
            it == Intent.CATEGORY_LEANBACK_LAUNCHER || it == Intent.CATEGORY_CAR_LAUNCHER } == true

    override fun shouldShowNotificationDialogForTask(task: TaskInfo?, currentPackage: String?,
        callingPackage: String?, intent: Intent?, activityName: String): Boolean {
        if (intent == null || currentPackage == null || task == null ||
            !(task.isFocused && task.isVisible && task.isRunning)) return false
        return launcher(intent) ||
            (task.baseActivity?.packageName == currentPackage && launcher(task.baseIntent)) ||
            (currentPackage == callingPackage && task.baseIntent.filterEquals(intent) &&
                task.numActivities == 1 && activityName == task.topActivityInfo!!.name)
    }

    override fun showNotificationPromptIfNeeded(packageName: String, userId: Int, taskId: Int) {
        val pkg = packages.getPackage(packageName) ?: return
        if (taskId == android.app.ActivityTaskManager.INVALID_TASK_ID ||
            packageName == packages.permissionControllerPackageName ||
            pkg.targetSdkVersion < android.os.Build.VERSION_CODES.M ||
            android.os.SystemProperties.get("sys.azk.boot_completed") != "1" ||
            android.Manifest.permission.POST_NOTIFICATIONS !in pkg.requestedPermissions ||
            android.app.compat.CompatChanges.isChangeEnabled(194833441L, packageName, UserHandle.of(userId)) ||
            context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val notifications = android.app.INotificationManager.Stub.asInterface(
            android.os.ServiceManager.getService(Context.NOTIFICATION_SERVICE))
        val uid = packages.getPackageUid(packageName, 0L, userId)
        if (notifications.getNumNotificationChannelsForPackage(packageName, uid, true) == 0 ||
            packages.checkUidPermission(android.Manifest.permission.POST_NOTIFICATIONS, uid) ==
            PackageManager.PERMISSION_GRANTED) return
        throw dev.artzig.ArtzigTestDiagnostics.unsupported("PermissionPolicyInternal",
            "showNotificationPromptIfNeeded", "Legacy notification prompts require permission flags and an overlay permission activity")
    }
}
