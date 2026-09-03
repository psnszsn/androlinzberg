@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ITrustManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedTrustManager : android.app.trust.ITrustManager.Stub() {
    override fun reportUnlockAttempt(successful: Boolean, userId: Int) { throw UnsupportedOperationException("ITrustManager.reportUnlockAttempt is not hosted") }
    override fun reportUserRequestedUnlock(userId: Int, dismissKeyguard: Boolean) { throw UnsupportedOperationException("ITrustManager.reportUserRequestedUnlock is not hosted") }
    override fun reportUserMayRequestUnlock(userId: Int) { throw UnsupportedOperationException("ITrustManager.reportUserMayRequestUnlock is not hosted") }
    override fun reportUnlockLockout(timeoutMs: Int, userId: Int) { throw UnsupportedOperationException("ITrustManager.reportUnlockLockout is not hosted") }
    override fun reportEnabledTrustAgentsChanged(userId: Int) { throw UnsupportedOperationException("ITrustManager.reportEnabledTrustAgentsChanged is not hosted") }
    override fun registerTrustListener(trustListener: android.app.trust.ITrustListener?) { throw UnsupportedOperationException("ITrustManager.registerTrustListener is not hosted") }
    override fun unregisterTrustListener(trustListener: android.app.trust.ITrustListener?) { throw UnsupportedOperationException("ITrustManager.unregisterTrustListener is not hosted") }
    override fun reportKeyguardShowingChanged() { throw UnsupportedOperationException("ITrustManager.reportKeyguardShowingChanged is not hosted") }
    override fun setDeviceLockedForUser(userId: Int, locked: Boolean) { throw UnsupportedOperationException("ITrustManager.setDeviceLockedForUser is not hosted") }
    override fun isDeviceLocked(userId: Int, deviceId: Int): Boolean = throw UnsupportedOperationException("ITrustManager.isDeviceLocked is not hosted")
    override fun isDeviceSecure(userId: Int, deviceId: Int): Boolean = throw UnsupportedOperationException("ITrustManager.isDeviceSecure is not hosted")
    override fun isTrustUsuallyManaged(userId: Int): Boolean = throw UnsupportedOperationException("ITrustManager.isTrustUsuallyManaged is not hosted")
    override fun unlockedByBiometricForUser(userId: Int, source: android.hardware.biometrics.BiometricSourceType?) { throw UnsupportedOperationException("ITrustManager.unlockedByBiometricForUser is not hosted") }
    override fun clearAllBiometricRecognized(target: android.hardware.biometrics.BiometricSourceType?, unlockedUser: Int) { throw UnsupportedOperationException("ITrustManager.clearAllBiometricRecognized is not hosted") }
    override fun isActiveUnlockRunning(userId: Int): Boolean = throw UnsupportedOperationException("ITrustManager.isActiveUnlockRunning is not hosted")
    override fun isInSignificantPlace(): Boolean = throw UnsupportedOperationException("ITrustManager.isInSignificantPlace is not hosted")
    override fun registerDeviceLockedStateListener(listener: com.android.internal.policy.IDeviceLockedStateListener?, deviceId: Int) { throw UnsupportedOperationException("ITrustManager.registerDeviceLockedStateListener is not hosted") }
    override fun unregisterDeviceLockedStateListener(listener: com.android.internal.policy.IDeviceLockedStateListener?) { throw UnsupportedOperationException("ITrustManager.unregisterDeviceLockedStateListener is not hosted") }
}
