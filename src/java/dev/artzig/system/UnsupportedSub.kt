@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ISub method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedSub : com.android.internal.telephony.ISub.Stub() {
    override fun getAllSubInfoList(callingPackage: String?, callingFeatureId: String?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getAllSubInfoList is not hosted")
    override fun getActiveSubscriptionInfo(subId: Int, callingPackage: String?, callingFeatureId: String?): android.telephony.SubscriptionInfo? = throw UnsupportedOperationException("ISub.getActiveSubscriptionInfo is not hosted")
    override fun getActiveSubscriptionInfoForIccId(iccId: String?, callingPackage: String?, callingFeatureId: String?): android.telephony.SubscriptionInfo? = throw UnsupportedOperationException("ISub.getActiveSubscriptionInfoForIccId is not hosted")
    override fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): android.telephony.SubscriptionInfo? = throw UnsupportedOperationException("ISub.getActiveSubscriptionInfoForSimSlotIndex is not hosted")
    override fun getActiveSubscriptionInfoList(callingPackage: String?, callingFeatureId: String?, isForAllProfiles: Boolean): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getActiveSubscriptionInfoList is not hosted")
    override fun getActiveSubInfoCount(callingPackage: String?, callingFeatureId: String?, isForAllProfile: Boolean): Int = throw UnsupportedOperationException("ISub.getActiveSubInfoCount is not hosted")
    override fun getActiveSubInfoCountMax(): Int = throw UnsupportedOperationException("ISub.getActiveSubInfoCountMax is not hosted")
    override fun getAvailableSubscriptionInfoList(callingPackage: String?, callingFeatureId: String?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getAvailableSubscriptionInfoList is not hosted")
    override fun getAccessibleSubscriptionInfoList(callingPackage: String?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getAccessibleSubscriptionInfoList is not hosted")
    override fun requestEmbeddedSubscriptionInfoListRefresh(cardId: Int) { throw UnsupportedOperationException("ISub.requestEmbeddedSubscriptionInfoListRefresh is not hosted") }
    override fun addSubInfo(uniqueId: String?, displayName: String?, slotIndex: Int, subscriptionType: Int): Int = throw UnsupportedOperationException("ISub.addSubInfo is not hosted")
    override fun removeSubInfo(uniqueId: String?, subscriptionType: Int): Boolean = throw UnsupportedOperationException("ISub.removeSubInfo is not hosted")
    override fun setIconTint(subId: Int, tint: Int): Int = throw UnsupportedOperationException("ISub.setIconTint is not hosted")
    override fun setDisplayNameUsingSrc(displayName: String?, subId: Int, nameSource: Int): Int = throw UnsupportedOperationException("ISub.setDisplayNameUsingSrc is not hosted")
    override fun setDisplayNumber(number: String?, subId: Int): Int = throw UnsupportedOperationException("ISub.setDisplayNumber is not hosted")
    override fun setDataRoaming(roaming: Int, subId: Int): Int = throw UnsupportedOperationException("ISub.setDataRoaming is not hosted")
    override fun setOpportunistic(opportunistic: Boolean, subId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ISub.setOpportunistic is not hosted")
    override fun createSubscriptionGroup(subIdList: IntArray?, callingPackage: String?): android.os.ParcelUuid? = throw UnsupportedOperationException("ISub.createSubscriptionGroup is not hosted")
    override fun setPreferredDataSubscriptionId(subId: Int, needValidation: Boolean, callback: com.android.internal.telephony.ISetOpportunisticDataCallback?) { throw UnsupportedOperationException("ISub.setPreferredDataSubscriptionId is not hosted") }
    override fun getPreferredDataSubscriptionId(): Int = throw UnsupportedOperationException("ISub.getPreferredDataSubscriptionId is not hosted")
    override fun getOpportunisticSubscriptions(callingPackage: String?, callingFeatureId: String?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getOpportunisticSubscriptions is not hosted")
    override fun removeSubscriptionsFromGroup(subIdList: IntArray?, groupUuid: android.os.ParcelUuid?, callingPackage: String?) { throw UnsupportedOperationException("ISub.removeSubscriptionsFromGroup is not hosted") }
    override fun addSubscriptionsIntoGroup(subIdList: IntArray?, groupUuid: android.os.ParcelUuid?, callingPackage: String?) { throw UnsupportedOperationException("ISub.addSubscriptionsIntoGroup is not hosted") }
    override fun getSubscriptionsInGroup(groupUuid: android.os.ParcelUuid?, callingPackage: String?, callingFeatureId: String?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getSubscriptionsInGroup is not hosted")
    override fun getSlotIndex(subId: Int): Int = throw UnsupportedOperationException("ISub.getSlotIndex is not hosted")
    override fun getSubId(slotIndex: Int): Int = throw UnsupportedOperationException("ISub.getSubId is not hosted")
    override fun getDefaultSubId(): Int = throw UnsupportedOperationException("ISub.getDefaultSubId is not hosted")
    override fun getDefaultSubIdAsUser(userId: Int): Int = throw UnsupportedOperationException("ISub.getDefaultSubIdAsUser is not hosted")
    override fun getPhoneId(subId: Int): Int = throw UnsupportedOperationException("ISub.getPhoneId is not hosted")
    override fun getDefaultDataSubId(): Int = throw UnsupportedOperationException("ISub.getDefaultDataSubId is not hosted")
    override fun setDefaultDataSubId(subId: Int) { throw UnsupportedOperationException("ISub.setDefaultDataSubId is not hosted") }
    override fun getDefaultVoiceSubId(): Int = throw UnsupportedOperationException("ISub.getDefaultVoiceSubId is not hosted")
    override fun getDefaultVoiceSubIdAsUser(userId: Int): Int = throw UnsupportedOperationException("ISub.getDefaultVoiceSubIdAsUser is not hosted")
    override fun setDefaultVoiceSubId(subId: Int) { throw UnsupportedOperationException("ISub.setDefaultVoiceSubId is not hosted") }
    override fun getDefaultSmsSubId(): Int = throw UnsupportedOperationException("ISub.getDefaultSmsSubId is not hosted")
    override fun getDefaultSmsSubIdAsUser(userId: Int): Int = throw UnsupportedOperationException("ISub.getDefaultSmsSubIdAsUser is not hosted")
    override fun setDefaultSmsSubId(subId: Int) { throw UnsupportedOperationException("ISub.setDefaultSmsSubId is not hosted") }
    override fun getActiveSubIdList(visibleOnly: Boolean): IntArray? = throw UnsupportedOperationException("ISub.getActiveSubIdList is not hosted")
    override fun setSubscriptionProperty(subId: Int, propKey: String?, propValue: String?) { throw UnsupportedOperationException("ISub.setSubscriptionProperty is not hosted") }
    override fun getSubscriptionProperty(subId: Int, propKey: String?, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ISub.getSubscriptionProperty is not hosted")
    override fun isSubscriptionEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ISub.isSubscriptionEnabled is not hosted")
    override fun getEnabledSubscriptionId(slotIndex: Int): Int = throw UnsupportedOperationException("ISub.getEnabledSubscriptionId is not hosted")
    override fun isActiveSubId(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ISub.isActiveSubId is not hosted")
    override fun getActiveDataSubscriptionId(): Int = throw UnsupportedOperationException("ISub.getActiveDataSubscriptionId is not hosted")
    override fun canDisablePhysicalSubscription(): Boolean = throw UnsupportedOperationException("ISub.canDisablePhysicalSubscription is not hosted")
    override fun setUiccApplicationsEnabled(enabled: Boolean, subscriptionId: Int) { throw UnsupportedOperationException("ISub.setUiccApplicationsEnabled is not hosted") }
    override fun setDeviceToDeviceStatusSharing(sharing: Int, subId: Int): Int = throw UnsupportedOperationException("ISub.setDeviceToDeviceStatusSharing is not hosted")
    override fun setDeviceToDeviceStatusSharingContacts(contacts: String?, subscriptionId: Int): Int = throw UnsupportedOperationException("ISub.setDeviceToDeviceStatusSharingContacts is not hosted")
    override fun getPhoneNumber(subId: Int, source: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ISub.getPhoneNumber is not hosted")
    override fun getPhoneNumberFromFirstAvailableSource(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ISub.getPhoneNumberFromFirstAvailableSource is not hosted")
    override fun setPhoneNumber(subId: Int, source: Int, number: String?, callingPackage: String?, callingFeatureId: String?) { throw UnsupportedOperationException("ISub.setPhoneNumber is not hosted") }
    override fun setUsageSetting(usageSetting: Int, subId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ISub.setUsageSetting is not hosted")
    override fun setGroupOwner(subId: Int, groupOwner: String?) { throw UnsupportedOperationException("ISub.setGroupOwner is not hosted") }
    override fun setSubscriptionUserHandle(userHandle: android.os.UserHandle?, subId: Int): Int = throw UnsupportedOperationException("ISub.setSubscriptionUserHandle is not hosted")
    override fun getSubscriptionUserHandle(subId: Int): android.os.UserHandle? = throw UnsupportedOperationException("ISub.getSubscriptionUserHandle is not hosted")
    override fun isSubscriptionAssociatedWithCallingUser(subscriptionId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ISub.isSubscriptionAssociatedWithCallingUser is not hosted")
    override fun isSubscriptionAssociatedWithUser(subscriptionId: Int, userHandle: android.os.UserHandle?): Boolean = throw UnsupportedOperationException("ISub.isSubscriptionAssociatedWithUser is not hosted")
    override fun getSubscriptionInfoListAssociatedWithUser(userHandle: android.os.UserHandle?): MutableList<android.telephony.SubscriptionInfo?>? = throw UnsupportedOperationException("ISub.getSubscriptionInfoListAssociatedWithUser is not hosted")
    override fun restoreAllSimSpecificSettingsFromBackup(data: ByteArray?) { throw UnsupportedOperationException("ISub.restoreAllSimSpecificSettingsFromBackup is not hosted") }
    override fun setTransferStatus(subId: Int, status: Int) { throw UnsupportedOperationException("ISub.setTransferStatus is not hosted") }
}
