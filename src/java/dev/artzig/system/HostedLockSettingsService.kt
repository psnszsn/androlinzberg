package dev.artzig.system

import android.Manifest
import android.app.trust.IStrongAuthTracker
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import com.android.internal.widget.ICheckCredentialProgressCallback
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.internal.widget.VerifyCredentialResponse

/**
 * The session's ILockSettings over the real AIDL surface, published as
 * "lock_settings" (LockPatternUtils, hence KeyguardManager, resolve it).
 *
 * Source note: `com.android.server.locksettings.LockSettingsService` for a device
 * that never had a lock screen credential: the credential type is NONE, the
 * per-user key/value settings are a plain store behind the AOSP
 * ACCESS_KEYGUARD_SECURE_STORAGE write gate, an empty credential verifies, strong
 * auth is never required and trackers hear that on registration. Setting a
 * credential needs gatekeeper and keystore, which this host does not run, so it
 * and the escrow/recovery surface stay unserved.
 */
class HostedLockSettingsService(private val permissions: HostedPermissions) : com.android.internal.widget.ILockSettings.Stub(), com.android.internal.widget.ILockSettings by unsupported<com.android.internal.widget.ILockSettings>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private val values = HashMap<Int, HashMap<String, String>>()
    private val trackers = LinkedHashMap<IBinder, IStrongAuthTracker>()

    private fun enforceWrite(what: String) = permissions.enforce(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE, what)
    private fun store(userId: Int) = values.getOrPut(userId) { HashMap() }

    override fun setBoolean(key: String?, value: Boolean, userId: Int) = setString(key, value.toString(), userId)
    override fun setLong(key: String?, value: Long, userId: Int) = setString(key, value.toString(), userId)
    override fun setString(key: String?, value: String?, userId: Int) {
        enforceWrite("setString")
        synchronized(lock) { store(userId)[key ?: return] = value ?: "" }
    }
    override fun getBoolean(key: String?, defaultValue: Boolean, userId: Int): Boolean = getString(key, null, userId)?.toBooleanStrictOrNull() ?: defaultValue
    override fun getLong(key: String?, defaultValue: Long, userId: Int): Long = getString(key, null, userId)?.toLongOrNull() ?: defaultValue
    override fun getString(key: String?, defaultValue: String?, userId: Int): String? = synchronized(lock) { values[userId]?.get(key) ?: defaultValue }

    override fun getCredentialType(userId: Int): Int = LockPatternUtils.CREDENTIAL_TYPE_NONE
    override fun getPinLength(userId: Int): Int = LockPatternUtils.PIN_LENGTH_UNAVAILABLE
    override fun refreshStoredPinLength(userId: Int): Boolean = false
    override fun hasSecureLockScreen(): Boolean = false
    override fun getSeparateProfileChallengeEnabled(userId: Int): Boolean = false
    override fun getStrongAuthForUser(userId: Int): Int = LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
    override fun hasPendingEscrowToken(userId: Int): Boolean = false
    override fun tryUnlockWithCachedUnifiedChallenge(userId: Int): Boolean = false
    override fun removeCachedUnifiedChallenge(userId: Int) {}
    override fun systemReady() {}
    override fun userPresent(userId: Int) {}
    override fun unlockUserKeyIfUnsecured(userId: Int) = enforceWrite("unlockUserKeyIfUnsecured")
    override fun requireStrongAuth(strongAuthReason: Int, userId: Int) = enforceWrite("requireStrongAuth")
    override fun reportSuccessfulBiometricUnlock(isStrongBiometric: Boolean, userId: Int) = enforceWrite("reportSuccessfulBiometricUnlock")
    override fun scheduleNonStrongBiometricIdleTimeout(userId: Int) = enforceWrite("scheduleNonStrongBiometricIdleTimeout")
    override fun resetKeyStore(userId: Int) = enforceWrite("resetKeyStore")

    /** LockSettingsService.checkCredential with no credential set: only the empty credential passes. */
    override fun checkCredential(credential: LockscreenCredential?, userId: Int, progressCallback: ICheckCredentialProgressCallback?): VerifyCredentialResponse? {
        enforceWrite("checkCredential")
        return if (credential == null || credential.isNone) VerifyCredentialResponse.OK else VerifyCredentialResponse.ERROR
    }
    override fun verifyCredential(credential: LockscreenCredential?, userId: Int, flags: Int): VerifyCredentialResponse? {
        enforceWrite("verifyCredential")
        return if (credential == null || credential.isNone) VerifyCredentialResponse.OK else VerifyCredentialResponse.ERROR
    }

    override fun registerStrongAuthTracker(tracker: IStrongAuthTracker?) {
        enforceWrite("registerStrongAuthTracker")
        val binder = tracker?.asBinder() ?: return
        synchronized(lock) { trackers[binder] = tracker }
        try {
            tracker.onStrongAuthRequiredChanged(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED, UserHandle.USER_SYSTEM)
        } catch (e: RemoteException) {
            synchronized(lock) { trackers.remove(binder) }
        }
    }
    override fun unregisterStrongAuthTracker(tracker: IStrongAuthTracker?) {
        enforceWrite("unregisterStrongAuthTracker")
        synchronized(lock) { trackers.remove(tracker?.asBinder() ?: return) }
    }
}
