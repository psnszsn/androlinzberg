@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IPhoneSubInfo method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedPhoneSubInfo : com.android.internal.telephony.IPhoneSubInfo.Stub() {
    override fun getDeviceId(callingPackage: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getDeviceId is not hosted")
    override fun getDeviceIdWithFeature(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getDeviceIdWithFeature is not hosted")
    override fun getNaiForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getNaiForSubscriber is not hosted")
    override fun getDeviceIdForPhone(phoneId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getDeviceIdForPhone is not hosted")
    override fun getImeiForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getImeiForSubscriber is not hosted")
    override fun getDeviceSvn(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getDeviceSvn is not hosted")
    override fun getDeviceSvnUsingSubId(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getDeviceSvnUsingSubId is not hosted")
    override fun getSubscriberId(callingPackage: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getSubscriberId is not hosted")
    override fun getSubscriberIdWithFeature(callingPackage: String?, callingComponenId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getSubscriberIdWithFeature is not hosted")
    override fun getSubscriberIdForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getSubscriberIdForSubscriber is not hosted")
    override fun getGroupIdLevel1ForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getGroupIdLevel1ForSubscriber is not hosted")
    override fun getGroupIdLevel2ForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getGroupIdLevel2ForSubscriber is not hosted")
    override fun getIccSerialNumber(callingPackage: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIccSerialNumber is not hosted")
    override fun getIccSerialNumberWithFeature(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIccSerialNumberWithFeature is not hosted")
    override fun getIccSerialNumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIccSerialNumberForSubscriber is not hosted")
    override fun getLine1Number(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getLine1Number is not hosted")
    override fun getLine1NumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getLine1NumberForSubscriber is not hosted")
    override fun getLine1AlphaTag(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getLine1AlphaTag is not hosted")
    override fun getLine1AlphaTagForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getLine1AlphaTagForSubscriber is not hosted")
    override fun getMsisdn(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getMsisdn is not hosted")
    override fun getMsisdnForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getMsisdnForSubscriber is not hosted")
    override fun getVoiceMailNumber(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getVoiceMailNumber is not hosted")
    override fun getVoiceMailNumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getVoiceMailNumberForSubscriber is not hosted")
    override fun getCarrierInfoForImsiEncryption(subId: Int, keyType: Int, callingPackage: String?): android.telephony.ImsiEncryptionInfo? = throw UnsupportedOperationException("IPhoneSubInfo.getCarrierInfoForImsiEncryption is not hosted")
    override fun setCarrierInfoForImsiEncryption(subId: Int, callingPackage: String?, imsiEncryptionInfo: android.telephony.ImsiEncryptionInfo?) { throw UnsupportedOperationException("IPhoneSubInfo.setCarrierInfoForImsiEncryption is not hosted") }
    override fun resetCarrierKeysForImsiEncryption(subId: Int, callingPackage: String?) { throw UnsupportedOperationException("IPhoneSubInfo.resetCarrierKeysForImsiEncryption is not hosted") }
    override fun getVoiceMailAlphaTag(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getVoiceMailAlphaTag is not hosted")
    override fun getVoiceMailAlphaTagForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getVoiceMailAlphaTagForSubscriber is not hosted")
    override fun getIsimImpi(subId: Int): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIsimImpi is not hosted")
    override fun getImsPrivateUserIdentity(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getImsPrivateUserIdentity is not hosted")
    override fun getIsimDomain(subId: Int): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIsimDomain is not hosted")
    override fun getIsimImpu(subId: Int): Array<String?>? = throw UnsupportedOperationException("IPhoneSubInfo.getIsimImpu is not hosted")
    override fun getImsPublicUserIdentities(subId: Int, callingPackage: String?): MutableList<android.net.Uri?>? = throw UnsupportedOperationException("IPhoneSubInfo.getImsPublicUserIdentities is not hosted")
    override fun getIsimIst(subId: Int): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIsimIst is not hosted")
    override fun getIsimPcscf(subId: Int): Array<String?>? = throw UnsupportedOperationException("IPhoneSubInfo.getIsimPcscf is not hosted")
    override fun getImsPcscfAddresses(subId: Int, callingPackage: String?): MutableList<String?>? = throw UnsupportedOperationException("IPhoneSubInfo.getImsPcscfAddresses is not hosted")
    override fun getIccSimChallengeResponse(subId: Int, appType: Int, authType: Int, data: String?, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("IPhoneSubInfo.getIccSimChallengeResponse is not hosted")
    override fun getSmscIdentity(subId: Int, appType: Int): android.net.Uri? = throw UnsupportedOperationException("IPhoneSubInfo.getSmscIdentity is not hosted")
    override fun getSimServiceTable(subId: Int, appType: Int): String? = throw UnsupportedOperationException("IPhoneSubInfo.getSimServiceTable is not hosted")
}
