package dev.artzig.system

import android.Manifest.permission
import android.content.Context
import android.net.INetworkPolicyListener
import android.net.INetworkPolicyManager
import android.net.Network
import android.net.NetworkPolicy
import android.net.NetworkPolicyManager
import android.net.NetworkTemplate
import android.os.Binder
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.telephony.SubscriptionPlan
import com.android.net.module.util.PermissionUtils
import dev.artzig.ArtzigTestDiagnostics

/**
 * NetworkPolicyManagerService's default unrestricted state and permission gates.
 * Linux has no Android UID firewall or carrier-policy controller on this host.
 * Queries expose that state; mutations requiring enforcement fail explicitly and
 * cannot create a policy that the kernel does not enforce. The stock manager owns
 * client validation, listener dispatch, policy values and subscription forwarding.
 */
class HostedNetworkPolicyService(private val context: Context) :
    INetworkPolicyManager.Stub(), INetworkPolicyManager by unsupported<INetworkPolicyManager>() {
    override fun asBinder(): android.os.IBinder = this
    private val listeners = RemoteCallbackList<INetworkPolicyListener>()

    private fun enforce(name: String) = context.enforceCallingOrSelfPermission(name, "NetworkPolicy")
    private fun networkStack() = PermissionUtils.enforceNetworkStackPermission(context)
    private fun observe() = PermissionUtils.enforceAnyPermissionOf(
        context, permission.CONNECTIVITY_INTERNAL, permission.OBSERVE_NETWORK_POLICY)
    private fun unavailable(member: String): Nothing = throw ArtzigTestDiagnostics.unsupported(
        "android.net.INetworkPolicyManager", member,
        "Android UID firewall and carrier network-policy enforcement are not hosted")

    override fun getUidPolicy(uid: Int): Int {
        enforce(permission.MANAGE_NETWORK_POLICY)
        return NetworkPolicyManager.POLICY_NONE
    }
    override fun getUidsWithPolicy(policy: Int): IntArray {
        enforce(permission.MANAGE_NETWORK_POLICY)
        return intArrayOf()
    }
    private fun validateUid(uid: Int) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        require(UserHandle.isApp(uid)) { "cannot apply policy to UID $uid" }
    }
    override fun setUidPolicy(uid: Int, policy: Int) {
        validateUid(uid)
        if (policy != NetworkPolicyManager.POLICY_NONE) unavailable("setUidPolicy")
    }
    override fun addUidPolicy(uid: Int, policy: Int) {
        validateUid(uid)
        if (policy != NetworkPolicyManager.POLICY_NONE) unavailable("addUidPolicy")
    }
    override fun removeUidPolicy(uid: Int, policy: Int) {
        validateUid(uid)
        // No UID has a policy, so removing any bits leaves the existing state unchanged.
    }
    override fun registerListener(listener: INetworkPolicyListener?) {
        java.util.Objects.requireNonNull(listener)
        observe()
        listeners.register(listener)
    }
    override fun unregisterListener(listener: INetworkPolicyListener?) {
        java.util.Objects.requireNonNull(listener)
        observe()
        listeners.unregister(listener)
    }
    override fun getNetworkPolicies(callingPackage: String?): Array<NetworkPolicy> {
        enforce(permission.MANAGE_NETWORK_POLICY)
        try { enforce(permission.READ_PRIVILEGED_PHONE_STATE) }
        catch (_: SecurityException) {
            enforce(permission.READ_PHONE_STATE)
            context.getSystemService(android.app.AppOpsManager::class.java).noteOp(
                android.app.AppOpEnums.APP_OP_READ_PHONE_STATE, Binder.getCallingUid(),
                callingPackage, null, null)
        }
        return emptyArray()
    }
    override fun setNetworkPolicies(policies: Array<out NetworkPolicy?>?) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        java.util.Objects.requireNonNull(policies)
        if (policies!!.any { it != null }) unavailable("setNetworkPolicies")
    }
    override fun snoozeLimit(template: NetworkTemplate?) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        throw IllegalArgumentException("unable to find policy for $template")
    }
    override fun getRestrictBackground(): Boolean {
        enforce(permission.MANAGE_NETWORK_POLICY)
        return false
    }
    override fun getRestrictBackgroundByCaller(): Int {
        enforce(permission.ACCESS_NETWORK_STATE)
        return android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
    }
    override fun getRestrictBackgroundStatus(uid: Int): Int {
        networkStack()
        return android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
    }
    override fun setRestrictBackground(restrictBackground: Boolean) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        if (restrictBackground) unavailable("setRestrictBackground")
    }
    override fun setDeviceIdleMode(enabled: Boolean) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        if (enabled) unavailable("setDeviceIdleMode")
    }
    override fun setWifiMeteredOverride(networkId: String?, meteredOverride: Int) {
        enforce(permission.MANAGE_NETWORK_POLICY)
        unavailable("setWifiMeteredOverride")
    }
    override fun getMultipathPreference(network: Network?): Int {
        networkStack()
        return 0
    }
    override fun isUidNetworkingBlocked(uid: Int, meteredNetwork: Boolean): Boolean {
        enforce(permission.OBSERVE_NETWORK_POLICY)
        return false
    }
    override fun isUidRestrictedOnMeteredNetworks(uid: Int): Boolean {
        enforce(permission.OBSERVE_NETWORK_POLICY)
        return false
    }
    override fun getSubscriptionPlan(template: NetworkTemplate?): SubscriptionPlan? {
        enforce(android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
        return null
    }
    private fun subscriptionAccess(callingPackage: String?) {
        // There is no carrier configuration, delegated plan owner or carrier privilege.
        // Preserve package identity validation before the final permission check.
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid)
        if (packages == null || !packages.contains(callingPackage)) {
            throw SecurityException("Package $callingPackage does not belong to uid $uid")
        }
        enforce(permission.MANAGE_SUBSCRIPTION_PLANS)
    }
    override fun getSubscriptionPlans(subId: Int, callingPackage: String?): Array<SubscriptionPlan>? {
        subscriptionAccess(callingPackage)
        return null
    }
    override fun setSubscriptionPlans(subId: Int, plans: Array<out SubscriptionPlan?>?,
        expirationDurationMillis: Long, callingPackage: String?) {
        subscriptionAccess(callingPackage)
        unavailable("setSubscriptionPlans")
    }
    override fun getSubscriptionPlansOwner(subId: Int): String? {
        // Pinned service exposes this debugging API only to core callers.
        if (UserHandle.getCallingAppId() != android.os.Process.SYSTEM_UID) {
            throw SecurityException("Requires system UID")
        }
        return null
    }
    override fun setSubscriptionOverride(subId: Int, overrideMask: Int, overrideValue: Int,
        networkTypes: IntArray?, expirationDurationMillis: Long, callingPackage: String?) {
        subscriptionAccess(callingPackage)
        unavailable("setSubscriptionOverride")
    }
    override fun notifyStatsProviderWarningOrLimitReached() {
        enforce(android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
        unavailable("notifyStatsProviderWarningOrLimitReached")
    }
    override fun factoryReset(subscriber: String?) {
        enforce(permission.NETWORK_SETTINGS)
        val users = context.getSystemService(android.os.UserManager::class.java)
        if (users.hasUserRestriction(android.os.UserManager.DISALLOW_NETWORK_RESET)) return
        // Preserve the stock nested permission checks even with an empty policy set.
        setNetworkPolicies(getNetworkPolicies(context.opPackageName))
        setRestrictBackground(false)
        if (!users.hasUserRestriction(android.os.UserManager.DISALLOW_APPS_CONTROL)) {
            for (uid in getUidsWithPolicy(NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND)) {
                setUidPolicy(uid, NetworkPolicyManager.POLICY_NONE)
            }
        }
    }
}
