package dev.artzig.system

import android.app.admin.IDevicePolicyManager
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
}
