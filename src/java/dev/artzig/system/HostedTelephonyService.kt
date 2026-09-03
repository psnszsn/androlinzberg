package dev.artzig.system

import android.content.ComponentName
import android.content.Intent
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.UiccCardInfo
import android.telephony.UiccSlotInfo
import android.telephony.UiccSlotMapping
import android.telephony.VisualVoicemailSmsFilterSettings

/**
 * The session's ITelephony over the real AIDL surface, published as "phone".
 *
 * Source note: `com.android.phone.PhoneInterfaceManager` for a device whose modem
 * stack never came up: every `getPhone(subId)` is null, so the getters take the
 * branches AOSP takes for a missing phone (SIM absent, phone type none, network
 * type unknown, data disconnected, radio unavailable, no carrier privileges, no
 * emergency numbers, no identifiers). Permission checks follow
 * TelephonyPermissions: device identifiers require the privileged phone-state
 * permission; getters AOSP guards with the no-throw phone-state check return
 * their default instead. Anything that would drive the radio (calls, SIM I/O,
 * network selection, modem power) stays unserved.
 */
class HostedTelephonyService(private val permissions: HostedTelephonyPermissions) : com.android.internal.telephony.ITelephony.Stub(), com.android.internal.telephony.ITelephony by unsupported<com.android.internal.telephony.ITelephony>() {
    override fun asBinder(): android.os.IBinder = this

    // ------------------------------------------------------------ SIM, slots, identifiers

