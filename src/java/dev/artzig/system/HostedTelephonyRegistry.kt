package dev.artzig.system

import android.Manifest
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.telephony.BarringInfo
import android.telephony.DisconnectCause
import android.telephony.PreciseCallState
import android.telephony.PreciseDataConnectionState
import android.telephony.PreciseDisconnectCause
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ICarrierConfigChangeListener
import com.android.internal.telephony.ICarrierPrivilegesCallback
import com.android.internal.telephony.IOnSubscriptionsChangedListener
import com.android.internal.telephony.IPhoneStateListener
import com.android.internal.telephony.ISatelliteStateChangeListener

/**
 * The session's ITelephonyRegistry over the real AIDL surface, published as
 * "telephony.registry".
 *
 * Source note: `com.android.server.TelephonyRegistry` for a device whose phone
 * process never reports anything: the per-phone state stays at the constructor
 * values (service state out of service, call idle, data unknown, radio power
 * unavailable, no signal strength, no cells, no emergency numbers) and a listener
 * registered with `notifyNow` receives exactly the initial callbacks AOSP sends
 * for those values. Listener permission checks follow `checkListenerPermission`
 * (READ_PHONE_STATE, the privileged permission or fine location per event);
 * the `notify*` entry points keep `checkNotifyPermission` (MODIFY_PHONE_STATE,
 * else ignored) and update the state they own so a privileged caller can drive
 * the registered callbacks. The phone count is TelephonyManager's active modem
 * count, as in AOSP.
 */
class HostedTelephonyRegistry(private val permissions: HostedTelephonyPermissions, private val numPhones: Int) : com.android.internal.telephony.ITelephonyRegistry.Stub(), com.android.internal.telephony.ITelephonyRegistry by unsupported<com.android.internal.telephony.ITelephonyRegistry>() {
    override fun asBinder(): android.os.IBinder = this

    private class Record(
        val binder: IBinder,
        val callback: IPhoneStateListener,
        val events: Set<Int>,
        val subId: Int,
        val phoneId: Int,
        val callingPackage: String?,
        val callerUid: Int,
        val hasFineLocation: Boolean,
    )

    private val lock = Any()
    private val records = LinkedHashMap<IBinder, Record>()
    private val subscriptionListeners = LinkedHashMap<IBinder, IOnSubscriptionsChangedListener>()
    private val opportunisticListeners = LinkedHashMap<IBinder, IOnSubscriptionsChangedListener>()
    private val carrierPrivilegesCallbacks = LinkedHashMap<IBinder, Pair<Int, ICarrierPrivilegesCallback>>()
    private val carrierConfigListeners = LinkedHashMap<IBinder, ICarrierConfigChangeListener>()
    private val satelliteListeners = LinkedHashMap<IBinder, ISatelliteStateChangeListener>()
    private var subscriptionInfoChangeOccurred = false
    private var opportunisticSubscriptionInfoChangeOccurred = false

    // TelephonyRegistry's per-phone state at its constructor values.
    private val serviceStates = Array(numPhones) { ServiceState() }
    private val signalStrengths = arrayOfNulls<SignalStrength>(numPhones)
    private val messageWaiting = BooleanArray(numPhones)
    private val callForwarding = BooleanArray(numPhones)
    private val callStates = IntArray(numPhones) { TelephonyManager.CALL_STATE_IDLE }
    private val callIncomingNumbers = Array(numPhones) { "" }
    private val dataConnectionStates = IntArray(numPhones) { TelephonyManager.DATA_UNKNOWN }
    private val dataConnectionNetworkTypes = IntArray(numPhones) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
    private val dataActivities = IntArray(numPhones) { TelephonyManager.DATA_ACTIVITY_NONE }
    private val userMobileDataStates = BooleanArray(numPhones)
    private val voiceActivationStates = IntArray(numPhones) { TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN }
    private val dataActivationStates = IntArray(numPhones) { TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN }
    private val carrierNetworkChangeStates = BooleanArray(numPhones)
    private val displayInfos = arrayOfNulls<TelephonyDisplayInfo>(numPhones)
    private val preciseCallStates = Array(numPhones) { PreciseCallState() }
    private val barringInfos = Array(numPhones) { BarringInfo() }
    private val dataEnabled = BooleanArray(numPhones)
    private val dataEnabledReasons = IntArray(numPhones) { TelephonyManager.DATA_ENABLED_REASON_USER }
    private var radioPowerState = TelephonyManager.RADIO_POWER_UNAVAILABLE
    private var activeDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var satelliteEnabled = false

