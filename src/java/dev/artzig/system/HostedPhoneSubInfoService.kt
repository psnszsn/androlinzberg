package dev.artzig.system

import android.net.Uri
import android.telephony.ImsiEncryptionInfo

/**
 * The session's IPhoneSubInfo over the real AIDL surface, published as
 * "iphonesubinfo".
 *
 * Source note: `com.android.internal.telephony.PhoneSubInfoController` with no
 * phone objects: every subscriber-identity getter is null once its permission
 * check passes. Device identifiers keep the privileged-caller gate (Android 10+),
 * line numbers keep the phone-number gate, the rest keep READ_PHONE_STATE or the
 * privileged permission as AOSP annotates them.
 */
class HostedPhoneSubInfoService(private val permissions: HostedTelephonyPermissions) : com.android.internal.telephony.IPhoneSubInfo.Stub(), com.android.internal.telephony.IPhoneSubInfo by unsupported<com.android.internal.telephony.IPhoneSubInfo>() {
    override fun asBinder(): android.os.IBinder = this

    private fun identifier(callingPackage: String?, what: String): String? {
        permissions.enforceDeviceIdentifiers(callingPackage, what)
        return null
    }
    private fun number(what: String): String? {
        permissions.enforceReadPhoneNumber(what)
        return null
    }
    private fun phoneState(what: String): String? {
        permissions.enforceReadPhoneState(what)
        return null
    }
    private fun privileged(what: String): String? {
        permissions.enforcePrivilegedPhoneState(what)
        return null
    }

    override fun getDeviceId(callingPackage: String?): String? = identifier(callingPackage, "getDeviceId")
    override fun getDeviceIdWithFeature(callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getDeviceId")
    override fun getDeviceIdForPhone(phoneId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getDeviceId")
    override fun getImeiForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getImei")
    override fun getNaiForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getNai")
    override fun getDeviceSvn(callingPackage: String?, callingFeatureId: String?): String? = phoneState("getDeviceSvn")
    override fun getDeviceSvnUsingSubId(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = phoneState("getDeviceSvn")
    override fun getSubscriberId(callingPackage: String?): String? = identifier(callingPackage, "getSubscriberId")
    override fun getSubscriberIdWithFeature(callingPackage: String?, callingComponenId: String?): String? = identifier(callingPackage, "getSubscriberId")
    override fun getSubscriberIdForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getSubscriberId")
    override fun getGroupIdLevel1ForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getGroupIdLevel1")
    override fun getGroupIdLevel2ForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getGroupIdLevel2")
    override fun getIccSerialNumber(callingPackage: String?): String? = identifier(callingPackage, "getIccSerialNumber")
    override fun getIccSerialNumberWithFeature(callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getIccSerialNumber")
    override fun getIccSerialNumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = identifier(callingPackage, "getIccSerialNumber")
    override fun getLine1Number(callingPackage: String?, callingFeatureId: String?): String? = number("getLine1Number")
    override fun getLine1NumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = number("getLine1Number")
    override fun getLine1AlphaTag(callingPackage: String?, callingFeatureId: String?): String? = phoneState("getLine1AlphaTag")
    override fun getLine1AlphaTagForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = phoneState("getLine1AlphaTag")
    override fun getMsisdn(callingPackage: String?, callingFeatureId: String?): String? = number("getMsisdn")
    override fun getMsisdnForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = number("getMsisdn")
    override fun getVoiceMailNumber(callingPackage: String?, callingFeatureId: String?): String? = phoneState("getVoiceMailNumber")
    override fun getVoiceMailNumberForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = phoneState("getVoiceMailNumber")
    override fun getVoiceMailAlphaTag(callingPackage: String?, callingFeatureId: String?): String? = phoneState("getVoiceMailAlphaTag")
    override fun getVoiceMailAlphaTagForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = phoneState("getVoiceMailAlphaTag")
    override fun getCarrierInfoForImsiEncryption(subId: Int, keyType: Int, callingPackage: String?): ImsiEncryptionInfo? = null.also { permissions.enforcePrivilegedPhoneState("getCarrierInfoForImsiEncryption") }
    override fun getIsimImpi(subId: Int): String? = privileged("getIsimImpi")
    override fun getImsPrivateUserIdentity(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = privileged("getImsPrivateUserIdentity")
    override fun getIsimDomain(subId: Int): String? = privileged("getIsimDomain")
    override fun getIsimImpu(subId: Int): Array<String?>? {
        permissions.enforcePrivilegedPhoneState("getIsimImpu")
        return null
    }
    override fun getImsPublicUserIdentities(subId: Int, callingPackage: String?): MutableList<Uri?>? {
        permissions.enforcePrivilegedPhoneState("getImsPublicUserIdentities")
        return ArrayList()
    }
    override fun getIsimIst(subId: Int): String? = privileged("getIsimIst")
    override fun getIsimPcscf(subId: Int): Array<String?>? {
        permissions.enforcePrivilegedPhoneState("getIsimPcscf")
        return null
    }
    override fun getImsPcscfAddresses(subId: Int, callingPackage: String?): MutableList<String?>? {
        permissions.enforcePrivilegedPhoneState("getImsPcscfAddresses")
        return ArrayList()
    }
    override fun getIccSimChallengeResponse(subId: Int, appType: Int, authType: Int, data: String?, callingPackage: String?, callingFeatureId: String?): String? = privileged("getIccSimChallengeResponse")
    override fun getSmscIdentity(subId: Int, appType: Int): Uri? {
        permissions.enforcePrivilegedPhoneState("getSmscIdentity")
        return null
    }
    override fun getSimServiceTable(subId: Int, appType: Int): String? = privileged("getSimServiceTable")
}