    override fun getSimStateForSlotIndex(slotIndex: Int): Int = TelephonyManager.SIM_STATE_ABSENT
    override fun hasIccCardUsingSlotIndex(slotIndex: Int): Boolean = false
    override fun getActivePhoneTypeForSlot(slotIndex: Int): Int = TelephonyManager.PHONE_TYPE_NONE
    override fun isModemEnabledForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun getUiccCardsInfo(callingPackage: String?): MutableList<UiccCardInfo?>? {
        permissions.enforcePrivilegedPhoneState("getUiccCardsInfo")
        return ArrayList()
    }
    override fun getUiccSlotsInfo(callingPackage: String?): Array<UiccSlotInfo?>? {
        permissions.enforcePrivilegedPhoneState("getUiccSlotsInfo")
        return arrayOfNulls(0)
    }
    override fun getSlotsMapping(callingPackage: String?): MutableList<UiccSlotMapping?>? {
        permissions.enforcePrivilegedPhoneState("getSlotsMapping")
        return ArrayList()
    }
    override fun getCardIdForDefaultEuicc(subId: Int, callingPackage: String?): Int = TelephonyManager.UNSUPPORTED_CARD_ID
    override fun getSimLocaleForSubscriber(subId: Int): String? = null
    override fun getDeviceIdWithFeature(callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceDeviceIdentifiers(callingPackage, "getDeviceId")
        return null
    }
    override fun getImeiForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceDeviceIdentifiers(callingPackage, "getImeiForSlot")
        return null
    }
    /** PhoneInterfaceManager.getPrimaryImei: no IMEI at all is an UnsupportedOperationException. */
    override fun getPrimaryImei(callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceDeviceIdentifiers(callingPackage, "getPrimaryImei")
        throw UnsupportedOperationException("Primary IMEI is not available")
    }
    override fun getMeidForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceDeviceIdentifiers(callingPackage, "getMeidForSlot")
        return null
    }
    override fun getDeviceSoftwareVersionForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? = null
    override fun getLine1NumberForDisplay(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = null
    override fun getLine1AlphaTagForDisplay(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = null
    override fun getMergedSubscriberIds(subId: Int, callingPackage: String?, callingFeatureId: String?): Array<String?>? = null
    override fun getCdmaMdn(subId: Int): String? {
        permissions.enforceModifyPhoneState("getCdmaMdn")
        return null
    }
    override fun getCdmaMin(subId: Int): String? {
        permissions.enforceModifyPhoneState("getCdmaMin")
        return null
    }
    override fun getCdmaEriIconIndexForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = -1
    override fun getCdmaEriIconModeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = -1
    override fun getCdmaEriTextForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = null
    override fun getLteOnCdmaModeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = com.android.internal.telephony.PhoneConstants.LTE_ON_CDMA_UNKNOWN

    // ------------------------------------------------------------ network and radio state

    override fun getNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    override fun getDataNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    override fun getVoiceNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    override fun getNetworkCountryIsoForPhone(phoneId: Int): String? = ""
    override fun getServiceStateForSlot(slotIndex: Int, renounceFineLocationAccess: Boolean, renounceCoarseLocationAccess: Boolean, callingPackage: String?, callingFeatureId: String?): ServiceState? = null
    override fun getSignalStrength(subId: Int): SignalStrength? = null
    override fun getAllCellInfo(callingPkg: String?, callingFeatureId: String?): MutableList<CellInfo?>? = ArrayList()
    override fun getCellLocation(callingPkg: String?, callingFeatureId: String?): CellIdentity? = null
    override fun getRadioPowerState(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): Int = TelephonyManager.RADIO_POWER_UNAVAILABLE
    override fun isRadioOnWithFeature(callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun isRadioOnForSubscriberWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun getRadioAccessFamily(phoneId: Int, callingPackage: String?): Int = TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN.toInt()
    override fun getNetworkSelectionMode(subId: Int): Int = TelephonyManager.NETWORK_SELECTION_MODE_UNKNOWN
    override fun isManualNetworkSelectionAllowed(subId: Int): Boolean = false
    override fun getEquivalentHomePlmns(subId: Int, callingPackage: String?, callingFeatureId: String?): MutableList<String?>? = ArrayList()
    override fun isRadioInterfaceCapabilitySupported(capability: String?): Boolean = false
    override fun isNrDualConnectivityEnabled(subId: Int): Boolean = false
    override fun getEmergencyCallbackMode(subId: Int): Boolean = false
    override fun isInEmergencySmsMode(): Boolean = false
    override fun isConcurrentVoiceAndDataAllowed(subId: Int): Boolean = false

    // ------------------------------------------------------------ calls

    override fun getCallState(): Int = TelephonyManager.CALL_STATE_IDLE
    override fun getCallStateForSubscription(subId: Int, callingPackage: String?, featureId: String?): Int = TelephonyManager.CALL_STATE_IDLE
    override fun isVideoCallingEnabled(callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun canChangeDtmfToneLength(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun isWorldPhone(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = false
    override fun isTtyModeSupported(): Boolean = false
    override fun isRttSupported(subscriptionId: Int): Boolean = false
    override fun isHearingAidCompatibilitySupported(): Boolean = false
    override fun getVoiceMessageCountForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = 0
    override fun getVisualVoicemailPackageName(callingPackage: String?, callingFeatureId: String?, subId: Int): String? {
        permissions.enforceReadPhoneState("getVisualVoicemailPackageName")
        return null
    }
    override fun getActiveVisualVoicemailSmsFilterSettings(subId: Int): VisualVoicemailSmsFilterSettings? = null
    override fun getVoiceActivationState(subId: Int, callingPackage: String?): Int = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN
    override fun getDataActivationState(subId: Int, callingPackage: String?): Int = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN
    override fun isEmergencyNumber(number: String?, exactMatch: Boolean): Boolean = false
    override fun getEmergencyNumberList(callingPackage: String?, callingFeatureId: String?): MutableMap<*, *>? = HashMap<Int, List<Any>>()
    override fun getDefaultRespondViaMessageApplication(subId: Int, updateIfNeeded: Boolean): ComponentName? = null

    // ------------------------------------------------------------ data

    override fun getDataStateForSubId(subId: Int): Int = TelephonyManager.DATA_DISCONNECTED
    override fun getDataActivityForSubId(subId: Int): Int = TelephonyManager.DATA_ACTIVITY_NONE
    override fun getDataEnabled(subId: Int): Boolean = false
    override fun isDataEnabled(subId: Int): Boolean = false
    override fun isUserDataEnabled(subId: Int): Boolean = false
    override fun isDataEnabledForReason(subId: Int, reason: Int): Boolean = false
    override fun isDataEnabledForApn(apnType: Int, subId: Int, callingPackage: String?): Boolean = false
    override fun isDataConnectivityPossible(subId: Int): Boolean = false
    override fun isDataRoamingEnabled(subId: Int): Boolean = false
    override fun isTetheringApnRequiredForSubscriber(subId: Int): Boolean = false
    override fun isMobileDataPolicyEnabled(subscriptionId: Int, policy: Int): Boolean = false
    override fun getNumberOfModemsWithSimultaneousDataConnections(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = 0
    override fun getMmsUserAgent(subId: Int): String? = ""
    override fun getMmsUAProfUrl(subId: Int): String? = ""

    // ------------------------------------------------------------ carrier

    override fun getCarrierPrivilegeStatus(subId: Int): Int = TelephonyManager.CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED
    override fun getCarrierPrivilegeStatusForUid(subId: Int, uid: Int): Int = TelephonyManager.CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED
    override fun checkCarrierPrivilegesForPackage(subId: Int, pkgName: String?): Int = TelephonyManager.CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED
    override fun checkCarrierPrivilegesForPackageAnyPhone(pkgName: String?): Int = TelephonyManager.CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED
    override fun getPackagesWithCarrierPrivileges(phoneId: Int): MutableList<String?>? = ArrayList()
    override fun getPackagesWithCarrierPrivilegesForAllPhones(): MutableList<String?>? = ArrayList()
    override fun getCarrierPackageNamesForIntentAndPhone(intent: Intent?, phoneId: Int): MutableList<String?>? = null
    override fun getSubscriptionCarrierId(subId: Int): Int = TelephonyManager.UNKNOWN_CARRIER_ID
    override fun getSubscriptionCarrierName(subId: Int): String? = null
    override fun getSubscriptionSpecificCarrierId(subId: Int): Int = TelephonyManager.UNKNOWN_CARRIER_ID
    override fun getSubscriptionSpecificCarrierName(subId: Int): String? = null
    override fun getCarrierIdFromMccMnc(slotIndex: Int, mccmnc: String?, isSubscriptionMccMnc: Boolean): Int = TelephonyManager.UNKNOWN_CARRIER_ID
    override fun getCarrierIdListVersion(subId: Int): Int = TelephonyManager.UNKNOWN_CARRIER_ID_LIST_VERSION
    override fun getSubIdForPhoneAccountHandle(phoneAccountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    override fun getPhoneAccountHandleForSubscriptionId(subscriptionId: Int): android.telecom.PhoneAccountHandle? = null
}
