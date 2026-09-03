@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ITelecomService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedTelecomService : com.android.internal.telecom.ITelecomService.Stub() {
    override fun showInCallScreen(showDialpad: Boolean, callingPackage: String?, callingFeatureId: String?) { throw UnsupportedOperationException("ITelecomService.showInCallScreen is not hosted") }
    override fun getDefaultOutgoingPhoneAccount(uriScheme: String?, callingPackage: String?, callingFeatureId: String?): android.telecom.PhoneAccountHandle? = throw UnsupportedOperationException("ITelecomService.getDefaultOutgoingPhoneAccount is not hosted")
    override fun getUserSelectedOutgoingPhoneAccount(callingPackage: String?): android.telecom.PhoneAccountHandle? = throw UnsupportedOperationException("ITelecomService.getUserSelectedOutgoingPhoneAccount is not hosted")
    override fun setUserSelectedOutgoingPhoneAccount(account: android.telecom.PhoneAccountHandle?) { throw UnsupportedOperationException("ITelecomService.setUserSelectedOutgoingPhoneAccount is not hosted") }
    override fun getCallCapablePhoneAccounts(includeDisabledAccounts: Boolean, callingPackage: String?, callingFeatureId: String?, acrossProfiles: Boolean): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getCallCapablePhoneAccounts is not hosted")
    override fun getSelfManagedPhoneAccounts(callingPackage: String?, callingFeatureId: String?): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getSelfManagedPhoneAccounts is not hosted")
    override fun getOwnSelfManagedPhoneAccounts(callingPackage: String?, callingFeatureId: String?): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getOwnSelfManagedPhoneAccounts is not hosted")
    override fun getPhoneAccountsSupportingScheme(uriScheme: String?, callingPackage: String?): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getPhoneAccountsSupportingScheme is not hosted")
    override fun getPhoneAccountsForPackage(packageName: String?): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getPhoneAccountsForPackage is not hosted")
    override fun getPhoneAccount(account: android.telecom.PhoneAccountHandle?, callingPackage: String?): android.telecom.PhoneAccount? = throw UnsupportedOperationException("ITelecomService.getPhoneAccount is not hosted")
    override fun getRegisteredPhoneAccounts(callingPackage: String?, callingFeatureId: String?): android.content.pm.ParceledListSlice<android.telecom.PhoneAccount>? = throw UnsupportedOperationException("ITelecomService.getRegisteredPhoneAccounts is not hosted")
    override fun getAllPhoneAccountsCount(): Int = throw UnsupportedOperationException("ITelecomService.getAllPhoneAccountsCount is not hosted")
    override fun getAllPhoneAccounts(): android.content.pm.ParceledListSlice<android.telecom.PhoneAccount>? = throw UnsupportedOperationException("ITelecomService.getAllPhoneAccounts is not hosted")
    override fun getAllPhoneAccountHandles(): android.content.pm.ParceledListSlice<android.telecom.PhoneAccountHandle>? = throw UnsupportedOperationException("ITelecomService.getAllPhoneAccountHandles is not hosted")
    override fun getSimCallManager(subId: Int, callingPackage: String?): android.telecom.PhoneAccountHandle? = throw UnsupportedOperationException("ITelecomService.getSimCallManager is not hosted")
    override fun getSimCallManagerForUser(userId: Int, callingPackage: String?): android.telecom.PhoneAccountHandle? = throw UnsupportedOperationException("ITelecomService.getSimCallManagerForUser is not hosted")
    override fun registerPhoneAccount(metadata: android.telecom.PhoneAccount?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.registerPhoneAccount is not hosted") }
    override fun unregisterPhoneAccount(account: android.telecom.PhoneAccountHandle?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.unregisterPhoneAccount is not hosted") }
    override fun clearAccounts(packageName: String?) { throw UnsupportedOperationException("ITelecomService.clearAccounts is not hosted") }
    override fun isVoiceMailNumber(accountHandle: android.telecom.PhoneAccountHandle?, number: String?, callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isVoiceMailNumber is not hosted")
    override fun getVoiceMailNumber(accountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelecomService.getVoiceMailNumber is not hosted")
    override fun getLine1Number(accountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): String? = throw UnsupportedOperationException("ITelecomService.getLine1Number is not hosted")
    override fun getDefaultPhoneApp(): android.content.ComponentName? = throw UnsupportedOperationException("ITelecomService.getDefaultPhoneApp is not hosted")
    override fun getDefaultDialerPackage(callingPackage: String?): String? = throw UnsupportedOperationException("ITelecomService.getDefaultDialerPackage is not hosted")
    override fun getDefaultDialerPackageForUser(userId: Int): String? = throw UnsupportedOperationException("ITelecomService.getDefaultDialerPackageForUser is not hosted")
    override fun getSystemDialerPackage(callingPackage: String?): String? = throw UnsupportedOperationException("ITelecomService.getSystemDialerPackage is not hosted")
    override fun dumpCallAnalytics(): android.telecom.TelecomAnalytics? = throw UnsupportedOperationException("ITelecomService.dumpCallAnalytics is not hosted")
    override fun silenceRinger(callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.silenceRinger is not hosted") }
    override fun isInCall(callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isInCall is not hosted")
    override fun hasManageOngoingCallsPermission(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.hasManageOngoingCallsPermission is not hosted")
    override fun isInManagedCall(callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isInManagedCall is not hosted")
    override fun isRinging(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isRinging is not hosted")
    override fun getCallState(): Int = throw UnsupportedOperationException("ITelecomService.getCallState is not hosted")
    override fun getCallStateUsingPackage(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelecomService.getCallStateUsingPackage is not hosted")
    override fun endCall(callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.endCall is not hosted")
    override fun acceptRingingCall(callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.acceptRingingCall is not hosted") }
    override fun acceptRingingCallWithVideoState(callingPackage: String?, videoState: Int) { throw UnsupportedOperationException("ITelecomService.acceptRingingCallWithVideoState is not hosted") }
    override fun cancelMissedCallsNotification(callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.cancelMissedCallsNotification is not hosted") }
    override fun handlePinMmi(dialString: String?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.handlePinMmi is not hosted")
    override fun handlePinMmiForPhoneAccount(accountHandle: android.telecom.PhoneAccountHandle?, dialString: String?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.handlePinMmiForPhoneAccount is not hosted")
    override fun getAdnUriForPhoneAccount(accountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?): android.net.Uri? = throw UnsupportedOperationException("ITelecomService.getAdnUriForPhoneAccount is not hosted")
    override fun isTtySupported(callingPackage: String?, callingFeatureId: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isTtySupported is not hosted")
    override fun getCurrentTtyMode(callingPackage: String?, callingFeatureId: String?): Int = throw UnsupportedOperationException("ITelecomService.getCurrentTtyMode is not hosted")
    override fun addNewIncomingCall(phoneAccount: android.telecom.PhoneAccountHandle?, extras: android.os.Bundle?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.addNewIncomingCall is not hosted") }
    override fun addNewIncomingConference(phoneAccount: android.telecom.PhoneAccountHandle?, extras: android.os.Bundle?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.addNewIncomingConference is not hosted") }
    override fun addNewUnknownCall(phoneAccount: android.telecom.PhoneAccountHandle?, extras: android.os.Bundle?) { throw UnsupportedOperationException("ITelecomService.addNewUnknownCall is not hosted") }
    override fun startConference(participants: MutableList<android.net.Uri?>?, extras: android.os.Bundle?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.startConference is not hosted") }
    override fun placeCall(handle: android.net.Uri?, extras: android.os.Bundle?, callingPackage: String?, callingFeatureId: String?) { throw UnsupportedOperationException("ITelecomService.placeCall is not hosted") }
    override fun enablePhoneAccount(accountHandle: android.telecom.PhoneAccountHandle?, isEnabled: Boolean): Boolean = throw UnsupportedOperationException("ITelecomService.enablePhoneAccount is not hosted")
    override fun setDefaultDialer(packageName: String?): Boolean = throw UnsupportedOperationException("ITelecomService.setDefaultDialer is not hosted")
    override fun stopBlockSuppression() { throw UnsupportedOperationException("ITelecomService.stopBlockSuppression is not hosted") }
    override fun createManageBlockedNumbersIntent(callingPackage: String?): android.content.Intent? = throw UnsupportedOperationException("ITelecomService.createManageBlockedNumbersIntent is not hosted")
    override fun createLaunchEmergencyDialerIntent(number: String?): android.content.Intent? = throw UnsupportedOperationException("ITelecomService.createLaunchEmergencyDialerIntent is not hosted")
    override fun isIncomingCallPermitted(phoneAccountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isIncomingCallPermitted is not hosted")
    override fun isOutgoingCallPermitted(phoneAccountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isOutgoingCallPermitted is not hosted")
    override fun waitOnHandlers() { throw UnsupportedOperationException("ITelecomService.waitOnHandlers is not hosted") }
    override fun acceptHandover(srcAddr: android.net.Uri?, videoState: Int, destAcct: android.telecom.PhoneAccountHandle?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.acceptHandover is not hosted") }
    override fun setTestEmergencyPhoneAccountPackageNameFilter(packageName: String?) { throw UnsupportedOperationException("ITelecomService.setTestEmergencyPhoneAccountPackageNameFilter is not hosted") }
    override fun isInEmergencyCall(): Boolean = throw UnsupportedOperationException("ITelecomService.isInEmergencyCall is not hosted")
    override fun handleCallIntent(intent: android.content.Intent?, callingPackageProxy: String?) { throw UnsupportedOperationException("ITelecomService.handleCallIntent is not hosted") }
    override fun cleanupStuckCalls() { throw UnsupportedOperationException("ITelecomService.cleanupStuckCalls is not hosted") }
    override fun cleanupOrphanPhoneAccounts(): Int = throw UnsupportedOperationException("ITelecomService.cleanupOrphanPhoneAccounts is not hosted")
    override fun isNonUiInCallServiceBound(packageName: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isNonUiInCallServiceBound is not hosted")
    override fun resetCarMode() { throw UnsupportedOperationException("ITelecomService.resetCarMode is not hosted") }
    override fun setTestDefaultCallRedirectionApp(packageName: String?) { throw UnsupportedOperationException("ITelecomService.setTestDefaultCallRedirectionApp is not hosted") }
    override fun requestLogMark(message: String?) { throw UnsupportedOperationException("ITelecomService.requestLogMark is not hosted") }
    override fun setTestPhoneAcctSuggestionComponent(flattenedComponentName: String?, userHandle: android.os.UserHandle?) { throw UnsupportedOperationException("ITelecomService.setTestPhoneAcctSuggestionComponent is not hosted") }
    override fun setTestDefaultCallScreeningApp(packageName: String?) { throw UnsupportedOperationException("ITelecomService.setTestDefaultCallScreeningApp is not hosted") }
    override fun addOrRemoveTestCallCompanionApp(packageName: String?, isAdded: Boolean) { throw UnsupportedOperationException("ITelecomService.addOrRemoveTestCallCompanionApp is not hosted") }
    override fun setSystemDialer(testComponentName: android.content.ComponentName?) { throw UnsupportedOperationException("ITelecomService.setSystemDialer is not hosted") }
    override fun setTestDefaultDialer(packageName: String?) { throw UnsupportedOperationException("ITelecomService.setTestDefaultDialer is not hosted") }
    override fun setTestCallDiagnosticService(packageName: String?) { throw UnsupportedOperationException("ITelecomService.setTestCallDiagnosticService is not hosted") }
    override fun isInSelfManagedCall(packageName: String?, userHandle: android.os.UserHandle?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.isInSelfManagedCall is not hosted")
    override fun addCall(callAttributes: android.telecom.CallAttributes?, callback: com.android.internal.telecom.ICallEventCallback?, callId: String?, callingPackage: String?) { throw UnsupportedOperationException("ITelecomService.addCall is not hosted") }
    override fun hasForegroundServiceDelegation(phoneAccountHandle: android.telecom.PhoneAccountHandle?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ITelecomService.hasForegroundServiceDelegation is not hosted")
    override fun setMetricsTestMode(enabled: Boolean) { throw UnsupportedOperationException("ITelecomService.setMetricsTestMode is not hosted") }
    override fun waitForAudioToUpdate(expectActive: Boolean) { throw UnsupportedOperationException("ITelecomService.waitForAudioToUpdate is not hosted") }
}
