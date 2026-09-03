@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ITelephony method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedTelephony : com.android.internal.telephony.ITelephony.Stub() {
    override fun dial(number: String?) { throw UnsupportedOperationException("ITelephony.dial is not hosted") }
    override fun call(callingPackage: String?, number: String?) { throw UnsupportedOperationException("ITelephony.call is not hosted") }
    override fun isRadioOn(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRadioOn is not hosted")
    override fun isRadioOnWithFeature(callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRadioOnWithFeature is not hosted")
    override fun isRadioOnForSubscriber(subId: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRadioOnForSubscriber is not hosted")
    override fun isRadioOnForSubscriberWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRadioOnForSubscriberWithFeature is not hosted")
    override fun setCallComposerStatus(subId: Int, status: Int) { throw UnsupportedOperationException("ITelephony.setCallComposerStatus is not hosted") }
    override fun getCallComposerStatus(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getCallComposerStatus is not hosted")
    override fun supplyPinForSubscriber(subId: Int, pin: String?): Boolean = throw UnsupportedOperationException("ITelephony.supplyPinForSubscriber is not hosted")
    override fun supplyPukForSubscriber(subId: Int, puk: String?, pin: String?): Boolean = throw UnsupportedOperationException("ITelephony.supplyPukForSubscriber is not hosted")
    override fun supplyPinReportResultForSubscriber(subId: Int, pin: String?): IntArray? = throw UnsupportedOperationException("ITelephony.supplyPinReportResultForSubscriber is not hosted")
    override fun supplyPukReportResultForSubscriber(subId: Int, puk: String?, pin: String?): IntArray? = throw UnsupportedOperationException("ITelephony.supplyPukReportResultForSubscriber is not hosted")
    override fun handlePinMmi(dialString: String?): Boolean = throw UnsupportedOperationException("ITelephony.handlePinMmi is not hosted")
    override fun handleUssdRequest(subId: Int, ussdRequest: String?, wrappedCallback: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.handleUssdRequest is not hosted") }
    override fun handlePinMmiForSubscriber(subId: Int, dialString: String?): Boolean = throw UnsupportedOperationException("ITelephony.handlePinMmiForSubscriber is not hosted")
    override fun toggleRadioOnOff() { throw UnsupportedOperationException("ITelephony.toggleRadioOnOff is not hosted") }
    override fun toggleRadioOnOffForSubscriber(subId: Int) { throw UnsupportedOperationException("ITelephony.toggleRadioOnOffForSubscriber is not hosted") }
    override fun setRadio(turnOn: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setRadio is not hosted")
    override fun setRadioForSubscriber(subId: Int, turnOn: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setRadioForSubscriber is not hosted")
    override fun setRadioPower(turnOn: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setRadioPower is not hosted")
    override fun requestRadioPowerOffForReason(subId: Int, reason: Int): Boolean = throw UnsupportedOperationException("ITelephony.requestRadioPowerOffForReason is not hosted")
    override fun clearRadioPowerOffForReason(subId: Int, reason: Int): Boolean = throw UnsupportedOperationException("ITelephony.clearRadioPowerOffForReason is not hosted")
    override fun getRadioPowerOffReasons(subId: Int, callingPackage: String?, callingFeatureId: String?): MutableList<*>? = throw UnsupportedOperationException("ITelephony.getRadioPowerOffReasons is not hosted")
    override fun updateServiceLocation() { throw UnsupportedOperationException("ITelephony.updateServiceLocation is not hosted") }
    override fun updateServiceLocationWithPackageName(callingPkg: String?) { throw UnsupportedOperationException("ITelephony.updateServiceLocationWithPackageName is not hosted") }
    override fun enableLocationUpdates() { throw UnsupportedOperationException("ITelephony.enableLocationUpdates is not hosted") }
    override fun disableLocationUpdates() { throw UnsupportedOperationException("ITelephony.disableLocationUpdates is not hosted") }
    override fun enableDataConnectivity(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.enableDataConnectivity is not hosted")
    override fun disableDataConnectivity(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.disableDataConnectivity is not hosted")
    override fun isDataConnectivityPossible(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isDataConnectivityPossible is not hosted")
    override fun getCellLocation(callingPkg: String?, callingFeatureId: String?): android.telephony.CellIdentity? = throw UnsupportedOperationException("ITelephony.getCellLocation is not hosted")
    override fun getNetworkCountryIsoForPhone(phoneId: Int): String? = throw UnsupportedOperationException("ITelephony.getNetworkCountryIsoForPhone is not hosted")
    override fun getNeighboringCellInfo(callingPkg: String?, callingFeatureId: String?): MutableList<android.telephony.NeighboringCellInfo?>? = throw UnsupportedOperationException("ITelephony.getNeighboringCellInfo is not hosted")
    override fun getCallState(): Int = throw UnsupportedOperationException("ITelephony.getCallState is not hosted")
    override fun getCallStateForSubscription(subId: Int, callingPackage: String?, featureId: String?): Int = throw UnsupportedOperationException("ITelephony.getCallStateForSubscription is not hosted")
    override fun getDataActivity(): Int = throw UnsupportedOperationException("ITelephony.getDataActivity is not hosted")
    override fun getDataActivityForSubId(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getDataActivityForSubId is not hosted")
    override fun getDataState(): Int = throw UnsupportedOperationException("ITelephony.getDataState is not hosted")
    override fun getDataStateForSubId(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getDataStateForSubId is not hosted")
    override fun getActivePhoneType(): Int = throw UnsupportedOperationException("ITelephony.getActivePhoneType is not hosted")
    override fun getActivePhoneTypeForSlot(slotIndex: Int): Int = throw UnsupportedOperationException("ITelephony.getActivePhoneTypeForSlot is not hosted")
    override fun getCdmaEriIconIndex(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getCdmaEriIconIndex is not hosted")
    override fun getCdmaEriIconIndexForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getCdmaEriIconIndexForSubscriber is not hosted")
    override fun getCdmaEriIconMode(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getCdmaEriIconMode is not hosted")
    override fun getCdmaEriIconModeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getCdmaEriIconModeForSubscriber is not hosted")
    override fun getCdmaEriText(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getCdmaEriText is not hosted")
    override fun getCdmaEriTextForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getCdmaEriTextForSubscriber is not hosted")
    override fun needsOtaServiceProvisioning(): Boolean = throw UnsupportedOperationException("ITelephony.needsOtaServiceProvisioning is not hosted")
    override fun setVoiceMailNumber(subId: Int, alphaTag: String?, number: String?): Boolean = throw UnsupportedOperationException("ITelephony.setVoiceMailNumber is not hosted")
    override fun setVoiceActivationState(subId: Int, activationState: Int) { throw UnsupportedOperationException("ITelephony.setVoiceActivationState is not hosted") }
    override fun setDataActivationState(subId: Int, activationState: Int) { throw UnsupportedOperationException("ITelephony.setDataActivationState is not hosted") }
    override fun getVoiceActivationState(subId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ITelephony.getVoiceActivationState is not hosted")
    override fun getDataActivationState(subId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ITelephony.getDataActivationState is not hosted")
    override fun getVoiceMessageCountForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getVoiceMessageCountForSubscriber is not hosted")
    override fun isConcurrentVoiceAndDataAllowed(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isConcurrentVoiceAndDataAllowed is not hosted")
    override fun getVisualVoicemailSettings(callingPackage: String?, subId: Int): android.os.Bundle? = throw UnsupportedOperationException("ITelephony.getVisualVoicemailSettings is not hosted")
    override fun getVisualVoicemailPackageName(callingPackage: String?, callingFeatureId: String?, subId: Int): String? = throw UnsupportedOperationException("ITelephony.getVisualVoicemailPackageName is not hosted")
    override fun enableVisualVoicemailSmsFilter(callingPackage: String?, subId: Int, settings: android.telephony.VisualVoicemailSmsFilterSettings?) { throw UnsupportedOperationException("ITelephony.enableVisualVoicemailSmsFilter is not hosted") }
    override fun disableVisualVoicemailSmsFilter(callingPackage: String?, subId: Int) { throw UnsupportedOperationException("ITelephony.disableVisualVoicemailSmsFilter is not hosted") }
    override fun getVisualVoicemailSmsFilterSettings(callingPackage: String?, subId: Int): android.telephony.VisualVoicemailSmsFilterSettings? = throw UnsupportedOperationException("ITelephony.getVisualVoicemailSmsFilterSettings is not hosted")
    override fun getActiveVisualVoicemailSmsFilterSettings(subId: Int): android.telephony.VisualVoicemailSmsFilterSettings? = throw UnsupportedOperationException("ITelephony.getActiveVisualVoicemailSmsFilterSettings is not hosted")
    override fun sendVisualVoicemailSmsForSubscriber(callingPackage: String?, callingAttributeTag: String?, subId: Int, number: String?, port: Int, text: String?, sentIntent: android.app.PendingIntent?) { throw UnsupportedOperationException("ITelephony.sendVisualVoicemailSmsForSubscriber is not hosted") }
    override fun sendDialerSpecialCode(callingPackageName: String?, inputCode: String?) { throw UnsupportedOperationException("ITelephony.sendDialerSpecialCode is not hosted") }
    override fun getNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getNetworkTypeForSubscriber is not hosted")
    override fun getDataNetworkType(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getDataNetworkType is not hosted")
    override fun getDataNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getDataNetworkTypeForSubscriber is not hosted")
    override fun getVoiceNetworkTypeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getVoiceNetworkTypeForSubscriber is not hosted")
    override fun hasIccCard(): Boolean = throw UnsupportedOperationException("ITelephony.hasIccCard is not hosted")
    override fun hasIccCardUsingSlotIndex(slotIndex: Int): Boolean = throw UnsupportedOperationException("ITelephony.hasIccCardUsingSlotIndex is not hosted")
    override fun getLteOnCdmaMode(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getLteOnCdmaMode is not hosted")
    override fun getLteOnCdmaModeForSubscriber(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getLteOnCdmaModeForSubscriber is not hosted")
    override fun getAllCellInfo(callingPkg: String?, callingFeatureId: String?): MutableList<android.telephony.CellInfo?>? = throw UnsupportedOperationException("ITelephony.getAllCellInfo is not hosted")
    override fun requestCellInfoUpdate(subId: Int, cb: android.telephony.ICellInfoCallback?, callingPkg: String?, callingFeatureId: String?) { throw UnsupportedOperationException("ITelephony.requestCellInfoUpdate is not hosted") }
    override fun requestCellInfoUpdateWithWorkSource(subId: Int, cb: android.telephony.ICellInfoCallback?, callingPkg: String?, callingFeatureId: String?, ws: android.os.WorkSource?) { throw UnsupportedOperationException("ITelephony.requestCellInfoUpdateWithWorkSource is not hosted") }
    override fun setCellInfoListRate(rateInMillis: Int, subId: Int) { throw UnsupportedOperationException("ITelephony.setCellInfoListRate is not hosted") }
    override fun iccOpenLogicalChannel(request: com.android.internal.telephony.IccLogicalChannelRequest?): android.telephony.IccOpenLogicalChannelResponse? = throw UnsupportedOperationException("ITelephony.iccOpenLogicalChannel is not hosted")
    override fun iccCloseLogicalChannel(request: com.android.internal.telephony.IccLogicalChannelRequest?): Boolean = throw UnsupportedOperationException("ITelephony.iccCloseLogicalChannel is not hosted")
    override fun iccTransmitApduLogicalChannelByPort(slotIndex: Int, portIndex: Int, channel: Int, cla: Int, instruction: Int, p1: Int, p2: Int, p3: Int, data: String?): String? = throw UnsupportedOperationException("ITelephony.iccTransmitApduLogicalChannelByPort is not hosted")
    override fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, instruction: Int, p1: Int, p2: Int, p3: Int, data: String?): String? = throw UnsupportedOperationException("ITelephony.iccTransmitApduLogicalChannel is not hosted")
    override fun iccTransmitApduBasicChannelByPort(slotIndex: Int, portIndex: Int, callingPackage: String?, cla: Int, instruction: Int, p1: Int, p2: Int, p3: Int, data: String?): String? = throw UnsupportedOperationException("ITelephony.iccTransmitApduBasicChannelByPort is not hosted")
    override fun iccTransmitApduBasicChannel(subId: Int, callingPackage: String?, cla: Int, instruction: Int, p1: Int, p2: Int, p3: Int, data: String?): String? = throw UnsupportedOperationException("ITelephony.iccTransmitApduBasicChannel is not hosted")
    override fun iccExchangeSimIO(subId: Int, fileID: Int, command: Int, p1: Int, p2: Int, p3: Int, filePath: String?): ByteArray? = throw UnsupportedOperationException("ITelephony.iccExchangeSimIO is not hosted")
    override fun sendEnvelopeWithStatus(subId: Int, content: String?): String? = throw UnsupportedOperationException("ITelephony.sendEnvelopeWithStatus is not hosted")
    override fun nvReadItem(itemID: Int): String? = throw UnsupportedOperationException("ITelephony.nvReadItem is not hosted")
    override fun nvWriteItem(itemID: Int, itemValue: String?): Boolean = throw UnsupportedOperationException("ITelephony.nvWriteItem is not hosted")
    override fun nvWriteCdmaPrl(preferredRoamingList: ByteArray?): Boolean = throw UnsupportedOperationException("ITelephony.nvWriteCdmaPrl is not hosted")
    override fun resetModemConfig(slotIndex: Int): Boolean = throw UnsupportedOperationException("ITelephony.resetModemConfig is not hosted")
    override fun rebootModem(slotIndex: Int): Boolean = throw UnsupportedOperationException("ITelephony.rebootModem is not hosted")
    override fun getAllowedNetworkTypesBitmask(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getAllowedNetworkTypesBitmask is not hosted")
    override fun isTetheringApnRequiredForSubscriber(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isTetheringApnRequiredForSubscriber is not hosted")
    override fun enableIms(slotId: Int) { throw UnsupportedOperationException("ITelephony.enableIms is not hosted") }
    override fun disableIms(slotId: Int) { throw UnsupportedOperationException("ITelephony.disableIms is not hosted") }
    override fun resetIms(slotIndex: Int) { throw UnsupportedOperationException("ITelephony.resetIms is not hosted") }
    override fun registerMmTelFeatureCallback(slotId: Int, callback: com.android.ims.internal.IImsServiceFeatureCallback?) { throw UnsupportedOperationException("ITelephony.registerMmTelFeatureCallback is not hosted") }
    override fun unregisterImsFeatureCallback(callback: com.android.ims.internal.IImsServiceFeatureCallback?) { throw UnsupportedOperationException("ITelephony.unregisterImsFeatureCallback is not hosted") }
    override fun getImsRegistration(slotId: Int, feature: Int): android.telephony.ims.aidl.IImsRegistration? = throw UnsupportedOperationException("ITelephony.getImsRegistration is not hosted")
    override fun getImsConfig(slotId: Int, feature: Int): android.telephony.ims.aidl.IImsConfig? = throw UnsupportedOperationException("ITelephony.getImsConfig is not hosted")
    override fun setBoundImsServiceOverride(slotIndex: Int, userId: Int, isCarrierService: Boolean, featureTypes: IntArray?, packageName: String?): Boolean = throw UnsupportedOperationException("ITelephony.setBoundImsServiceOverride is not hosted")
    override fun clearCarrierImsServiceOverride(slotIndex: Int): Boolean = throw UnsupportedOperationException("ITelephony.clearCarrierImsServiceOverride is not hosted")
    override fun getBoundImsServicePackage(slotIndex: Int, isCarrierImsService: Boolean, featureType: Int): String? = throw UnsupportedOperationException("ITelephony.getBoundImsServicePackage is not hosted")
    override fun getImsMmTelFeatureState(subId: Int, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.getImsMmTelFeatureState is not hosted") }
    override fun setNetworkSelectionModeAutomatic(subId: Int) { throw UnsupportedOperationException("ITelephony.setNetworkSelectionModeAutomatic is not hosted") }
    override fun getCellNetworkScanResults(subId: Int, callingPackage: String?, callingFeatureId: String?): com.android.internal.telephony.CellNetworkScanResult? = throw UnsupportedOperationException("ITelephony.getCellNetworkScanResults is not hosted")
    override fun requestNetworkScan(subId: Int, renounceFineLocationAccess: Boolean, request: android.telephony.NetworkScanRequest?, messenger: android.os.Messenger?, binder: android.os.IBinder?, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.requestNetworkScan is not hosted")
    override fun stopNetworkScan(subId: Int, scanId: Int) { throw UnsupportedOperationException("ITelephony.stopNetworkScan is not hosted") }
    override fun setNetworkSelectionModeManual(subId: Int, operatorInfo: com.android.internal.telephony.OperatorInfo?, persisSelection: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setNetworkSelectionModeManual is not hosted")
    override fun getAllowedNetworkTypesForReason(subId: Int, reason: Int): Long = throw UnsupportedOperationException("ITelephony.getAllowedNetworkTypesForReason is not hosted")
    override fun setAllowedNetworkTypesForReason(subId: Int, reason: Int, allowedNetworkTypes: Long): Boolean = throw UnsupportedOperationException("ITelephony.setAllowedNetworkTypesForReason is not hosted")
    override fun getDataEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.getDataEnabled is not hosted")
    override fun isUserDataEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isUserDataEnabled is not hosted")
    override fun isDataEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isDataEnabled is not hosted")
    override fun setDataEnabledForReason(subId: Int, reason: Int, enable: Boolean, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.setDataEnabledForReason is not hosted") }
    override fun isDataEnabledForReason(subId: Int, reason: Int): Boolean = throw UnsupportedOperationException("ITelephony.isDataEnabledForReason is not hosted")
    override fun isManualNetworkSelectionAllowed(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isManualNetworkSelectionAllowed is not hosted")
    override fun setImsRegistrationState(registered: Boolean) { throw UnsupportedOperationException("ITelephony.setImsRegistrationState is not hosted") }
    override fun getCdmaMdn(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getCdmaMdn is not hosted")
    override fun getCdmaMin(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getCdmaMin is not hosted")
    override fun requestNumberVerification(range: android.telephony.PhoneNumberRange?, timeoutMillis: Long, callback: com.android.internal.telephony.INumberVerificationCallback?, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.requestNumberVerification is not hosted") }
    override fun getCarrierPrivilegeStatus(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getCarrierPrivilegeStatus is not hosted")
    override fun getCarrierPrivilegeStatusForUid(subId: Int, uid: Int): Int = throw UnsupportedOperationException("ITelephony.getCarrierPrivilegeStatusForUid is not hosted")
    override fun checkCarrierPrivilegesForPackage(subId: Int, pkgName: String?): Int = throw UnsupportedOperationException("ITelephony.checkCarrierPrivilegesForPackage is not hosted")
    override fun checkCarrierPrivilegesForPackageAnyPhone(pkgName: String?): Int = throw UnsupportedOperationException("ITelephony.checkCarrierPrivilegesForPackageAnyPhone is not hosted")
    override fun getCarrierPackageNamesForIntentAndPhone(intent: android.content.Intent?, phoneId: Int): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getCarrierPackageNamesForIntentAndPhone is not hosted")
    override fun setLine1NumberForDisplayForSubscriber(subId: Int, alphaTag: String?, number: String?): Boolean = throw UnsupportedOperationException("ITelephony.setLine1NumberForDisplayForSubscriber is not hosted")
    override fun getLine1NumberForDisplay(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getLine1NumberForDisplay is not hosted")
    override fun getLine1AlphaTagForDisplay(subId: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getLine1AlphaTagForDisplay is not hosted")
    override fun getMergedSubscriberIds(subId: Int, callingPackage: String?, callingFeatureId: String?): Array<String?>? = throw UnsupportedOperationException("ITelephony.getMergedSubscriberIds is not hosted")
    override fun getMergedImsisFromGroup(subId: Int, callingPackage: String?): Array<String?>? = throw UnsupportedOperationException("ITelephony.getMergedImsisFromGroup is not hosted")
    override fun setOperatorBrandOverride(subId: Int, brand: String?): Boolean = throw UnsupportedOperationException("ITelephony.setOperatorBrandOverride is not hosted")
    override fun setRoamingOverride(subId: Int, gsmRoamingList: MutableList<String?>?, gsmNonRoamingList: MutableList<String?>?, cdmaRoamingList: MutableList<String?>?, cdmaNonRoamingList: MutableList<String?>?): Boolean = throw UnsupportedOperationException("ITelephony.setRoamingOverride is not hosted")
    override fun needMobileRadioShutdown(): Boolean = throw UnsupportedOperationException("ITelephony.needMobileRadioShutdown is not hosted")
    override fun shutdownMobileRadios() { throw UnsupportedOperationException("ITelephony.shutdownMobileRadios is not hosted") }
    override fun getRadioAccessFamily(phoneId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ITelephony.getRadioAccessFamily is not hosted")
    override fun uploadCallComposerPicture(subscriptionId: Int, callingPackage: String?, contentType: String?, fd: android.os.ParcelFileDescriptor?, callback: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.uploadCallComposerPicture is not hosted") }
    override fun enableVideoCalling(enable: Boolean) { throw UnsupportedOperationException("ITelephony.enableVideoCalling is not hosted") }
    override fun isVideoCallingEnabled(callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.isVideoCallingEnabled is not hosted")
    override fun canChangeDtmfToneLength(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.canChangeDtmfToneLength is not hosted")
    override fun isWorldPhone(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.isWorldPhone is not hosted")
    override fun isTtyModeSupported(): Boolean = throw UnsupportedOperationException("ITelephony.isTtyModeSupported is not hosted")
    override fun isRttSupported(subscriptionId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isRttSupported is not hosted")
    override fun isHearingAidCompatibilitySupported(): Boolean = throw UnsupportedOperationException("ITelephony.isHearingAidCompatibilitySupported is not hosted")
    override fun isImsRegistered(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isImsRegistered is not hosted")
    override fun isWifiCallingAvailable(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isWifiCallingAvailable is not hosted")
    override fun isVideoTelephonyAvailable(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isVideoTelephonyAvailable is not hosted")
    override fun getImsRegTechnologyForMmTel(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getImsRegTechnologyForMmTel is not hosted")
    override fun getDeviceId(callingPackage: String?): String? = throw UnsupportedOperationException("ITelephony.getDeviceId is not hosted")
    override fun getDeviceIdWithFeature(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getDeviceIdWithFeature is not hosted")
    override fun getImeiForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getImeiForSlot is not hosted")
    override fun getPrimaryImei(callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getPrimaryImei is not hosted")
    override fun getTypeAllocationCodeForSlot(slotIndex: Int): String? = throw UnsupportedOperationException("ITelephony.getTypeAllocationCodeForSlot is not hosted")
    override fun getMeidForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getMeidForSlot is not hosted")
    override fun getManufacturerCodeForSlot(slotIndex: Int): String? = throw UnsupportedOperationException("ITelephony.getManufacturerCodeForSlot is not hosted")
    override fun getDeviceSoftwareVersionForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelephony.getDeviceSoftwareVersionForSlot is not hosted")
    override fun getSubIdForPhoneAccountHandle(phoneAccountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getSubIdForPhoneAccountHandle is not hosted")
    override fun getPhoneAccountHandleForSubscriptionId(subscriptionId: Int): android.telecom.PhoneAccountHandle? = throw UnsupportedOperationException("ITelephony.getPhoneAccountHandleForSubscriptionId is not hosted")
    override fun factoryReset(subId: Int, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.factoryReset is not hosted") }
    override fun getSimLocaleForSubscriber(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getSimLocaleForSubscriber is not hosted")
    override fun requestModemActivityInfo(result: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestModemActivityInfo is not hosted") }
    override fun getServiceStateForSlot(slotIndex: Int, renounceFineLocationAccess: Boolean, renounceCoarseLocationAccess: Boolean, callingPackage: String?, callingFeatureId: String?): android.telephony.ServiceState? = throw UnsupportedOperationException("ITelephony.getServiceStateForSlot is not hosted")
    override fun getVoicemailRingtoneUri(accountHandle: android.telecom.PhoneAccountHandle?): android.net.Uri? = throw UnsupportedOperationException("ITelephony.getVoicemailRingtoneUri is not hosted")
    override fun setVoicemailRingtoneUri(callingPackage: String?, phoneAccountHandle: android.telecom.PhoneAccountHandle?, uri: android.net.Uri?) { throw UnsupportedOperationException("ITelephony.setVoicemailRingtoneUri is not hosted") }
    override fun isVoicemailVibrationEnabled(accountHandle: android.telecom.PhoneAccountHandle?): Boolean = throw UnsupportedOperationException("ITelephony.isVoicemailVibrationEnabled is not hosted")
    override fun setVoicemailVibrationEnabled(callingPackage: String?, phoneAccountHandle: android.telecom.PhoneAccountHandle?, enabled: Boolean) { throw UnsupportedOperationException("ITelephony.setVoicemailVibrationEnabled is not hosted") }
    override fun getPackagesWithCarrierPrivileges(phoneId: Int): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getPackagesWithCarrierPrivileges is not hosted")
    override fun getPackagesWithCarrierPrivilegesForAllPhones(): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getPackagesWithCarrierPrivilegesForAllPhones is not hosted")
    override fun getAidForAppType(subId: Int, appType: Int): String? = throw UnsupportedOperationException("ITelephony.getAidForAppType is not hosted")
    override fun getEsn(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getEsn is not hosted")
    override fun getCdmaPrlVersion(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getCdmaPrlVersion is not hosted")
    override fun getTelephonyHistograms(): MutableList<android.telephony.TelephonyHistogram?>? = throw UnsupportedOperationException("ITelephony.getTelephonyHistograms is not hosted")
    override fun setAllowedCarriers(carrierRestrictionRules: android.telephony.CarrierRestrictionRules?): Int = throw UnsupportedOperationException("ITelephony.setAllowedCarriers is not hosted")
    override fun getAllowedCarriers(): android.telephony.CarrierRestrictionRules? = throw UnsupportedOperationException("ITelephony.getAllowedCarriers is not hosted")
    override fun getSubscriptionCarrierId(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getSubscriptionCarrierId is not hosted")
    override fun getSubscriptionCarrierName(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getSubscriptionCarrierName is not hosted")
    override fun getSubscriptionSpecificCarrierId(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getSubscriptionSpecificCarrierId is not hosted")
    override fun getSubscriptionSpecificCarrierName(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getSubscriptionSpecificCarrierName is not hosted")
    override fun getCarrierIdFromMccMnc(slotIndex: Int, mccmnc: String?, isSubscriptionMccMnc: Boolean): Int = throw UnsupportedOperationException("ITelephony.getCarrierIdFromMccMnc is not hosted")
    override fun carrierActionSetRadioEnabled(subId: Int, enabled: Boolean) { throw UnsupportedOperationException("ITelephony.carrierActionSetRadioEnabled is not hosted") }
    override fun carrierActionReportDefaultNetworkStatus(subId: Int, report: Boolean) { throw UnsupportedOperationException("ITelephony.carrierActionReportDefaultNetworkStatus is not hosted") }
    override fun carrierActionResetAll(subId: Int) { throw UnsupportedOperationException("ITelephony.carrierActionResetAll is not hosted") }
    override fun getCallForwarding(subId: Int, callForwardingReason: Int, callback: com.android.internal.telephony.ICallForwardingInfoCallback?) { throw UnsupportedOperationException("ITelephony.getCallForwarding is not hosted") }
    override fun setCallForwarding(subId: Int, callForwardingInfo: android.telephony.CallForwardingInfo?, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.setCallForwarding is not hosted") }
    override fun getCallWaitingStatus(subId: Int, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.getCallWaitingStatus is not hosted") }
    override fun setCallWaitingStatus(subId: Int, enabled: Boolean, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.setCallWaitingStatus is not hosted") }
    override fun getClientRequestStats(callingPackage: String?, callingFeatureId: String?, subid: Int): MutableList<android.telephony.ClientRequestStats?>? = throw UnsupportedOperationException("ITelephony.getClientRequestStats is not hosted")
    override fun setSimPowerStateForSlot(slotIndex: Int, state: Int) { throw UnsupportedOperationException("ITelephony.setSimPowerStateForSlot is not hosted") }
    override fun setSimPowerStateForSlotWithCallback(slotIndex: Int, state: Int, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.setSimPowerStateForSlotWithCallback is not hosted") }
    override fun getForbiddenPlmns(subId: Int, appType: Int, callingPackage: String?, callingFeatureId: String?): Array<String?>? = throw UnsupportedOperationException("ITelephony.getForbiddenPlmns is not hosted")
    override fun setForbiddenPlmns(subId: Int, appType: Int, fplmns: MutableList<String?>?, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.setForbiddenPlmns is not hosted")
    override fun getEmergencyCallbackMode(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.getEmergencyCallbackMode is not hosted")
    override fun getSignalStrength(subId: Int): android.telephony.SignalStrength? = throw UnsupportedOperationException("ITelephony.getSignalStrength is not hosted")
    override fun getCardIdForDefaultEuicc(subId: Int, callingPackage: String?): Int = throw UnsupportedOperationException("ITelephony.getCardIdForDefaultEuicc is not hosted")
    override fun getUiccCardsInfo(callingPackage: String?): MutableList<android.telephony.UiccCardInfo?>? = throw UnsupportedOperationException("ITelephony.getUiccCardsInfo is not hosted")
    override fun getUiccSlotsInfo(callingPackage: String?): Array<android.telephony.UiccSlotInfo?>? = throw UnsupportedOperationException("ITelephony.getUiccSlotsInfo is not hosted")
    override fun switchSlots(physicalSlots: IntArray?): Boolean = throw UnsupportedOperationException("ITelephony.switchSlots is not hosted")
    override fun setSimSlotMapping(slotMapping: MutableList<android.telephony.UiccSlotMapping?>?): Boolean = throw UnsupportedOperationException("ITelephony.setSimSlotMapping is not hosted")
    override fun isDataRoamingEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isDataRoamingEnabled is not hosted")
    override fun setDataRoamingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setDataRoamingEnabled is not hosted") }
    override fun getCdmaRoamingMode(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getCdmaRoamingMode is not hosted")
    override fun setCdmaRoamingMode(subId: Int, mode: Int): Boolean = throw UnsupportedOperationException("ITelephony.setCdmaRoamingMode is not hosted")
    override fun getCdmaSubscriptionMode(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getCdmaSubscriptionMode is not hosted")
    override fun setCdmaSubscriptionMode(subId: Int, mode: Int): Boolean = throw UnsupportedOperationException("ITelephony.setCdmaSubscriptionMode is not hosted")
    override fun setCarrierTestOverride(subId: Int, mccmnc: String?, imsi: String?, iccid: String?, gid1: String?, gid2: String?, plmn: String?, spn: String?, carrierPrivilegeRules: String?, apn: String?) { throw UnsupportedOperationException("ITelephony.setCarrierTestOverride is not hosted") }
    override fun setCarrierServicePackageOverride(subId: Int, carrierServicePackage: String?, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.setCarrierServicePackageOverride is not hosted") }
    override fun getCarrierIdListVersion(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getCarrierIdListVersion is not hosted")
    override fun refreshUiccProfile(subId: Int) { throw UnsupportedOperationException("ITelephony.refreshUiccProfile is not hosted") }
    override fun getNumberOfModemsWithSimultaneousDataConnections(subId: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getNumberOfModemsWithSimultaneousDataConnections is not hosted")
    override fun getNetworkSelectionMode(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getNetworkSelectionMode is not hosted")
    override fun isInEmergencySmsMode(): Boolean = throw UnsupportedOperationException("ITelephony.isInEmergencySmsMode is not hosted")
    override fun getRadioPowerState(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.getRadioPowerState is not hosted")
    override fun registerImsRegistrationCallback(subId: Int, c: android.telephony.ims.aidl.IImsRegistrationCallback?) { throw UnsupportedOperationException("ITelephony.registerImsRegistrationCallback is not hosted") }
    override fun unregisterImsRegistrationCallback(subId: Int, c: android.telephony.ims.aidl.IImsRegistrationCallback?) { throw UnsupportedOperationException("ITelephony.unregisterImsRegistrationCallback is not hosted") }
    override fun registerImsEmergencyRegistrationCallback(subId: Int, c: android.telephony.ims.aidl.IImsRegistrationCallback?) { throw UnsupportedOperationException("ITelephony.registerImsEmergencyRegistrationCallback is not hosted") }
    override fun unregisterImsEmergencyRegistrationCallback(subId: Int, c: android.telephony.ims.aidl.IImsRegistrationCallback?) { throw UnsupportedOperationException("ITelephony.unregisterImsEmergencyRegistrationCallback is not hosted") }
    override fun getImsMmTelRegistrationState(subId: Int, consumer: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.getImsMmTelRegistrationState is not hosted") }
    override fun getImsMmTelRegistrationTransportType(subId: Int, consumer: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.getImsMmTelRegistrationTransportType is not hosted") }
    override fun registerMmTelCapabilityCallback(subId: Int, c: android.telephony.ims.aidl.IImsCapabilityCallback?) { throw UnsupportedOperationException("ITelephony.registerMmTelCapabilityCallback is not hosted") }
    override fun unregisterMmTelCapabilityCallback(subId: Int, c: android.telephony.ims.aidl.IImsCapabilityCallback?) { throw UnsupportedOperationException("ITelephony.unregisterMmTelCapabilityCallback is not hosted") }
    override fun isCapable(subId: Int, capability: Int, regTech: Int): Boolean = throw UnsupportedOperationException("ITelephony.isCapable is not hosted")
    override fun isAvailable(subId: Int, capability: Int, regTech: Int): Boolean = throw UnsupportedOperationException("ITelephony.isAvailable is not hosted")
    override fun isMmTelCapabilitySupported(subId: Int, callback: com.android.internal.telephony.IIntegerConsumer?, capability: Int, transportType: Int) { throw UnsupportedOperationException("ITelephony.isMmTelCapabilitySupported is not hosted") }
    override fun isAdvancedCallingSettingEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isAdvancedCallingSettingEnabled is not hosted")
    override fun setAdvancedCallingSettingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setAdvancedCallingSettingEnabled is not hosted") }
    override fun isVtSettingEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isVtSettingEnabled is not hosted")
    override fun setVtSettingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setVtSettingEnabled is not hosted") }
    override fun isVoWiFiSettingEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isVoWiFiSettingEnabled is not hosted")
    override fun setVoWiFiSettingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setVoWiFiSettingEnabled is not hosted") }
    override fun isCrossSimCallingEnabledByUser(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isCrossSimCallingEnabledByUser is not hosted")
    override fun setCrossSimCallingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setCrossSimCallingEnabled is not hosted") }
    override fun isVoWiFiRoamingSettingEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isVoWiFiRoamingSettingEnabled is not hosted")
    override fun setVoWiFiRoamingSettingEnabled(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setVoWiFiRoamingSettingEnabled is not hosted") }
    override fun setVoWiFiNonPersistent(subId: Int, isCapable: Boolean, mode: Int) { throw UnsupportedOperationException("ITelephony.setVoWiFiNonPersistent is not hosted") }
    override fun getVoWiFiModeSetting(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getVoWiFiModeSetting is not hosted")
    override fun setVoWiFiModeSetting(subId: Int, mode: Int) { throw UnsupportedOperationException("ITelephony.setVoWiFiModeSetting is not hosted") }
    override fun getVoWiFiRoamingModeSetting(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getVoWiFiRoamingModeSetting is not hosted")
    override fun setVoWiFiRoamingModeSetting(subId: Int, mode: Int) { throw UnsupportedOperationException("ITelephony.setVoWiFiRoamingModeSetting is not hosted") }
    override fun setRttCapabilitySetting(subId: Int, isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setRttCapabilitySetting is not hosted") }
    override fun isTtyOverVolteEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isTtyOverVolteEnabled is not hosted")
    override fun getEmergencyNumberList(callingPackage: String?, callingFeatureId: String?): MutableMap<*, *>? = throw UnsupportedOperationException("ITelephony.getEmergencyNumberList is not hosted")
    override fun isEmergencyNumber(number: String?, exactMatch: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.isEmergencyNumber is not hosted")
    override fun getCertsFromCarrierPrivilegeAccessRules(subId: Int): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getCertsFromCarrierPrivilegeAccessRules is not hosted")
    override fun registerImsProvisioningChangedCallback(subId: Int, callback: android.telephony.ims.aidl.IImsConfigCallback?) { throw UnsupportedOperationException("ITelephony.registerImsProvisioningChangedCallback is not hosted") }
    override fun unregisterImsProvisioningChangedCallback(subId: Int, callback: android.telephony.ims.aidl.IImsConfigCallback?) { throw UnsupportedOperationException("ITelephony.unregisterImsProvisioningChangedCallback is not hosted") }
    override fun registerFeatureProvisioningChangedCallback(subId: Int, callback: android.telephony.ims.aidl.IFeatureProvisioningCallback?) { throw UnsupportedOperationException("ITelephony.registerFeatureProvisioningChangedCallback is not hosted") }
    override fun unregisterFeatureProvisioningChangedCallback(subId: Int, callback: android.telephony.ims.aidl.IFeatureProvisioningCallback?) { throw UnsupportedOperationException("ITelephony.unregisterFeatureProvisioningChangedCallback is not hosted") }
    override fun setImsProvisioningStatusForCapability(subId: Int, capability: Int, tech: Int, isProvisioned: Boolean) { throw UnsupportedOperationException("ITelephony.setImsProvisioningStatusForCapability is not hosted") }
    override fun getImsProvisioningStatusForCapability(subId: Int, capability: Int, tech: Int): Boolean = throw UnsupportedOperationException("ITelephony.getImsProvisioningStatusForCapability is not hosted")
    override fun getRcsProvisioningStatusForCapability(subId: Int, capability: Int, tech: Int): Boolean = throw UnsupportedOperationException("ITelephony.getRcsProvisioningStatusForCapability is not hosted")
    override fun setRcsProvisioningStatusForCapability(subId: Int, capability: Int, tech: Int, isProvisioned: Boolean) { throw UnsupportedOperationException("ITelephony.setRcsProvisioningStatusForCapability is not hosted") }
    override fun getImsProvisioningInt(subId: Int, key: Int): Int = throw UnsupportedOperationException("ITelephony.getImsProvisioningInt is not hosted")
    override fun getImsProvisioningString(subId: Int, key: Int): String? = throw UnsupportedOperationException("ITelephony.getImsProvisioningString is not hosted")
    override fun setImsProvisioningInt(subId: Int, key: Int, value: Int): Int = throw UnsupportedOperationException("ITelephony.setImsProvisioningInt is not hosted")
    override fun setImsProvisioningString(subId: Int, key: Int, value: String?): Int = throw UnsupportedOperationException("ITelephony.setImsProvisioningString is not hosted")
    override fun startEmergencyCallbackMode() { throw UnsupportedOperationException("ITelephony.startEmergencyCallbackMode is not hosted") }
    override fun updateEmergencyNumberListTestMode(action: Int, num: android.telephony.emergency.EmergencyNumber?) { throw UnsupportedOperationException("ITelephony.updateEmergencyNumberListTestMode is not hosted") }
    override fun getEmergencyNumberListTestMode(): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getEmergencyNumberListTestMode is not hosted")
    override fun getEmergencyNumberDbVersion(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getEmergencyNumberDbVersion is not hosted")
    override fun notifyOtaEmergencyNumberDbInstalled() { throw UnsupportedOperationException("ITelephony.notifyOtaEmergencyNumberDbInstalled is not hosted") }
    override fun updateOtaEmergencyNumberDbFilePath(otaParcelFileDescriptor: android.os.ParcelFileDescriptor?) { throw UnsupportedOperationException("ITelephony.updateOtaEmergencyNumberDbFilePath is not hosted") }
    override fun resetOtaEmergencyNumberDbFilePath() { throw UnsupportedOperationException("ITelephony.resetOtaEmergencyNumberDbFilePath is not hosted") }
    override fun enableModemForSlot(slotIndex: Int, enable: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.enableModemForSlot is not hosted")
    override fun setMultiSimCarrierRestriction(isMultiSimCarrierRestricted: Boolean) { throw UnsupportedOperationException("ITelephony.setMultiSimCarrierRestriction is not hosted") }
    override fun isMultiSimSupported(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelephony.isMultiSimSupported is not hosted")
    override fun switchMultiSimConfig(numOfSims: Int) { throw UnsupportedOperationException("ITelephony.switchMultiSimConfig is not hosted") }
    override fun doesSwitchMultiSimConfigTriggerReboot(subId: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.doesSwitchMultiSimConfigTriggerReboot is not hosted")
    override fun getSlotsMapping(callingPackage: String?): MutableList<android.telephony.UiccSlotMapping?>? = throw UnsupportedOperationException("ITelephony.getSlotsMapping is not hosted")
    override fun getRadioHalVersion(): Int = throw UnsupportedOperationException("ITelephony.getRadioHalVersion is not hosted")
    override fun getHalVersion(service: Int): Int = throw UnsupportedOperationException("ITelephony.getHalVersion is not hosted")
    override fun getCurrentPackageName(): String? = throw UnsupportedOperationException("ITelephony.getCurrentPackageName is not hosted")
    override fun isApplicationOnUicc(subId: Int, appType: Int): Boolean = throw UnsupportedOperationException("ITelephony.isApplicationOnUicc is not hosted")
    override fun isModemEnabledForSlot(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelephony.isModemEnabledForSlot is not hosted")
    override fun isDataEnabledForApn(apnType: Int, subId: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.isDataEnabledForApn is not hosted")
    override fun isApnMetered(apnType: Int, subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isApnMetered is not hosted")
    override fun setSystemSelectionChannels(specifiers: MutableList<android.telephony.RadioAccessSpecifier?>?, subId: Int, resultCallback: com.android.internal.telephony.IBooleanConsumer?) { throw UnsupportedOperationException("ITelephony.setSystemSelectionChannels is not hosted") }
    override fun getSystemSelectionChannels(subId: Int): MutableList<android.telephony.RadioAccessSpecifier?>? = throw UnsupportedOperationException("ITelephony.getSystemSelectionChannels is not hosted")
    override fun isMvnoMatched(slotIndex: Int, mvnoType: Int, mvnoMatchData: String?): Boolean = throw UnsupportedOperationException("ITelephony.isMvnoMatched is not hosted")
    override fun enqueueSmsPickResult(callingPackage: String?, callingAttributeTag: String?, subIdResult: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.enqueueSmsPickResult is not hosted") }
    override fun showSwitchToManagedProfileDialog() { throw UnsupportedOperationException("ITelephony.showSwitchToManagedProfileDialog is not hosted") }
    override fun getMmsUserAgent(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getMmsUserAgent is not hosted")
    override fun getMmsUAProfUrl(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getMmsUAProfUrl is not hosted")
    override fun setMobileDataPolicyEnabled(subscriptionId: Int, policy: Int, enabled: Boolean) { throw UnsupportedOperationException("ITelephony.setMobileDataPolicyEnabled is not hosted") }
    override fun isMobileDataPolicyEnabled(subscriptionId: Int, policy: Int): Boolean = throw UnsupportedOperationException("ITelephony.isMobileDataPolicyEnabled is not hosted")
    override fun setCepEnabled(isCepEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setCepEnabled is not hosted") }
    override fun notifyRcsAutoConfigurationReceived(subId: Int, config: ByteArray?, isCompressed: Boolean) { throw UnsupportedOperationException("ITelephony.notifyRcsAutoConfigurationReceived is not hosted") }
    override fun isIccLockEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isIccLockEnabled is not hosted")
    override fun setIccLockEnabled(subId: Int, enabled: Boolean, password: String?): Int = throw UnsupportedOperationException("ITelephony.setIccLockEnabled is not hosted")
    override fun changeIccLockPassword(subId: Int, oldPassword: String?, newPassword: String?): Int = throw UnsupportedOperationException("ITelephony.changeIccLockPassword is not hosted")
    override fun requestUserActivityNotification() { throw UnsupportedOperationException("ITelephony.requestUserActivityNotification is not hosted") }
    override fun userActivity() { throw UnsupportedOperationException("ITelephony.userActivity is not hosted") }
    override fun getManualNetworkSelectionPlmn(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getManualNetworkSelectionPlmn is not hosted")
    override fun canConnectTo5GInDsdsMode(): Boolean = throw UnsupportedOperationException("ITelephony.canConnectTo5GInDsdsMode is not hosted")
    override fun getEquivalentHomePlmns(subId: Int, callingPackage: String?, callingFeatureId: String?): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getEquivalentHomePlmns is not hosted")
    override fun setVoNrEnabled(subId: Int, enabled: Boolean): Int = throw UnsupportedOperationException("ITelephony.setVoNrEnabled is not hosted")
    override fun isVoNrEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isVoNrEnabled is not hosted")
    override fun setNrDualConnectivityState(subId: Int, nrDualConnectivityState: Int): Int = throw UnsupportedOperationException("ITelephony.setNrDualConnectivityState is not hosted")
    override fun isNrDualConnectivityEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isNrDualConnectivityEnabled is not hosted")
    override fun isRadioInterfaceCapabilitySupported(capability: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRadioInterfaceCapabilitySupported is not hosted")
    override fun sendThermalMitigationRequest(subId: Int, thermalMitigationRequest: android.telephony.ThermalMitigationRequest?, callingPackage: String?): Int = throw UnsupportedOperationException("ITelephony.sendThermalMitigationRequest is not hosted")
    override fun bootstrapAuthenticationRequest(subId: Int, appType: Int, nafUrl: android.net.Uri?, securityProtocol: android.telephony.gba.UaSecurityProtocolIdentifier?, forceBootStrapping: Boolean, callback: android.telephony.IBootstrapAuthenticationCallback?) { throw UnsupportedOperationException("ITelephony.bootstrapAuthenticationRequest is not hosted") }
    override fun setBoundGbaServiceOverride(subId: Int, packageName: String?): Boolean = throw UnsupportedOperationException("ITelephony.setBoundGbaServiceOverride is not hosted")
    override fun getBoundGbaService(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getBoundGbaService is not hosted")
    override fun setGbaReleaseTimeOverride(subId: Int, interval: Int): Boolean = throw UnsupportedOperationException("ITelephony.setGbaReleaseTimeOverride is not hosted")
    override fun getGbaReleaseTime(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getGbaReleaseTime is not hosted")
    override fun setRcsClientConfiguration(subId: Int, rcc: android.telephony.ims.RcsClientConfiguration?) { throw UnsupportedOperationException("ITelephony.setRcsClientConfiguration is not hosted") }
    override fun isRcsVolteSingleRegistrationCapable(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isRcsVolteSingleRegistrationCapable is not hosted")
    override fun registerRcsProvisioningCallback(subId: Int, callback: android.telephony.ims.aidl.IRcsConfigCallback?) { throw UnsupportedOperationException("ITelephony.registerRcsProvisioningCallback is not hosted") }
    override fun unregisterRcsProvisioningCallback(subId: Int, callback: android.telephony.ims.aidl.IRcsConfigCallback?) { throw UnsupportedOperationException("ITelephony.unregisterRcsProvisioningCallback is not hosted") }
    override fun triggerRcsReconfiguration(subId: Int) { throw UnsupportedOperationException("ITelephony.triggerRcsReconfiguration is not hosted") }
    override fun setRcsSingleRegistrationTestModeEnabled(enabled: Boolean) { throw UnsupportedOperationException("ITelephony.setRcsSingleRegistrationTestModeEnabled is not hosted") }
    override fun getRcsSingleRegistrationTestModeEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.getRcsSingleRegistrationTestModeEnabled is not hosted")
    override fun setDeviceSingleRegistrationEnabledOverride(enabled: String?) { throw UnsupportedOperationException("ITelephony.setDeviceSingleRegistrationEnabledOverride is not hosted") }
    override fun getDeviceSingleRegistrationEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.getDeviceSingleRegistrationEnabled is not hosted")
    override fun setCarrierSingleRegistrationEnabledOverride(subId: Int, enabled: String?): Boolean = throw UnsupportedOperationException("ITelephony.setCarrierSingleRegistrationEnabledOverride is not hosted")
    override fun sendDeviceToDeviceMessage(message: Int, value: Int) { throw UnsupportedOperationException("ITelephony.sendDeviceToDeviceMessage is not hosted") }
    override fun setActiveDeviceToDeviceTransport(transport: String?) { throw UnsupportedOperationException("ITelephony.setActiveDeviceToDeviceTransport is not hosted") }
    override fun setDeviceToDeviceForceEnabled(isForceEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setDeviceToDeviceForceEnabled is not hosted") }
    override fun getCarrierSingleRegistrationEnabled(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.getCarrierSingleRegistrationEnabled is not hosted")
    override fun setImsFeatureValidationOverride(subId: Int, enabled: String?): Boolean = throw UnsupportedOperationException("ITelephony.setImsFeatureValidationOverride is not hosted")
    override fun getImsFeatureValidationOverride(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.getImsFeatureValidationOverride is not hosted")
    override fun getMobileProvisioningUrl(): String? = throw UnsupportedOperationException("ITelephony.getMobileProvisioningUrl is not hosted")
    override fun removeContactFromEab(subId: Int, contacts: String?): Int = throw UnsupportedOperationException("ITelephony.removeContactFromEab is not hosted")
    override fun getContactFromEab(contact: String?): String? = throw UnsupportedOperationException("ITelephony.getContactFromEab is not hosted")
    override fun getCapabilityFromEab(contact: String?): String? = throw UnsupportedOperationException("ITelephony.getCapabilityFromEab is not hosted")
    override fun getDeviceUceEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.getDeviceUceEnabled is not hosted")
    override fun setDeviceUceEnabled(isEnabled: Boolean) { throw UnsupportedOperationException("ITelephony.setDeviceUceEnabled is not hosted") }
    override fun addUceRegistrationOverrideShell(subId: Int, featureTags: MutableList<String?>?): android.telephony.ims.RcsContactUceCapability? = throw UnsupportedOperationException("ITelephony.addUceRegistrationOverrideShell is not hosted")
    override fun removeUceRegistrationOverrideShell(subId: Int, featureTags: MutableList<String?>?): android.telephony.ims.RcsContactUceCapability? = throw UnsupportedOperationException("ITelephony.removeUceRegistrationOverrideShell is not hosted")
    override fun clearUceRegistrationOverrideShell(subId: Int): android.telephony.ims.RcsContactUceCapability? = throw UnsupportedOperationException("ITelephony.clearUceRegistrationOverrideShell is not hosted")
    override fun getLatestRcsContactUceCapabilityShell(subId: Int): android.telephony.ims.RcsContactUceCapability? = throw UnsupportedOperationException("ITelephony.getLatestRcsContactUceCapabilityShell is not hosted")
    override fun getLastUcePidfXmlShell(subId: Int): String? = throw UnsupportedOperationException("ITelephony.getLastUcePidfXmlShell is not hosted")
    override fun removeUceRequestDisallowedStatus(subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.removeUceRequestDisallowedStatus is not hosted")
    override fun setCapabilitiesRequestTimeout(subId: Int, timeoutAfterMs: Long): Boolean = throw UnsupportedOperationException("ITelephony.setCapabilitiesRequestTimeout is not hosted")
    override fun setSignalStrengthUpdateRequest(subId: Int, request: android.telephony.SignalStrengthUpdateRequest?, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.setSignalStrengthUpdateRequest is not hosted") }
    override fun clearSignalStrengthUpdateRequest(subId: Int, request: android.telephony.SignalStrengthUpdateRequest?, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.clearSignalStrengthUpdateRequest is not hosted") }
    override fun getPhoneCapability(): android.telephony.PhoneCapability? = throw UnsupportedOperationException("ITelephony.getPhoneCapability is not hosted")
    override fun prepareForUnattendedReboot(): Int = throw UnsupportedOperationException("ITelephony.prepareForUnattendedReboot is not hosted")
    override fun getSlicingConfig(callback: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.getSlicingConfig is not hosted") }
    override fun isPremiumCapabilityAvailableForPurchase(capability: Int, subId: Int): Boolean = throw UnsupportedOperationException("ITelephony.isPremiumCapabilityAvailableForPurchase is not hosted")
    override fun purchasePremiumCapability(capability: Int, callback: com.android.internal.telephony.IIntegerConsumer?, subId: Int) { throw UnsupportedOperationException("ITelephony.purchasePremiumCapability is not hosted") }
    override fun registerImsStateCallback(subId: Int, feature: Int, cb: com.android.internal.telephony.IImsStateCallback?, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.registerImsStateCallback is not hosted") }
    override fun unregisterImsStateCallback(cb: com.android.internal.telephony.IImsStateCallback?) { throw UnsupportedOperationException("ITelephony.unregisterImsStateCallback is not hosted") }
    override fun getLastKnownCellIdentity(subId: Int, callingPackage: String?, callingFeatureId: String?): android.telephony.CellIdentity? = throw UnsupportedOperationException("ITelephony.getLastKnownCellIdentity is not hosted")
    override fun setModemService(serviceName: String?): Boolean = throw UnsupportedOperationException("ITelephony.setModemService is not hosted")
    override fun getModemService(): String? = throw UnsupportedOperationException("ITelephony.getModemService is not hosted")
    override fun isProvisioningRequiredForCapability(subId: Int, capability: Int, tech: Int): Boolean = throw UnsupportedOperationException("ITelephony.isProvisioningRequiredForCapability is not hosted")
    override fun isRcsProvisioningRequiredForCapability(subId: Int, capability: Int, tech: Int): Boolean = throw UnsupportedOperationException("ITelephony.isRcsProvisioningRequiredForCapability is not hosted")
    override fun setVoiceServiceStateOverride(subId: Int, hasService: Boolean, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.setVoiceServiceStateOverride is not hosted") }
    override fun getCarrierServicePackageNameForLogicalSlot(logicalSlotIndex: Int): String? = throw UnsupportedOperationException("ITelephony.getCarrierServicePackageNameForLogicalSlot is not hosted")
    override fun setRemovableEsimAsDefaultEuicc(isDefault: Boolean, callingPackage: String?) { throw UnsupportedOperationException("ITelephony.setRemovableEsimAsDefaultEuicc is not hosted") }
    override fun isRemovableEsimDefaultEuicc(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelephony.isRemovableEsimDefaultEuicc is not hosted")
    override fun getDefaultRespondViaMessageApplication(subId: Int, updateIfNeeded: Boolean): android.content.ComponentName? = throw UnsupportedOperationException("ITelephony.getDefaultRespondViaMessageApplication is not hosted")
    override fun getSimStateForSlotIndex(slotIndex: Int): Int = throw UnsupportedOperationException("ITelephony.getSimStateForSlotIndex is not hosted")
    override fun persistEmergencyCallDiagnosticData(dropboxTag: String?, enableLogcat: Boolean, logcatStartTimestampMillis: Long, enableTelecomDump: Boolean, enableTelephonyDump: Boolean) { throw UnsupportedOperationException("ITelephony.persistEmergencyCallDiagnosticData is not hosted") }
    override fun setNullCipherAndIntegrityEnabled(enabled: Boolean) { throw UnsupportedOperationException("ITelephony.setNullCipherAndIntegrityEnabled is not hosted") }
    override fun isNullCipherAndIntegrityPreferenceEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.isNullCipherAndIntegrityPreferenceEnabled is not hosted")
    override fun getCellBroadcastIdRanges(subId: Int): MutableList<android.telephony.CellBroadcastIdRange?>? = throw UnsupportedOperationException("ITelephony.getCellBroadcastIdRanges is not hosted")
    override fun setCellBroadcastIdRanges(subId: Int, ranges: MutableList<android.telephony.CellBroadcastIdRange?>?, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.setCellBroadcastIdRanges is not hosted") }
    override fun isDomainSelectionSupported(): Boolean = throw UnsupportedOperationException("ITelephony.isDomainSelectionSupported is not hosted")
    override fun getCarrierRestrictionStatus(internalCallback: com.android.internal.telephony.IIntegerConsumer?, packageName: String?) { throw UnsupportedOperationException("ITelephony.getCarrierRestrictionStatus is not hosted") }
    override fun requestSatelliteEnabled(enableSatellite: Boolean, enableDemoMode: Boolean, isEmergency: Boolean, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.requestSatelliteEnabled is not hosted") }
    override fun requestIsSatelliteEnabled(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsSatelliteEnabled is not hosted") }
    override fun requestIsDemoModeEnabled(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsDemoModeEnabled is not hosted") }
    override fun requestIsEmergencyModeEnabled(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsEmergencyModeEnabled is not hosted") }
    override fun requestIsSatelliteSupported(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsSatelliteSupported is not hosted") }
    override fun requestSatelliteCapabilities(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSatelliteCapabilities is not hosted") }
    override fun startSatelliteTransmissionUpdates(resultCallback: com.android.internal.telephony.IIntegerConsumer?, callback: android.telephony.satellite.ISatelliteTransmissionUpdateCallback?) { throw UnsupportedOperationException("ITelephony.startSatelliteTransmissionUpdates is not hosted") }
    override fun stopSatelliteTransmissionUpdates(resultCallback: com.android.internal.telephony.IIntegerConsumer?, callback: android.telephony.satellite.ISatelliteTransmissionUpdateCallback?) { throw UnsupportedOperationException("ITelephony.stopSatelliteTransmissionUpdates is not hosted") }
    override fun provisionSatelliteService(token: String?, provisionData: ByteArray?, callback: com.android.internal.telephony.IIntegerConsumer?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ITelephony.provisionSatelliteService is not hosted")
    override fun deprovisionSatelliteService(token: String?, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.deprovisionSatelliteService is not hosted") }
    override fun registerForSatelliteProvisionStateChanged(callback: android.telephony.satellite.ISatelliteProvisionStateCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForSatelliteProvisionStateChanged is not hosted")
    override fun unregisterForSatelliteProvisionStateChanged(callback: android.telephony.satellite.ISatelliteProvisionStateCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForSatelliteProvisionStateChanged is not hosted") }
    override fun requestIsSatelliteProvisioned(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsSatelliteProvisioned is not hosted") }
    override fun registerForSatelliteModemStateChanged(callback: android.telephony.satellite.ISatelliteModemStateCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForSatelliteModemStateChanged is not hosted")
    override fun unregisterForModemStateChanged(callback: android.telephony.satellite.ISatelliteModemStateCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForModemStateChanged is not hosted") }
    override fun registerForIncomingDatagram(callback: android.telephony.satellite.ISatelliteDatagramCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForIncomingDatagram is not hosted")
    override fun unregisterForIncomingDatagram(callback: android.telephony.satellite.ISatelliteDatagramCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForIncomingDatagram is not hosted") }
    override fun pollPendingDatagrams(callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.pollPendingDatagrams is not hosted") }
    override fun sendDatagram(datagramType: Int, datagram: android.telephony.satellite.SatelliteDatagram?, needFullScreenPointingUI: Boolean, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.sendDatagram is not hosted") }
    override fun getSatelliteDisallowedReasons(): IntArray? = throw UnsupportedOperationException("ITelephony.getSatelliteDisallowedReasons is not hosted")
    override fun registerForSatelliteDisallowedReasonsChanged(callback: android.telephony.satellite.ISatelliteDisallowedReasonsCallback?) { throw UnsupportedOperationException("ITelephony.registerForSatelliteDisallowedReasonsChanged is not hosted") }
    override fun unregisterForSatelliteDisallowedReasonsChanged(callback: android.telephony.satellite.ISatelliteDisallowedReasonsCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForSatelliteDisallowedReasonsChanged is not hosted") }
    override fun requestIsCommunicationAllowedForCurrentLocation(subId: Int, receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestIsCommunicationAllowedForCurrentLocation is not hosted") }
    override fun requestSatelliteAccessConfigurationForCurrentLocation(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSatelliteAccessConfigurationForCurrentLocation is not hosted") }
    override fun requestTimeForNextSatelliteVisibility(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestTimeForNextSatelliteVisibility is not hosted") }
    override fun requestSelectedNbIotSatelliteSubscriptionId(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSelectedNbIotSatelliteSubscriptionId is not hosted") }
    override fun registerForSelectedNbIotSatelliteSubscriptionChanged(callback: android.telephony.satellite.ISelectedNbIotSatelliteSubscriptionCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForSelectedNbIotSatelliteSubscriptionChanged is not hosted")
    override fun unregisterForSelectedNbIotSatelliteSubscriptionChanged(callback: android.telephony.satellite.ISelectedNbIotSatelliteSubscriptionCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForSelectedNbIotSatelliteSubscriptionChanged is not hosted") }
    override fun setDeviceAlignedWithSatellite(isAligned: Boolean) { throw UnsupportedOperationException("ITelephony.setDeviceAlignedWithSatellite is not hosted") }
    override fun setSatelliteServicePackageName(servicePackageName: String?, provisioned: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteServicePackageName is not hosted")
    override fun setSatelliteGatewayServicePackageName(servicePackageName: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteGatewayServicePackageName is not hosted")
    override fun setSatelliteListeningTimeoutDuration(timeoutMillis: Long): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteListeningTimeoutDuration is not hosted")
    override fun setSatelliteIgnoreCellularServiceState(enabled: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteIgnoreCellularServiceState is not hosted")
    override fun setSupportDisableSatelliteWhileEnableInProgress(reset: Boolean, supported: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setSupportDisableSatelliteWhileEnableInProgress is not hosted")
    override fun setSatellitePointingUiClassName(packageName: String?, className: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatellitePointingUiClassName is not hosted")
    override fun setDatagramControllerTimeoutDuration(reset: Boolean, timeoutType: Int, timeoutMillis: Long): Boolean = throw UnsupportedOperationException("ITelephony.setDatagramControllerTimeoutDuration is not hosted")
    override fun setSatelliteControllerTimeoutDuration(reset: Boolean, timeoutType: Int, timeoutMillis: Long): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteControllerTimeoutDuration is not hosted")
    override fun setEmergencyCallToSatelliteHandoverType(handoverType: Int, delaySeconds: Int): Boolean = throw UnsupportedOperationException("ITelephony.setEmergencyCallToSatelliteHandoverType is not hosted")
    override fun setCountryCodes(reset: Boolean, currentNetworkCountryCodes: MutableList<String?>?, cachedNetworkCountryCodes: MutableMap<*, *>?, locationCountryCode: String?, locationCountryCodeTimestampNanos: Long): Boolean = throw UnsupportedOperationException("ITelephony.setCountryCodes is not hosted")
    override fun setSatelliteAccessControlOverlayConfigs(reset: Boolean, isAllowed: Boolean, s2CellFile: String?, locationFreshDurationNanos: Long, satelliteCountryCodes: MutableList<String?>?, satelliteAccessConfigurationFile: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteAccessControlOverlayConfigs is not hosted")
    override fun setSatelliteAccessAllowedForSubscriptions(subIdListStr: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteAccessAllowedForSubscriptions is not hosted")
    override fun setTnScanningSupport(reset: Boolean, concurrentTnScanningSupported: Boolean, tnScanningDuringSatelliteSessionAllowed: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setTnScanningSupport is not hosted")
    override fun setOemEnabledSatelliteProvisionStatus(reset: Boolean, isProvisioned: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setOemEnabledSatelliteProvisionStatus is not hosted")
    override fun overrideConfigDataVersion(reset: Boolean, version: Int): Boolean = throw UnsupportedOperationException("ITelephony.overrideConfigDataVersion is not hosted")
    override fun getShaIdFromAllowList(pkgName: String?, carrierId: Int): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getShaIdFromAllowList is not hosted")
    override fun addAttachRestrictionForCarrier(subId: Int, reason: Int, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.addAttachRestrictionForCarrier is not hosted") }
    override fun removeAttachRestrictionForCarrier(subId: Int, reason: Int, callback: com.android.internal.telephony.IIntegerConsumer?) { throw UnsupportedOperationException("ITelephony.removeAttachRestrictionForCarrier is not hosted") }
    override fun getAttachRestrictionReasonsForCarrier(subId: Int): IntArray? = throw UnsupportedOperationException("ITelephony.getAttachRestrictionReasonsForCarrier is not hosted")
    override fun requestNtnSignalStrength(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestNtnSignalStrength is not hosted") }
    override fun registerForNtnSignalStrengthChanged(callback: android.telephony.satellite.INtnSignalStrengthCallback?) { throw UnsupportedOperationException("ITelephony.registerForNtnSignalStrengthChanged is not hosted") }
    override fun unregisterForNtnSignalStrengthChanged(callback: android.telephony.satellite.INtnSignalStrengthCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForNtnSignalStrengthChanged is not hosted") }
    override fun registerForCapabilitiesChanged(callback: android.telephony.satellite.ISatelliteCapabilitiesCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForCapabilitiesChanged is not hosted")
    override fun unregisterForCapabilitiesChanged(callback: android.telephony.satellite.ISatelliteCapabilitiesCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForCapabilitiesChanged is not hosted") }
    override fun setShouldSendDatagramToModemInDemoMode(shouldSendToModemInDemoMode: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setShouldSendDatagramToModemInDemoMode is not hosted")
    override fun setDomainSelectionServiceOverride(componentName: android.content.ComponentName?): Boolean = throw UnsupportedOperationException("ITelephony.setDomainSelectionServiceOverride is not hosted")
    override fun clearDomainSelectionServiceOverride(): Boolean = throw UnsupportedOperationException("ITelephony.clearDomainSelectionServiceOverride is not hosted")
    override fun isAospDomainSelectionService(): Boolean = throw UnsupportedOperationException("ITelephony.isAospDomainSelectionService is not hosted")
    override fun setEnableCellularIdentifierDisclosureNotifications(enable: Boolean) { throw UnsupportedOperationException("ITelephony.setEnableCellularIdentifierDisclosureNotifications is not hosted") }
    override fun isCellularIdentifierDisclosureNotificationsEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.isCellularIdentifierDisclosureNotificationsEnabled is not hosted")
    override fun setNullCipherNotificationsEnabled(enable: Boolean) { throw UnsupportedOperationException("ITelephony.setNullCipherNotificationsEnabled is not hosted") }
    override fun isNullCipherNotificationsEnabled(): Boolean = throw UnsupportedOperationException("ITelephony.isNullCipherNotificationsEnabled is not hosted")
    override fun getSatellitePlmnsForCarrier(subId: Int): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getSatellitePlmnsForCarrier is not hosted")
    override fun registerForSatelliteSupportedStateChanged(callback: com.android.internal.telephony.IBooleanConsumer?): Int = throw UnsupportedOperationException("ITelephony.registerForSatelliteSupportedStateChanged is not hosted")
    override fun unregisterForSatelliteSupportedStateChanged(callback: com.android.internal.telephony.IBooleanConsumer?) { throw UnsupportedOperationException("ITelephony.unregisterForSatelliteSupportedStateChanged is not hosted") }
    override fun registerForCommunicationAccessStateChanged(subId: Int, callback: android.telephony.satellite.ISatelliteCommunicationAccessStateCallback?): Int = throw UnsupportedOperationException("ITelephony.registerForCommunicationAccessStateChanged is not hosted")
    override fun unregisterForCommunicationAccessStateChanged(subId: Int, callback: android.telephony.satellite.ISatelliteCommunicationAccessStateCallback?) { throw UnsupportedOperationException("ITelephony.unregisterForCommunicationAccessStateChanged is not hosted") }
    override fun setDatagramControllerBooleanConfig(reset: Boolean, booleanType: Int, enable: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setDatagramControllerBooleanConfig is not hosted")
    override fun setIsSatelliteCommunicationAllowedForCurrentLocationCache(state: String?): Boolean = throw UnsupportedOperationException("ITelephony.setIsSatelliteCommunicationAllowedForCurrentLocationCache is not hosted")
    override fun requestSatelliteSessionStats(subId: Int, receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSatelliteSessionStats is not hosted") }
    override fun requestSatelliteSubscriberProvisionStatus(result: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSatelliteSubscriberProvisionStatus is not hosted") }
    override fun requestSatelliteDisplayName(receiver: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.requestSatelliteDisplayName is not hosted") }
    override fun provisionSatellite(list: MutableList<android.telephony.satellite.SatelliteSubscriberInfo?>?, result: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.provisionSatellite is not hosted") }
    override fun setSatelliteSubscriberIdListChangedIntentComponent(name: String?): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteSubscriberIdListChangedIntentComponent is not hosted")
    override fun setTestEuiccUiComponent(componentName: android.content.ComponentName?) { throw UnsupportedOperationException("ITelephony.setTestEuiccUiComponent is not hosted") }
    override fun getTestEuiccUiComponent(): android.content.ComponentName? = throw UnsupportedOperationException("ITelephony.getTestEuiccUiComponent is not hosted")
    override fun overrideCarrierRoamingNtnEligibilityChanged(status: Boolean, resetRequired: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.overrideCarrierRoamingNtnEligibilityChanged is not hosted")
    override fun deprovisionSatellite(list: MutableList<android.telephony.satellite.SatelliteSubscriberInfo?>?, result: android.os.ResultReceiver?) { throw UnsupportedOperationException("ITelephony.deprovisionSatellite is not hosted") }
    override fun setNtnSmsSupported(ntnSmsSupported: Boolean) { throw UnsupportedOperationException("ITelephony.setNtnSmsSupported is not hosted") }
    override fun getCarrierIdFromIdentifier(carrierIdentifier: android.service.carrier.CarrierIdentifier?): Int = throw UnsupportedOperationException("ITelephony.getCarrierIdFromIdentifier is not hosted")
    override fun getSatelliteDataOptimizedApps(): MutableList<String?>? = throw UnsupportedOperationException("ITelephony.getSatelliteDataOptimizedApps is not hosted")
    override fun getSatelliteDataSupportMode(subId: Int): Int = throw UnsupportedOperationException("ITelephony.getSatelliteDataSupportMode is not hosted")
    override fun setSatelliteIgnorePlmnListFromStorage(enabled: Boolean): Boolean = throw UnsupportedOperationException("ITelephony.setSatelliteIgnorePlmnListFromStorage is not hosted")
}
