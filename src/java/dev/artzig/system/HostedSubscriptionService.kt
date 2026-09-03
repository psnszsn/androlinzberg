package dev.artzig.system

import android.os.UserHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

/**
 * The session's ISub over the real AIDL surface, published as "isub".
 *
 * Source note: `com.android.internal.telephony.subscription.SubscriptionManagerService`
 * with an empty subscription database (no SIM was ever inserted): every list is
 * empty, every default subscription id is INVALID_SUBSCRIPTION_ID, slot and phone
 * lookups fail with the INVALID_* ids, phone numbers are the empty string. The
 * getters keep AOSP's phone-state permission checks (a caller that never
 * declared READ_PHONE_STATE gets the SecurityException); the mutators that would
 * write the subscription database stay unserved.
 */
class HostedSubscriptionService(private val permissions: HostedTelephonyPermissions, private val activeModemCount: Int) : UnsupportedSub() {
    private fun emptySubscriptions(): MutableList<SubscriptionInfo?> = ArrayList()

    override fun getAllSubInfoList(callingPackage: String?, callingFeatureId: String?): MutableList<SubscriptionInfo?>? {
        permissions.enforcePrivilegedPhoneState("getAllSubInfoList")
        return emptySubscriptions()
    }
    override fun getActiveSubscriptionInfo(subId: Int, callingPackage: String?, callingFeatureId: String?): SubscriptionInfo? {
        permissions.enforceReadPhoneState("getActiveSubscriptionInfo")
        return null
    }
    override fun getActiveSubscriptionInfoForIccId(iccId: String?, callingPackage: String?, callingFeatureId: String?): SubscriptionInfo? {
        permissions.enforcePrivilegedPhoneState("getActiveSubscriptionInfoForIccId")
        return null
    }
    override fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): SubscriptionInfo? {
        permissions.enforceReadPhoneState("getActiveSubscriptionInfoForSimSlotIndex")
        return null
    }
    override fun getActiveSubscriptionInfoList(callingPackage: String?, callingFeatureId: String?, isForAllProfiles: Boolean): MutableList<SubscriptionInfo?>? {
        permissions.enforceReadPhoneState("getActiveSubscriptionInfoList")
        return emptySubscriptions()
    }
    override fun getActiveSubInfoCount(callingPackage: String?, callingFeatureId: String?, isForAllProfile: Boolean): Int {
        permissions.enforceReadPhoneState("getActiveSubInfoCount")
        return 0
    }
    /** SubscriptionManagerService.getActiveSubInfoCountMax: the modem count. */
    override fun getActiveSubInfoCountMax(): Int = activeModemCount
    override fun getAvailableSubscriptionInfoList(callingPackage: String?, callingFeatureId: String?): MutableList<SubscriptionInfo?>? {
        permissions.enforcePrivilegedPhoneState("getAvailableSubscriptionInfoList")
        return emptySubscriptions()
    }
    override fun getAccessibleSubscriptionInfoList(callingPackage: String?): MutableList<SubscriptionInfo?>? = emptySubscriptions()
    override fun getOpportunisticSubscriptions(callingPackage: String?, callingFeatureId: String?): MutableList<SubscriptionInfo?>? = emptySubscriptions()
    override fun getSubscriptionsInGroup(groupUuid: android.os.ParcelUuid?, callingPackage: String?, callingFeatureId: String?): MutableList<SubscriptionInfo?>? = emptySubscriptions()
    override fun getSubscriptionInfoListAssociatedWithUser(userHandle: UserHandle?): MutableList<SubscriptionInfo?>? {
        permissions.enforce(android.Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION, "getSubscriptionInfoListAssociatedWithUser")
        return emptySubscriptions()
    }

    override fun getSlotIndex(subId: Int): Int = SubscriptionManager.INVALID_SIM_SLOT_INDEX
    override fun getSubId(slotIndex: Int): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getPhoneId(subId: Int): Int = SubscriptionManager.INVALID_PHONE_INDEX
    override fun getDefaultSubId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultSubIdAsUser(userId: Int): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultDataSubId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultVoiceSubId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultVoiceSubIdAsUser(userId: Int): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultSmsSubId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getDefaultSmsSubIdAsUser(userId: Int): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getActiveDataSubscriptionId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getPreferredDataSubscriptionId(): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getEnabledSubscriptionId(slotIndex: Int): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getActiveSubIdList(visibleOnly: Boolean): IntArray? = IntArray(0)
    override fun isActiveSubId(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun isSubscriptionEnabled(subId: Int): Boolean = false
    override fun canDisablePhysicalSubscription(): Boolean = false
    override fun getSubscriptionProperty(subId: Int, propKey: String?, callingPackage: String?, callingFeatureId: String?): String? = null
    override fun getPhoneNumber(subId: Int, source: Int, callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceReadPhoneNumber("getPhoneNumber")
        return ""
    }
    override fun getPhoneNumberFromFirstAvailableSource(subId: Int, callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceReadPhoneNumber("getPhoneNumberFromFirstAvailableSource")
        return ""
    }
    override fun getSubscriptionUserHandle(subId: Int): UserHandle? = null
    override fun isSubscriptionAssociatedWithCallingUser(subscriptionId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun isSubscriptionAssociatedWithUser(subscriptionId: Int, userHandle: UserHandle?): Boolean = false
}
