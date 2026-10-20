package dev.artzig.system

import android.app.admin.IDevicePolicyManager
import android.app.admin.PasswordMetrics
import android.app.admin.ParcelableResource
import android.content.ComponentName
import android.os.IBinder
import android.os.UserHandle

/**
 * Unmanaged-device policy state on the stock [IDevicePolicyManager] transport.
 *
 * Source note: these are the empty-policy query results maintained by AOSP
 * `DevicePolicyManagerService` before any owner or active admin is provisioned.
 * Android's persistent policy store, provisioning controller, KeyChain service,
 * and enterprise network backends do not exist in the hosted session. Mutating
 * operations therefore stay on [unsupported] and report a structured failure,
 * while stock client queries see one stable, unmanaged user.
 *
 * Stock LockSettingsService and TrustManagerService ask this service for the
 * password and keyguard policy they enforce. Those answers follow DevicePolicyManagerService on a device without
 * `android.software.device_admin` (no admin can exist): no minimum, no
 * history, and password changes have nothing to update. Blocker for the stock
 * service: DevicePolicyManagerService's closure (its policy engine, KeyChain
 * and provisioning collaborators) is not yet compiled; its StatsPullAtomService
 * dependency also reaches unwired KernelCpuBpfTracking JNI and health HIDL APIs.
 */
class HostedDevicePolicyManagerService :
    IDevicePolicyManager.Stub(),
    IDevicePolicyManager by unsupported<IDevicePolicyManager>() {
    override fun asBinder(): IBinder = this

    override fun isAdminActive(policyReceiver: ComponentName?, userHandle: Int): Boolean = false
    override fun isRemovingAdmin(adminReceiver: ComponentName?, userHandle: Int): Boolean = false
    override fun getActiveAdmins(userHandle: Int): MutableList<ComponentName>? = null
    override fun packageHasActiveAdmins(packageName: String?, userHandle: Int): Boolean = false
    override fun hasGrantedPolicy(policyReceiver: ComponentName?, usesPolicy: Int, userHandle: Int): Boolean = false

    override fun hasDeviceOwner(): Boolean = false
    override fun getDeviceOwnerComponent(callingUserOnly: Boolean): ComponentName? = null
    override fun getDeviceOwnerComponentOnUser(userId: Int): ComponentName? = null
    override fun getDeviceOwnerName(): String? = null
    override fun getDeviceOwnerUserId(): Int = UserHandle.USER_NULL
    override fun getProfileOwnerAsUser(userHandle: Int): ComponentName? = null
    override fun getProfileOwnerName(userHandle: Int): String? = null
    override fun hasUserSetupCompleted(): Boolean = true
    override fun isOrganizationOwnedDeviceWithManagedProfile(): Boolean = false
    override fun isManagedProfile(admin: ComponentName?): Boolean = false

    override fun getPermissionPolicy(admin: ComponentName?): Int =
        android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
    override fun isSecurityLoggingEnabled(admin: ComponentName?, packageName: String?): Boolean = false
    override fun isNetworkLoggingEnabled(admin: ComponentName?, packageName: String?): Boolean = false
    override fun isCurrentInputMethodSetByOwner(): Boolean = false
    override fun canProfileOwnerResetPasswordWhenLocked(userId: Int): Boolean = false
    override fun isSafeOperation(reason: Int): Boolean = true
    override fun getDrawable(drawableId: String?, drawableStyle: String?, drawableSource: String?): ParcelableResource? = null
    override fun getString(stringId: String?): ParcelableResource? = null

    override fun getRestrictionsProvider(userHandle: Int): ComponentName? {
        // DevicePolicyManagerService.getRestrictionsProvider: only the system
        // restrictions service may query the provider. No admin is provisioned.
        if (!UserHandle.isSameApp(android.os.Binder.getCallingUid(), android.os.Process.SYSTEM_UID))
            throw SecurityException("Only the system may query the restrictions provider")
        return null
    }

    override fun shouldAllowBypassingDevicePolicyManagementRoleQualification(): Boolean {
        if (android.os.Binder.getCallingPid() != android.os.Process.myPid() &&
            android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS,
                android.os.Binder.getCallingUid(), -1, true) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Role qualification requires " + android.Manifest.permission.MANAGE_ROLE_HOLDERS)
        // DPMS.hasIncompatibleAccountsOnAnyUser treats an uninitialized account
        // compatibility inventory as incompatible. The hosted policy adapter has
        // neither that inventory nor a provisioning override, so qualification
        // must not be bypassed. The full DPMS closure blocker is documented above.
        return false
    }

    override fun getPasswordMinimumMetrics(userHandle: Int, deviceWideOnly: Boolean): PasswordMetrics {
        require(userHandle >= 0) { "Invalid userId" }
        return PasswordMetrics.merge(emptyList())
    }
    override fun getPasswordHistoryLength(who: ComponentName?, userHandle: Int, parent: Boolean): Int = 0
    override fun reportPasswordChanged(metrics: PasswordMetrics?, userId: Int) {}
    // LockPatternUtils' password policy queries.
    override fun getAggregatedPasswordComplexityForUser(userId: Int, deviceWideOnly: Boolean): Int =
        android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE
    override fun getPasswordQuality(who: ComponentName?, userHandle: Int, parent: Boolean): Int =
        android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
    override fun getPasswordMinimumLength(who: ComponentName?, userHandle: Int, parent: Boolean): Int = 0
    override fun getMaximumFailedPasswordsForWipe(who: ComponentName?, userHandle: Int, parent: Boolean): Int = 0
    // TrustManagerService's keyguard policy.
    override fun getKeyguardDisabledFeatures(who: ComponentName?, userHandle: Int, parent: Boolean): Int = 0
    override fun getTrustAgentConfiguration(admin: ComponentName?, agent: ComponentName?, userHandle: Int, parent: Boolean): MutableList<android.os.PersistableBundle>? = null
    override fun getMaximumTimeToLock(who: ComponentName?, userHandle: Int, parent: Boolean): Long = 0
    override fun getRequiredStrongAuthTimeout(who: ComponentName?, userId: Int, parent: Boolean): Long =
        android.app.admin.DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS

    // Keyguard's attempt reports: DevicePolicyData.mFailedPasswordAttempts per
    // user. The hosted service keeps no policy file, so the count lasts for
    // the session.
    private val failedAttempts = HashMap<Int, Int>()
    private fun enforceAttemptReport(userHandle: Int) {
        require(userHandle >= 0) { "Invalid userId" }
        // Context.enforceCallingOrSelfPermission: the system server itself holds it.
        if (android.os.Binder.getCallingPid() != android.os.Process.myPid() &&
            android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.BIND_DEVICE_ADMIN,
                android.os.Binder.getCallingUid(), -1, true) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw SecurityException("report password attempt requires " + android.Manifest.permission.BIND_DEVICE_ADMIN)
    }
    override fun reportFailedPasswordAttempt(userHandle: Int, parent: Boolean) {
        enforceAttemptReport(userHandle)
        synchronized(failedAttempts) { failedAttempts[userHandle] = (failedAttempts[userHandle] ?: 0) + 1 }
    }
    override fun reportSuccessfulPasswordAttempt(userHandle: Int) {
        enforceAttemptReport(userHandle)
        synchronized(failedAttempts) { failedAttempts.remove(userHandle) }
    }
    override fun getCurrentFailedPasswordAttempts(callerPackageName: String?, userHandle: Int, parent: Boolean): Int {
        require(userHandle >= 0) { "Invalid userId" }
        return synchronized(failedAttempts) { failedAttempts[userHandle] ?: 0 }
    }
}
