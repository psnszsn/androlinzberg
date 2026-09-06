package dev.artzig

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.NetworkPolicy
import android.net.NetworkPolicyManager
import android.net.NetworkTemplate
import android.os.Parcel
import dev.artzig.system.HostedNetworkPolicyService

internal object StockNetworkPolicySmoke {
    private class PolicyContext : ContextWrapper(null) {
        var allowed = true
        override fun getOpPackageName() = "android"
        override fun checkCallingOrSelfPermission(name: String): Int =
            if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun enforceCallingOrSelfPermission(name: String, message: String?) {
            if (!allowed) throw SecurityException(name)
        }
    }

    fun run() {
        val context = PolicyContext()
        val service = HostedNetworkPolicyService(context)
        val manager = NetworkPolicyManager(context, service)
        check(runCatching { NetworkPolicyManager(context, null) }.exceptionOrNull() is IllegalArgumentException)
        check(manager.getUidPolicy(10001) == NetworkPolicyManager.POLICY_NONE)
        check(manager.getUidsWithPolicy(NetworkPolicyManager.POLICY_NONE).isEmpty())
        check(manager.networkPolicies.isEmpty())
        check(!manager.restrictBackground)
        check(service.restrictBackgroundByCaller == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        check(!manager.isUidNetworkingBlocked(10001, true))
        check(!service.isUidRestrictedOnMeteredNetworks(10001))
        // No-op updates of the default state must not invent policy entries.
        manager.setUidPolicy(10001, NetworkPolicyManager.POLICY_NONE)
        manager.addUidPolicy(10001, NetworkPolicyManager.POLICY_NONE)
        manager.removeUidPolicy(10001, NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND)
        check(manager.getUidsWithPolicy(NetworkPolicyManager.POLICY_NONE).isEmpty())
        check(runCatching { manager.setUidPolicy(1000, 0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { service.registerListener(null) }.exceptionOrNull() is NullPointerException)
        context.allowed = false
        // Permission checks precede UID validation and any unsupported-enforcement diagnostic.
        check(runCatching { manager.setUidPolicy(1000, 1) }.exceptionOrNull() is SecurityException)
        check(runCatching { manager.getUidPolicy(10001) }.exceptionOrNull() is SecurityException)
        check(runCatching { service.restrictBackgroundByCaller }.exceptionOrNull() is SecurityException)
        check(runCatching { manager.isUidNetworkingBlocked(10001, false) }.exceptionOrNull() is SecurityException)
        context.allowed = true
        check(runCatching { manager.setUidPolicy(10001, NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND) }
            .exceptionOrNull() is UnsupportedOperationException)
        check(manager.getUidPolicy(10001) == NetworkPolicyManager.POLICY_NONE)
        check(runCatching { manager.setRestrictBackground(true) }.exceptionOrNull() is UnsupportedOperationException)
        check(!manager.restrictBackground)

        val template = NetworkTemplate.Builder(NetworkTemplate.MATCH_ETHERNET).build()
        val policy = NetworkPolicy(template, 14, "UTC", 4000, 10000, true)
        check(!policy.isOverWarning(3999) && policy.isOverWarning(4000))
        check(!policy.isOverLimit(6999) && policy.isOverLimit(7000)) // Stock two-MTU allowance.
        policy.lastWarningSnooze = 123
        policy.lastLimitSnooze = 456
        policy.lastRapidSnooze = 789
        policy.inferred = true
        val parcel = Parcel.obtain()
        try {
            policy.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = NetworkPolicy.CREATOR.createFromParcel(parcel)
            check(restored == policy && restored.hashCode() == policy.hashCode())
            restored.clearSnooze()
            check(restored.lastWarningSnooze == NetworkPolicy.SNOOZE_NEVER &&
                restored.lastLimitSnooze == NetworkPolicy.SNOOZE_NEVER &&
                restored.lastRapidSnooze == NetworkPolicy.SNOOZE_NEVER)
        } finally { parcel.recycle() }
        check(runCatching { service.snoozeLimit(template) }.exceptionOrNull() is IllegalArgumentException)
        policy.warningBytes = NetworkPolicy.WARNING_DISABLED
        policy.limitBytes = NetworkPolicy.LIMIT_DISABLED
        check(!policy.isOverWarning(100000) && !policy.isOverLimit(100000))
        check(android.app.SystemServiceRegistry.getSystemServiceName(NetworkPolicyManager::class.java) ==
            Context.NETWORK_POLICY_SERVICE)
    }
}
