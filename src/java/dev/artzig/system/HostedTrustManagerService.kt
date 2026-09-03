package dev.artzig.system

import android.Manifest
import android.app.trust.ITrustListener
import android.hardware.biometrics.BiometricSourceType
import android.os.IBinder
import android.os.RemoteException
import com.android.internal.policy.IDeviceLockedStateListener

/**
 * The session's ITrustManager over the real AIDL surface, published as
 * `Context.TRUST_SERVICE` (KeyguardManager resolves it at construction).
 *
 * Source note: `com.android.server.trust.TrustManagerService` for a device with no
 * lock screen: never locked, never secure, no trust agents, no biometrics. Listener
 * registrations keep their permission gates and receive the current (unlocked)
 * state on registration; the keyguard reports keep their ACCESS_KEYGUARD_SECURE_STORAGE
 * gate and are recorded only as the locked flag.
 */
class HostedTrustManagerService(private val permissions: HostedPermissions) : UnsupportedTrustManager() {
    private val lock = Any()
    private val trustListeners = LinkedHashMap<IBinder, ITrustListener>()
    private val lockedListeners = LinkedHashMap<IBinder, IDeviceLockedStateListener>()
    private var deviceLocked = false

    private fun enforceReport(what: String) = permissions.enforce(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE, what)

    override fun isDeviceLocked(userId: Int, deviceId: Int): Boolean = synchronized(lock) { deviceLocked }
    override fun isDeviceSecure(userId: Int, deviceId: Int): Boolean = false
    override fun isTrustUsuallyManaged(userId: Int): Boolean = false
    override fun isActiveUnlockRunning(userId: Int): Boolean = false
    override fun isInSignificantPlace(): Boolean = false

    override fun reportUnlockAttempt(successful: Boolean, userId: Int) = enforceReport("reportUnlockAttempt")
    override fun reportUserRequestedUnlock(userId: Int, dismissKeyguard: Boolean) = enforceReport("reportUserRequestedUnlock")
    override fun reportUserMayRequestUnlock(userId: Int) = enforceReport("reportUserMayRequestUnlock")
    override fun reportUnlockLockout(timeoutMs: Int, userId: Int) = enforceReport("reportUnlockLockout")
    override fun reportEnabledTrustAgentsChanged(userId: Int) = enforceReport("reportEnabledTrustAgentsChanged")
    override fun reportKeyguardShowingChanged() = enforceReport("reportKeyguardShowingChanged")
    override fun unlockedByBiometricForUser(userId: Int, source: BiometricSourceType?) = enforceReport("unlockedByBiometricForUser")
    override fun clearAllBiometricRecognized(target: BiometricSourceType?, unlockedUser: Int) = enforceReport("clearAllBiometricRecognized")

    override fun setDeviceLockedForUser(userId: Int, locked: Boolean) {
        enforceReport("setDeviceLockedForUser")
        val listeners = synchronized(lock) {
            if (deviceLocked == locked) return
            deviceLocked = locked
            lockedListeners.entries.map { it.key to it.value }
        }
        for ((binder, listener) in listeners) {
            try { listener.onDeviceLockedStateChanged(locked) } catch (e: RemoteException) { synchronized(lock) { lockedListeners.remove(binder) } }
        }
    }

    override fun registerTrustListener(trustListener: ITrustListener?) {
        permissions.enforce(Manifest.permission.TRUST_LISTENER, "registerTrustListener")
        val binder = trustListener?.asBinder() ?: return
        synchronized(lock) { trustListeners[binder] = trustListener }
    }
    override fun unregisterTrustListener(trustListener: ITrustListener?) {
        permissions.enforce(Manifest.permission.TRUST_LISTENER, "unregisterTrustListener")
        synchronized(lock) { trustListeners.remove(trustListener?.asBinder() ?: return) }
    }

    /** TrustManagerService.registerDeviceLockedStateListener: the current state is delivered at once. */
    override fun registerDeviceLockedStateListener(listener: IDeviceLockedStateListener?, deviceId: Int) {
        permissions.enforce(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, "registerDeviceLockedStateListener")
        val binder = listener?.asBinder() ?: return
        val locked = synchronized(lock) {
            lockedListeners[binder] = listener
            deviceLocked
        }
        try { listener.onDeviceLockedStateChanged(locked) } catch (e: RemoteException) { synchronized(lock) { lockedListeners.remove(binder) } }
    }
    override fun unregisterDeviceLockedStateListener(listener: IDeviceLockedStateListener?) {
        permissions.enforce(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, "unregisterDeviceLockedStateListener")
        synchronized(lock) { lockedListeners.remove(listener?.asBinder() ?: return) }
    }
}
