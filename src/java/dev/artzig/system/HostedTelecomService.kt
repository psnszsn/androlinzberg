package dev.artzig.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ParceledListSlice
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.UserHandle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * The session's ITelecomService over the real AIDL surface, published as
 * "telecom".
 *
 * Source note: `com.android.server.telecom.TelecomServiceImpl` and
 * `PhoneAccountRegistrar` on a device with no dialer, no SIM accounts and no
 * calls: the phone-account registry is real (registration validation, package
 * ownership, capability permissions, enabled state, the per-package and
 * self-managed queries), the call state is permanently idle, and the defaults a
 * dialer or SIM would provide (default outgoing account, SIM call manager,
 * default/system dialer, voicemail) are absent. Placing, adding or answering a
 * call needs a ConnectionService bound by telecom's CallsManager, which this
 * host does not run: `placeCall` keeps the CALL_PHONE gate and logs that no
 * account can carry the call; the incoming-call and transactional-call entry
 * points stay unserved.
 */
class HostedTelecomService(
    private val context: Context,
    private val permissions: HostedTelephonyPermissions,
    private val packages: HostedPackageManagerService,
) : com.android.internal.telecom.ITelecomService.Stub(), com.android.internal.telecom.ITelecomService by unsupported<com.android.internal.telecom.ITelecomService>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private val accounts = LinkedHashMap<PhoneAccountHandle, PhoneAccount>()

    private fun callerOwns(packageName: String?): Boolean = permissions.isCallerPackage(packageName)

    /** TelecomServiceImpl.enforcePhoneAccountModificationForPackage: the account's package or MODIFY_PHONE_STATE. */
    private fun enforcePhoneAccountModificationForPackage(packageName: String?) {
        if (callerOwns(packageName) || permissions.has(Manifest.permission.MODIFY_PHONE_STATE)) return
        throw SecurityException("Calling uid ${Binder.getCallingUid()} may not modify phone accounts of $packageName")
    }

    /** TelecomServiceImpl.canReadPhoneState: READ_PHONE_STATE (privileged dialers excepted). */
    private fun enforceReadPhoneState(what: String) = permissions.enforceReadPhoneState(what)

    /** PhoneAccountRegistrar.phoneAccountRequiresBindPermission. */
    private fun connectionServiceRequiresBindPermission(component: ComponentName?): Boolean {
        val info = packages.getServiceInfo(component ?: return false, 0L, UserHandle.USER_SYSTEM) ?: return false
        return info.permission == Manifest.permission.BIND_TELECOM_CONNECTION_SERVICE
    }

    private fun handles(filter: (PhoneAccount) -> Boolean): ParceledListSlice<PhoneAccountHandle> =
        ParceledListSlice(synchronized(lock) { accounts.values.filter(filter).map { it.accountHandle } })

    // ------------------------------------------------------------ phone accounts

    /** TelecomServiceImpl.registerPhoneAccount + PhoneAccountRegistrar.registerPhoneAccount. */
    override fun registerPhoneAccount(metadata: PhoneAccount?, callingPackage: String?) {
        val account = metadata ?: throw IllegalArgumentException("PhoneAccount must not be null")
        val handle = account.accountHandle ?: throw IllegalArgumentException("PhoneAccountHandle must not be null")
        enforcePhoneAccountModificationForPackage(handle.componentName?.packageName)
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) permissions.enforce(Manifest.permission.REGISTER_SIM_SUBSCRIPTION, "registerPhoneAccount")
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)) permissions.enforce(Manifest.permission.REGISTER_CONNECTION_MANAGER, "registerPhoneAccount")
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) {
            permissions.enforce(Manifest.permission.MANAGE_OWN_CALLS, "registerPhoneAccount")
            if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) ||
                account.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER) ||
                account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
            ) {
                throw SecurityException("Self-managed ConnectionServices cannot also be call capable, connection managers, or SIM accounts.")
            }
        }
        if (!connectionServiceRequiresBindPermission(handle.componentName)) {
            throw SecurityException("PhoneAccount connection service requires ${Manifest.permission.BIND_TELECOM_CONNECTION_SERVICE}")
        }
        synchronized(lock) {
            // PhoneAccountRegistrar: self-managed and SIM accounts start enabled; a call
            // provider keeps its previous enabled state (off until the user enables it).
            val enabled = account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED) ||
                account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) ||
                accounts[handle]?.isEnabled == true
            accounts[handle] = PhoneAccount.Builder(account).setIsEnabled(enabled).build()
        }
        Log.i(TAG, "phone account registered: $handle")
    }

    override fun unregisterPhoneAccount(account: PhoneAccountHandle?, callingPackage: String?) {
        val handle = account ?: return
        enforcePhoneAccountModificationForPackage(handle.componentName?.packageName)
        synchronized(lock) { accounts.remove(handle) }
    }

    override fun clearAccounts(packageName: String?) {
        enforcePhoneAccountModificationForPackage(packageName)
        synchronized(lock) { accounts.keys.removeAll { it.componentName?.packageName == packageName } }
    }

    override fun enablePhoneAccount(accountHandle: PhoneAccountHandle?, isEnabled: Boolean): Boolean {
        permissions.enforceModifyPhoneState("enablePhoneAccount")
        synchronized(lock) {
            val account = accounts[accountHandle ?: return false] ?: return false
            accounts[accountHandle] = PhoneAccount.Builder(account).setIsEnabled(isEnabled).build()
        }
        return true
    }

    override fun getPhoneAccount(account: PhoneAccountHandle?, callingPackage: String?): PhoneAccount? {
        val handle = account ?: return null
        if (!callerOwns(handle.componentName?.packageName)) enforceReadPhoneState("getPhoneAccount")
        return synchronized(lock) { accounts[handle] }
    }

    override fun getCallCapablePhoneAccounts(includeDisabledAccounts: Boolean, callingPackage: String?, callingFeatureId: String?, acrossProfiles: Boolean): ParceledListSlice<PhoneAccountHandle>? {
        enforceReadPhoneState("getCallCapablePhoneAccounts")
        if (includeDisabledAccounts) permissions.enforcePrivilegedPhoneState("getCallCapablePhoneAccounts(includeDisabledAccounts)")
        return handles {
            it.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) &&
                !it.hasCapabilities(PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY) &&
                (includeDisabledAccounts || it.isEnabled)
        }
    }

    override fun getSelfManagedPhoneAccounts(callingPackage: String?, callingFeatureId: String?): ParceledListSlice<PhoneAccountHandle>? {
        enforceReadPhoneState("getSelfManagedPhoneAccounts")
        return handles { it.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED) }
    }

    override fun getOwnSelfManagedPhoneAccounts(callingPackage: String?, callingFeatureId: String?): ParceledListSlice<PhoneAccountHandle>? {
        permissions.enforce(Manifest.permission.MANAGE_OWN_CALLS, "getOwnSelfManagedPhoneAccounts")
        return handles { it.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED) && it.accountHandle.componentName?.packageName == callingPackage }
    }

    override fun getPhoneAccountsSupportingScheme(uriScheme: String?, callingPackage: String?): ParceledListSlice<PhoneAccountHandle>? {
        permissions.enforceModifyPhoneState("getPhoneAccountsSupportingScheme")
        return handles { it.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) && it.isEnabled && (uriScheme == null || it.supportsUriScheme(uriScheme)) }
    }

    override fun getPhoneAccountsForPackage(packageName: String?): ParceledListSlice<PhoneAccountHandle>? {
        if (!callerOwns(packageName)) permissions.enforcePrivilegedPhoneState("getPhoneAccountsForPackage")
        return handles { it.accountHandle.componentName?.packageName == packageName }
    }

    override fun getRegisteredPhoneAccounts(callingPackage: String?, callingFeatureId: String?): ParceledListSlice<PhoneAccount>? =
        ParceledListSlice(synchronized(lock) { accounts.values.filter { it.accountHandle.componentName?.packageName == callingPackage } })

    override fun getAllPhoneAccountsCount(): Int {
        permissions.enforceModifyPhoneState("getAllPhoneAccountsCount")
        return synchronized(lock) { accounts.size }
    }

    override fun getAllPhoneAccounts(): ParceledListSlice<PhoneAccount>? {
        permissions.enforceModifyPhoneState("getAllPhoneAccounts")
        return ParceledListSlice(synchronized(lock) { ArrayList(accounts.values) })
    }

    override fun getAllPhoneAccountHandles(): ParceledListSlice<PhoneAccountHandle>? {
        permissions.enforceModifyPhoneState("getAllPhoneAccountHandles")
        return handles { true }
    }

    override fun getDefaultOutgoingPhoneAccount(uriScheme: String?, callingPackage: String?, callingFeatureId: String?): PhoneAccountHandle? {
        enforceReadPhoneState("getDefaultOutgoingPhoneAccount")
        return null
    }
    override fun getUserSelectedOutgoingPhoneAccount(callingPackage: String?): PhoneAccountHandle? = null
    override fun setUserSelectedOutgoingPhoneAccount(account: PhoneAccountHandle?) = permissions.enforceModifyPhoneState("setUserSelectedOutgoingPhoneAccount")
    override fun getSimCallManager(subId: Int, callingPackage: String?): PhoneAccountHandle? = null
    override fun getSimCallManagerForUser(userId: Int, callingPackage: String?): PhoneAccountHandle? = null

    // ------------------------------------------------------------ dialer and call state

    override fun getDefaultPhoneApp(): ComponentName? = null
    override fun getDefaultDialerPackage(callingPackage: String?): String? = null
    override fun getDefaultDialerPackageForUser(userId: Int): String? {
        permissions.enforceModifyPhoneState("getDefaultDialerPackageForUser")
        return null
    }
    override fun getSystemDialerPackage(callingPackage: String?): String? = null
    override fun setDefaultDialer(packageName: String?): Boolean {
        permissions.enforceModifyPhoneState("setDefaultDialer")
        permissions.enforce(Manifest.permission.WRITE_SECURE_SETTINGS, "setDefaultDialer")
        return false
    }
    override fun isInCall(callingPackage: String?, callingFeatureId: String?): Boolean {
        enforceReadPhoneState("isInCall")
        return false
    }
    override fun isInManagedCall(callingPackage: String?, callingFeatureId: String?): Boolean {
        enforceReadPhoneState("isInManagedCall")
        return false
    }
    override fun isRinging(callingPackage: String?): Boolean {
        enforceReadPhoneState("isRinging")
        return false
    }
    override fun isInEmergencyCall(): Boolean {
        permissions.enforcePrivilegedPhoneState("isInEmergencyCall")
        return false
    }
    override fun isInSelfManagedCall(packageName: String?, userHandle: UserHandle?, callingPackage: String?): Boolean {
        permissions.enforcePrivilegedPhoneState("isInSelfManagedCall")
        return false
    }
    override fun hasManageOngoingCallsPermission(callingPackage: String?): Boolean = permissions.has(Manifest.permission.MANAGE_ONGOING_CALLS)
    override fun getCallState(): Int = TelephonyManager.CALL_STATE_IDLE
    override fun getCallStateUsingPackage(callingPackage: String?, callingFeatureId: String?): Int {
        enforceReadPhoneState("getCallState")
        return TelephonyManager.CALL_STATE_IDLE
    }
    override fun endCall(callingPackage: String?): Boolean {
        if (!permissions.has(Manifest.permission.ANSWER_PHONE_CALLS)) permissions.enforceModifyPhoneState("endCall")
        return false
    }
    override fun acceptRingingCall(callingPackage: String?) {
        if (!permissions.has(Manifest.permission.ANSWER_PHONE_CALLS)) permissions.enforceModifyPhoneState("acceptRingingCall")
    }
    override fun acceptRingingCallWithVideoState(callingPackage: String?, videoState: Int) = acceptRingingCall(callingPackage)
    override fun silenceRinger(callingPackage: String?) = permissions.enforceModifyPhoneState("silenceRinger")
    override fun cancelMissedCallsNotification(callingPackage: String?) = permissions.enforceModifyPhoneState("cancelMissedCallsNotification")
    override fun showInCallScreen(showDialpad: Boolean, callingPackage: String?, callingFeatureId: String?) = enforceReadPhoneState("showInCallScreen")
    override fun isTtySupported(callingPackage: String?, callingFeatureId: String?): Boolean {
        enforceReadPhoneState("isTtySupported")
        return false
    }
    override fun getCurrentTtyMode(callingPackage: String?, callingFeatureId: String?): Int {
        enforceReadPhoneState("getCurrentTtyMode")
        return TelecomManager.TTY_MODE_OFF
    }
    override fun isVoiceMailNumber(accountHandle: PhoneAccountHandle?, number: String?, callingPackage: String?, callingFeatureId: String?): Boolean {
        enforceReadPhoneState("isVoiceMailNumber")
        return false
    }
    override fun getVoiceMailNumber(accountHandle: PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): String? {
        enforceReadPhoneState("getVoiceMailNumber")
        return null
    }
    override fun getLine1Number(accountHandle: PhoneAccountHandle?, callingPackage: String?, callingFeatureId: String?): String? {
        permissions.enforceReadPhoneNumber("getLine1Number")
        return null
    }
    override fun getAdnUriForPhoneAccount(accountHandle: PhoneAccountHandle?, callingPackage: String?): Uri? = null
    override fun handlePinMmi(dialString: String?, callingPackage: String?): Boolean {
        permissions.enforceModifyPhoneState("handlePinMmi")
        return false
    }
    override fun handlePinMmiForPhoneAccount(accountHandle: PhoneAccountHandle?, dialString: String?, callingPackage: String?): Boolean {
        permissions.enforceModifyPhoneState("handlePinMmiForPhoneAccount")
        return false
    }

    /** CallsManager.isIncomingCallPermitted: a registered account with no calls in progress. */
    override fun isIncomingCallPermitted(phoneAccountHandle: PhoneAccountHandle?, callingPackage: String?): Boolean {
        permissions.enforce(Manifest.permission.MANAGE_OWN_CALLS, "isIncomingCallPermitted")
        return synchronized(lock) { accounts.containsKey(phoneAccountHandle ?: return false) }
    }

    /** CallsManager.isOutgoingCallPermitted: a registered account with no calls in progress. */
    override fun isOutgoingCallPermitted(phoneAccountHandle: PhoneAccountHandle?, callingPackage: String?): Boolean {
        permissions.enforce(Manifest.permission.MANAGE_OWN_CALLS, "isOutgoingCallPermitted")
        return synchronized(lock) { accounts.containsKey(phoneAccountHandle ?: return false) }
    }

    override fun hasForegroundServiceDelegation(phoneAccountHandle: PhoneAccountHandle?, callingPackage: String?): Boolean = false

    /**
     * TelecomServiceImpl.placeCall: CALL_PHONE (or MANAGE_OWN_CALLS for a self-managed
     * account), then the call intent goes to CallsManager, which has no
     * ConnectionService to carry it here; the call is not placed.
     */
    override fun placeCall(handle: Uri?, extras: Bundle?, callingPackage: String?, callingFeatureId: String?) {
        if (!permissions.has(Manifest.permission.CALL_PHONE)) permissions.enforce(Manifest.permission.MANAGE_OWN_CALLS, "placeCall")
        Log.w(TAG, "placeCall from $callingPackage to ${handle?.scheme}: no phone account can carry a call on this host; not placed")
    }

    override fun createManageBlockedNumbersIntent(callingPackage: String?): Intent? = null

    override fun createLaunchEmergencyDialerIntent(number: String?): Intent? {
        val intent = Intent(Intent.ACTION_DIAL_EMERGENCY)
        if (!number.isNullOrEmpty()) intent.data = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
        return intent
    }

    private companion object {
        const val TAG = "HostedTelecomService"
    }
}