    private fun validatePhoneId(phoneId: Int): Boolean = phoneId in 0 until numPhones

    /** TelephonyRegistry.getPhoneIdFromSubId: an invalid subscription maps to the default phone. */
    private fun phoneIdForSubId(subId: Int): Int =
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID || !SubscriptionManager.isValidSubscriptionId(subId)) 0 else SubscriptionManager.INVALID_PHONE_INDEX

    /** TelephonyRegistry.checkNotifyPermission: MODIFY_PHONE_STATE, otherwise the notification is dropped. */
    private fun checkNotifyPermission(method: String): Boolean {
        if (permissions.has(Manifest.permission.MODIFY_PHONE_STATE)) return true
        Log.w(TAG, "$method: uid ${Binder.getCallingUid()} lacks MODIFY_PHONE_STATE; ignored")
        return false
    }

    /** TelephonyRegistry.checkListenerPermission. */
    private fun checkListenerPermission(events: Set<Int>, callingPackage: String?) {
        if (events.any { it in PRIVILEGED_EVENTS }) permissions.enforcePrivilegedPhoneState("listen")
        if (events.any { it in PHONE_STATE_EVENTS }) permissions.enforceReadPhoneState("listen")
        if (events.any { it in LOCATION_EVENTS } && !permissions.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            throw SecurityException("listen: $callingPackage requires android.permission.ACCESS_FINE_LOCATION for cell location or cell info events")
        }
    }

    private fun remove(binder: IBinder) {
        synchronized(lock) {
            records.remove(binder)
            subscriptionListeners.remove(binder)
            opportunisticListeners.remove(binder)
            carrierPrivilegesCallbacks.remove(binder)
            carrierConfigListeners.remove(binder)
            satelliteListeners.remove(binder)
        }
    }

    private fun watch(binder: IBinder) {
        try {
            binder.linkToDeath({ remove(binder) }, 0)
        } catch (e: RemoteException) {
            remove(binder)
        }
    }

    private inline fun deliver(record: Record, block: (IPhoneStateListener) -> Unit) {
        try {
            block(record.callback)
        } catch (e: RemoteException) {
            remove(record.binder)
        }
    }

    private fun serviceStateFor(record: Record, phoneId: Int): ServiceState {
        val raw = ServiceState(serviceStates[phoneId])
        return if (record.hasFineLocation) raw else raw.createLocationInfoSanitizedCopy(true)
    }

    // ------------------------------------------------------------ listeners

    override fun listenWithEventList(renounceFineLocationAccess: Boolean, renounceCoarseLocationAccess: Boolean, subId: Int, pkg: String?, featureId: String?, callback: IPhoneStateListener?, events: IntArray?, notifyNow: Boolean) {
        val eventSet = events?.toSet() ?: emptySet()
        checkListenerPermission(eventSet, pkg)
        val binder = callback?.asBinder() ?: return
        if (eventSet.isEmpty()) {
            remove(binder)
            return
        }
        val phoneId = phoneIdForSubId(subId)
        val record = Record(
            binder, callback, eventSet, subId, phoneId, pkg, Binder.getCallingUid(),
            hasFineLocation = !renounceFineLocationAccess && permissions.has(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        synchronized(lock) {
            if (records.put(binder, record) == null) watch(binder)
            if (!notifyNow || !validatePhoneId(phoneId)) return
            notifyInitialLocked(record, phoneId)
        }
    }

    /** TelephonyRegistry.listen's notifyNow block for the events this host tracks. */
    private fun notifyInitialLocked(r: Record, phoneId: Int) {
        val e = r.events
        if (TelephonyCallback.EVENT_SERVICE_STATE_CHANGED in e) deliver(r) { it.onServiceStateChanged(serviceStateFor(r, phoneId)) }
        if (TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED in e) signalStrengths[phoneId]?.let { s -> deliver(r) { it.onSignalStrengthChanged(s.gsmSignalStrength.let { g -> if (g == 99) -1 else g }) } }
        if (TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED in e) deliver(r) { it.onMessageWaitingIndicatorChanged(messageWaiting[phoneId]) }
        if (TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED in e) deliver(r) { it.onCallForwardingIndicatorChanged(callForwarding[phoneId]) }
        if (TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED in e) deliver(r) { it.onLegacyCallStateChanged(callStates[phoneId], incomingNumberFor(r, phoneId)) }
        if (TelephonyCallback.EVENT_CALL_STATE_CHANGED in e) deliver(r) { it.onCallStateChanged(callStates[phoneId]) }
        if (TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED in e) deliver(r) { it.onDataConnectionStateChanged(dataConnectionStates[phoneId], dataConnectionNetworkTypes[phoneId]) }
        if (TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED in e) deliver(r) { it.onDataActivity(dataActivities[phoneId]) }
        if (TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED in e) signalStrengths[phoneId]?.let { s -> deliver(r) { it.onSignalStrengthsChanged(s) } }
        if (TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED in e) deliver(r) { it.onPreciseCallStateChanged(preciseCallStates[phoneId]) }
        if (TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED in e) deliver(r) { it.onCarrierNetworkChange(carrierNetworkChangeStates[phoneId]) }
        if (TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED in e) deliver(r) { it.onVoiceActivationStateChanged(voiceActivationStates[phoneId]) }
        if (TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED in e) deliver(r) { it.onDataActivationStateChanged(dataActivationStates[phoneId]) }
        if (TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED in e) deliver(r) { it.onUserMobileDataStateChanged(userMobileDataStates[phoneId]) }
        if (TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED in e) displayInfos[phoneId]?.let { d -> deliver(r) { it.onDisplayInfoChanged(d) } }
        if (TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED in e) deliver(r) { it.onEmergencyNumberListChanged(HashMap<Int, Any>()) }
        if (TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED in e) deliver(r) { it.onActiveDataSubIdChanged(activeDataSubId) }
        if (TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED in e) deliver(r) { it.onRadioPowerStateChanged(radioPowerState) }
        if (TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED in e) deliver(r) { it.onCallDisconnectCauseChanged(DisconnectCause.NOT_VALID, PreciseDisconnectCause.NOT_VALID) }
        if (TelephonyCallback.EVENT_BARRING_INFO_CHANGED in e) deliver(r) { it.onBarringInfoChanged(if (r.hasFineLocation) barringInfos[phoneId] else barringInfos[phoneId].createLocationInfoSanitizedCopy()) }
        if (TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED in e) deliver(r) { it.onPhysicalChannelConfigChanged(ArrayList()) }
        if (TelephonyCallback.EVENT_DATA_ENABLED_CHANGED in e) deliver(r) { it.onDataEnabledChanged(dataEnabled[phoneId], dataEnabledReasons[phoneId]) }
    }

    /** TelephonyRegistry.getCallIncomingNumber: the number only for callers that may read it. */
    private fun incomingNumberFor(r: Record, phoneId: Int): String =
        if (permissions.has(Manifest.permission.READ_CALL_LOG, r.callerUid)) callIncomingNumbers[phoneId] else ""

    private inline fun forEachRecord(phoneId: Int, event: Int, block: (Record) -> Unit) {
        val snapshot = synchronized(lock) { records.values.filter { it.phoneId == phoneId && event in it.events } }
        for (r in snapshot) block(r)
    }

    override fun addOnSubscriptionsChangedListener(pkg: String?, featureId: String?, callback: IOnSubscriptionsChangedListener?) {
        val binder = callback?.asBinder() ?: return
        val notify = synchronized(lock) {
            if (subscriptionListeners.put(binder, callback) == null) watch(binder)
            subscriptionInfoChangeOccurred
        }
        if (notify) try { callback.onSubscriptionsChanged() } catch (e: RemoteException) { remove(binder) }
    }

    override fun addOnOpportunisticSubscriptionsChangedListener(pkg: String?, featureId: String?, callback: IOnSubscriptionsChangedListener?) {
        val binder = callback?.asBinder() ?: return
        val notify = synchronized(lock) {
            if (opportunisticListeners.put(binder, callback) == null) watch(binder)
            opportunisticSubscriptionInfoChangeOccurred
        }
        if (notify) try { callback.onSubscriptionsChanged() } catch (e: RemoteException) { remove(binder) }
    }

    override fun removeOnSubscriptionsChangedListener(pkg: String?, callback: IOnSubscriptionsChangedListener?) {
        remove(callback?.asBinder() ?: return)
    }

    /** TelephonyRegistry.addCarrierPrivilegesCallback: privileged, and told the current (empty) state at once. */
    override fun addCarrierPrivilegesCallback(phoneId: Int, callback: ICarrierPrivilegesCallback?, pkg: String?, featureId: String?) {
        permissions.enforcePrivilegedPhoneState("addCarrierPrivilegesCallback")
        require(validatePhoneId(phoneId)) { "Invalid slot index: $phoneId" }
        val binder = callback?.asBinder() ?: return
        synchronized(lock) { if (carrierPrivilegesCallbacks.put(binder, phoneId to callback) == null) watch(binder) }
        try {
            callback.onCarrierPrivilegesChanged(ArrayList(), IntArray(0))
            callback.onCarrierServiceChanged(null, -1)
        } catch (e: RemoteException) {
            remove(binder)
        }
    }

    override fun removeCarrierPrivilegesCallback(callback: ICarrierPrivilegesCallback?, pkg: String?) {
        permissions.enforcePrivilegedPhoneState("removeCarrierPrivilegesCallback")
        remove(callback?.asBinder() ?: return)
    }

    override fun addCarrierConfigChangeListener(listener: ICarrierConfigChangeListener?, pkg: String?, featureId: String?) {
        val binder = listener?.asBinder() ?: return
        synchronized(lock) { if (carrierConfigListeners.put(binder, listener) == null) watch(binder) }
    }

    override fun removeCarrierConfigChangeListener(listener: ICarrierConfigChangeListener?, pkg: String?) {
        remove(listener?.asBinder() ?: return)
    }

    /** TelephonyRegistry.addSatelliteStateChangeListener: the current state is delivered at once. */
    override fun addSatelliteStateChangeListener(listener: ISatelliteStateChangeListener?, pkg: String?, featureId: String?) {
        val binder = listener?.asBinder() ?: return
        val enabled = synchronized(lock) {
            if (satelliteListeners.put(binder, listener) == null) watch(binder)
            satelliteEnabled
        }
        try { listener.onSatelliteEnabledStateChanged(enabled) } catch (e: RemoteException) { remove(binder) }
    }

    override fun removeSatelliteStateChangeListener(listener: ISatelliteStateChangeListener?, pkg: String?) {
        remove(listener?.asBinder() ?: return)
    }

    // ------------------------------------------------------------ notifications (privileged)

    override fun notifyCallStateForAllSubs(state: Int, incomingNumber: String?) {
        if (!checkNotifyPermission("notifyCallStateForAllSubs")) return
        for (phoneId in 0 until numPhones) updateCallState(phoneId, state, incomingNumber)
    }

    override fun notifyCallState(phoneId: Int, subId: Int, state: Int, incomingNumber: String?) {
        if (!checkNotifyPermission("notifyCallState") || !validatePhoneId(phoneId)) return
        updateCallState(phoneId, state, incomingNumber)
    }

    private fun updateCallState(phoneId: Int, state: Int, incomingNumber: String?) {
        synchronized(lock) {
            callStates[phoneId] = state
            callIncomingNumbers[phoneId] = incomingNumber ?: ""
        }
        forEachRecord(phoneId, TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED) { r -> deliver(r) { it.onLegacyCallStateChanged(state, incomingNumberFor(r, phoneId)) } }
        forEachRecord(phoneId, TelephonyCallback.EVENT_CALL_STATE_CHANGED) { r -> deliver(r) { it.onCallStateChanged(state) } }
    }

    override fun notifyServiceStateForPhoneId(phoneId: Int, subId: Int, state: ServiceState?) {
        if (!checkNotifyPermission("notifyServiceStateForPhoneId") || !validatePhoneId(phoneId) || state == null) return
        synchronized(lock) { serviceStates[phoneId] = ServiceState(state) }
        forEachRecord(phoneId, TelephonyCallback.EVENT_SERVICE_STATE_CHANGED) { r -> deliver(r) { it.onServiceStateChanged(serviceStateFor(r, phoneId)) } }
    }

    override fun notifySignalStrengthForPhoneId(phoneId: Int, subId: Int, signalStrength: SignalStrength?) {
        if (!checkNotifyPermission("notifySignalStrengthForPhoneId") || !validatePhoneId(phoneId) || signalStrength == null) return
        synchronized(lock) { signalStrengths[phoneId] = signalStrength }
        forEachRecord(phoneId, TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED) { r -> deliver(r) { it.onSignalStrengthsChanged(signalStrength) } }
        val gsm = signalStrength.gsmSignalStrength
        forEachRecord(phoneId, TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED) { r -> deliver(r) { it.onSignalStrengthChanged(if (gsm == 99) -1 else gsm) } }
    }

    override fun notifyMessageWaitingChangedForPhoneId(phoneId: Int, subId: Int, mwi: Boolean) {
        if (!checkNotifyPermission("notifyMessageWaitingChanged") || !validatePhoneId(phoneId)) return
        synchronized(lock) { messageWaiting[phoneId] = mwi }
        forEachRecord(phoneId, TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED) { r -> deliver(r) { it.onMessageWaitingIndicatorChanged(mwi) } }
    }

    override fun notifyCallForwardingChanged(cfi: Boolean) = notifyCallForwardingChangedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cfi)

    override fun notifyCallForwardingChangedForSubscriber(subId: Int, cfi: Boolean) {
        if (!checkNotifyPermission("notifyCallForwardingChanged")) return
        val phoneId = phoneIdForSubId(subId)
        if (!validatePhoneId(phoneId)) return
        synchronized(lock) { callForwarding[phoneId] = cfi }
        forEachRecord(phoneId, TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED) { r -> deliver(r) { it.onCallForwardingIndicatorChanged(cfi) } }
    }

    override fun notifyDataActivityForSubscriber(subId: Int, state: Int) = notifyDataActivityForSubscriberWithSlot(phoneIdForSubId(subId), subId, state)

    override fun notifyDataActivityForSubscriberWithSlot(phoneId: Int, subId: Int, state: Int) {
        if (!checkNotifyPermission("notifyDataActivity") || !validatePhoneId(phoneId)) return
        synchronized(lock) { dataActivities[phoneId] = state }
        forEachRecord(phoneId, TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED) { r -> deliver(r) { it.onDataActivity(state) } }
    }

    override fun notifyDataConnectionForSubscriber(phoneId: Int, subId: Int, preciseState: PreciseDataConnectionState?) {
        if (!checkNotifyPermission("notifyDataConnection") || !validatePhoneId(phoneId) || preciseState == null) return
        synchronized(lock) {
            dataConnectionStates[phoneId] = preciseState.state
            dataConnectionNetworkTypes[phoneId] = preciseState.networkType
        }
        forEachRecord(phoneId, TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED) { r -> deliver(r) { it.onDataConnectionStateChanged(preciseState.state, preciseState.networkType) } }
        forEachRecord(phoneId, TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED) { r -> deliver(r) { it.onPreciseDataConnectionStateChanged(preciseState) } }
    }

    override fun notifyUserMobileDataStateChangedForPhoneId(phoneId: Int, subId: Int, state: Boolean) {
        if (!checkNotifyPermission("notifyUserMobileDataStateChanged") || !validatePhoneId(phoneId)) return
        synchronized(lock) { userMobileDataStates[phoneId] = state }
        forEachRecord(phoneId, TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED) { r -> deliver(r) { it.onUserMobileDataStateChanged(state) } }
    }

    override fun notifyDisplayInfoChanged(slotIndex: Int, subId: Int, telephonyDisplayInfo: TelephonyDisplayInfo?) {
        if (!checkNotifyPermission("notifyDisplayInfoChanged") || !validatePhoneId(slotIndex) || telephonyDisplayInfo == null) return
        synchronized(lock) { displayInfos[slotIndex] = telephonyDisplayInfo }
        forEachRecord(slotIndex, TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED) { r -> deliver(r) { it.onDisplayInfoChanged(telephonyDisplayInfo) } }
    }

    override fun notifyRadioPowerStateChanged(phoneId: Int, subId: Int, state: Int) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged") || !validatePhoneId(phoneId)) return
        synchronized(lock) { radioPowerState = state }
        forEachRecord(phoneId, TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED) { r -> deliver(r) { it.onRadioPowerStateChanged(state) } }
    }

    override fun notifyDataEnabled(phoneId: Int, subId: Int, enabled: Boolean, reason: Int) {
        if (!checkNotifyPermission("notifyDataEnabled") || !validatePhoneId(phoneId)) return
        synchronized(lock) {
            dataEnabled[phoneId] = enabled
            dataEnabledReasons[phoneId] = reason
        }
        forEachRecord(phoneId, TelephonyCallback.EVENT_DATA_ENABLED_CHANGED) { r -> deliver(r) { it.onDataEnabledChanged(enabled, reason) } }
    }

    override fun notifyActiveDataSubIdChanged(activeDataSubId: Int) {
        if (!checkNotifyPermission("notifyActiveDataSubIdChanged")) return
        synchronized(lock) { this.activeDataSubId = activeDataSubId }
        for (phoneId in 0 until numPhones) {
            forEachRecord(phoneId, TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED) { r -> deliver(r) { it.onActiveDataSubIdChanged(activeDataSubId) } }
        }
    }

    override fun notifyCarrierNetworkChange(active: Boolean) {
        if (!checkNotifyPermission("notifyCarrierNetworkChange")) return
        for (phoneId in 0 until numPhones) updateCarrierNetworkChange(phoneId, active)
    }

    override fun notifyCarrierNetworkChangeWithSubId(subId: Int, active: Boolean) {
        if (!checkNotifyPermission("notifyCarrierNetworkChangeWithSubId")) return
        val phoneId = phoneIdForSubId(subId)
        if (validatePhoneId(phoneId)) updateCarrierNetworkChange(phoneId, active)
    }

    private fun updateCarrierNetworkChange(phoneId: Int, active: Boolean) {
        synchronized(lock) { carrierNetworkChangeStates[phoneId] = active }
        forEachRecord(phoneId, TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED) { r -> deliver(r) { it.onCarrierNetworkChange(active) } }
    }

    override fun notifyEmergencyNumberList(phoneId: Int, subId: Int) {
        if (!checkNotifyPermission("notifyEmergencyNumberList") || !validatePhoneId(phoneId)) return
        forEachRecord(phoneId, TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED) { r -> deliver(r) { it.onEmergencyNumberListChanged(HashMap<Int, Any>()) } }
    }

    override fun notifySubscriptionInfoChanged() {
        if (!checkNotifyPermission("notifySubscriptionInfoChanged")) return
        val listeners = synchronized(lock) {
            subscriptionInfoChangeOccurred = true
            subscriptionListeners.entries.map { it.key to it.value }
        }
        for ((binder, listener) in listeners) try { listener.onSubscriptionsChanged() } catch (e: RemoteException) { remove(binder) }
    }

    override fun notifyOpportunisticSubscriptionInfoChanged() {
        if (!checkNotifyPermission("notifyOpportunisticSubscriptionInfoChanged")) return
        val listeners = synchronized(lock) {
            opportunisticSubscriptionInfoChangeOccurred = true
            opportunisticListeners.entries.map { it.key to it.value }
        }
        for ((binder, listener) in listeners) try { listener.onSubscriptionsChanged() } catch (e: RemoteException) { remove(binder) }
    }

    override fun notifyCarrierConfigChanged(phoneId: Int, subId: Int, carrierId: Int, specificCarrierId: Int) {
        if (!checkNotifyPermission("notifyCarrierConfigChanged") || !validatePhoneId(phoneId)) return
        val listeners = synchronized(lock) { carrierConfigListeners.entries.map { it.key to it.value } }
        for ((binder, listener) in listeners) try { listener.onCarrierConfigChanged(phoneId, subId, carrierId, specificCarrierId) } catch (e: RemoteException) { remove(binder) }
    }

    override fun notifySatelliteStateChanged(isEnabled: Boolean) {
        if (!checkNotifyPermission("notifySatelliteStateChanged")) return
        val listeners = synchronized(lock) {
            satelliteEnabled = isEnabled
            satelliteListeners.entries.map { it.key to it.value }
        }
        for ((binder, listener) in listeners) try { listener.onSatelliteEnabledStateChanged(isEnabled) } catch (e: RemoteException) { remove(binder) }
    }

    private companion object {
        const val TAG = "HostedTelephonyRegistry"

        /** TelephonyRegistry.isPrivilegedPhoneStatePermissionRequired. */
        val PRIVILEGED_EVENTS = setOf(
            TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED, TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED,
            TelephonyCallback.EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED, TelephonyCallback.EVENT_OEM_HOOK_RAW,
            TelephonyCallback.EVENT_SRVCC_STATE_CHANGED, TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED,
            TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED, TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED,
            TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED, TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED,
            TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED, TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL,
            TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS, TelephonyCallback.EVENT_REGISTRATION_FAILURE,
            TelephonyCallback.EVENT_BARRING_INFO_CHANGED, TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED,
            TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED, TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED,
            TelephonyCallback.EVENT_MEDIA_QUALITY_STATUS_CHANGED, TelephonyCallback.EVENT_EMERGENCY_CALLBACK_MODE_CHANGED,
            TelephonyCallback.EVENT_SIMULTANEOUS_CELLULAR_CALLING_SUBSCRIPTIONS_CHANGED, TelephonyCallback.EVENT_SECURITY_ALGORITHMS_CHANGED,
            TelephonyCallback.EVENT_CELLULAR_IDENTIFIER_DISCLOSED_CHANGED, TelephonyCallback.EVENT_CARRIER_ROAMING_NTN_SIGNAL_STRENGTH_CHANGED,
        )

        /** TelephonyRegistry.isPhoneStatePermissionRequired. */
        val PHONE_STATE_EVENTS = setOf(
            TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED, TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED,
            TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED, TelephonyCallback.EVENT_CALL_STATE_CHANGED,
            TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED, TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED,
            TelephonyCallback.EVENT_DATA_ENABLED_CHANGED, TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED,
        )

        /** TelephonyRegistry.isLocationPermissionRequired. */
        val LOCATION_EVENTS = setOf(TelephonyCallback.EVENT_CELL_LOCATION_CHANGED, TelephonyCallback.EVENT_CELL_INFO_CHANGED)
    }
}
