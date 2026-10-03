package com.android.server.net

import android.net.Network
import android.telephony.SubscriptionPlan

/**
 * Hosted adaptation of AOSP `com.android.server.net.NetworkPolicyManagerInternal`
 * (pinned services/core). The stock class statically imports
 * `NetworkPolicyManagerService.UidBlockedState`, which reaches the activity
 * manager's process list; this host has no NetworkPolicyManagerService. The
 * abstract local-service surface is unchanged; `updateBlockedReasonsWithProcState`
 * is omitted because nothing hosted computes UID blocked reasons.
 */
abstract class NetworkPolicyManagerInternal {
    abstract fun resetUserState(userId: Int)
    abstract fun onTempPowerSaveWhitelistChange(appId: Int, added: Boolean, reasonCode: Int, reason: String?)
    abstract fun getSubscriptionPlan(network: Network?): SubscriptionPlan?
    abstract fun getSubscriptionOpportunisticQuota(network: Network?, quotaType: Int): Long
    abstract fun onAdminDataAvailable()
    abstract fun setAppIdleWhitelist(uid: Int, shouldWhitelist: Boolean)
    abstract fun setMeteredRestrictedPackages(packageNames: Set<String>?, userId: Int)
    abstract fun setMeteredRestrictedPackagesAsync(packageNames: Set<String>?, userId: Int)
    abstract fun setLowPowerStandbyActive(active: Boolean)
    abstract fun setLowPowerStandbyAllowlist(uids: IntArray?)

    companion object {
        const val QUOTA_TYPE_JOBS = 1
        const val QUOTA_TYPE_MULTIPATH = 2
    }
}
