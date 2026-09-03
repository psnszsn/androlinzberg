@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ILockSettings method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedLockSettings : com.android.internal.widget.ILockSettings.Stub() {
    override fun setBoolean(key: String?, value: Boolean, userId: Int) { throw UnsupportedOperationException("ILockSettings.setBoolean is not hosted") }
    override fun setLong(key: String?, value: Long, userId: Int) { throw UnsupportedOperationException("ILockSettings.setLong is not hosted") }
    override fun setString(key: String?, value: String?, userId: Int) { throw UnsupportedOperationException("ILockSettings.setString is not hosted") }
    override fun getBoolean(key: String?, defaultValue: Boolean, userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.getBoolean is not hosted")
    override fun getLong(key: String?, defaultValue: Long, userId: Int): Long = throw UnsupportedOperationException("ILockSettings.getLong is not hosted")
    override fun getString(key: String?, defaultValue: String?, userId: Int): String? = throw UnsupportedOperationException("ILockSettings.getString is not hosted")
    override fun setLockCredential(credential: com.android.internal.widget.LockscreenCredential?, savedCredential: com.android.internal.widget.LockscreenCredential?, userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.setLockCredential is not hosted")
    override fun resetKeyStore(userId: Int) { throw UnsupportedOperationException("ILockSettings.resetKeyStore is not hosted") }
    override fun checkCredential(credential: com.android.internal.widget.LockscreenCredential?, userId: Int, progressCallback: com.android.internal.widget.ICheckCredentialProgressCallback?): com.android.internal.widget.VerifyCredentialResponse? = throw UnsupportedOperationException("ILockSettings.checkCredential is not hosted")
    override fun verifyCredential(credential: com.android.internal.widget.LockscreenCredential?, userId: Int, flags: Int): com.android.internal.widget.VerifyCredentialResponse? = throw UnsupportedOperationException("ILockSettings.verifyCredential is not hosted")
    override fun verifyTiedProfileChallenge(credential: com.android.internal.widget.LockscreenCredential?, userId: Int, flags: Int): com.android.internal.widget.VerifyCredentialResponse? = throw UnsupportedOperationException("ILockSettings.verifyTiedProfileChallenge is not hosted")
    override fun verifyGatekeeperPasswordHandle(gatekeeperPasswordHandle: Long, challenge: Long, userId: Int): com.android.internal.widget.VerifyCredentialResponse? = throw UnsupportedOperationException("ILockSettings.verifyGatekeeperPasswordHandle is not hosted")
    override fun removeGatekeeperPasswordHandle(gatekeeperPasswordHandle: Long) { throw UnsupportedOperationException("ILockSettings.removeGatekeeperPasswordHandle is not hosted") }
    override fun getCredentialType(userId: Int): Int = throw UnsupportedOperationException("ILockSettings.getCredentialType is not hosted")
    override fun getPinLength(userId: Int): Int = throw UnsupportedOperationException("ILockSettings.getPinLength is not hosted")
    override fun refreshStoredPinLength(userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.refreshStoredPinLength is not hosted")
    override fun getHashFactor(currentCredential: com.android.internal.widget.LockscreenCredential?, userId: Int): ByteArray? = throw UnsupportedOperationException("ILockSettings.getHashFactor is not hosted")
    override fun setSeparateProfileChallengeEnabled(userId: Int, enabled: Boolean, managedUserPassword: com.android.internal.widget.LockscreenCredential?) { throw UnsupportedOperationException("ILockSettings.setSeparateProfileChallengeEnabled is not hosted") }
    override fun getSeparateProfileChallengeEnabled(userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.getSeparateProfileChallengeEnabled is not hosted")
    override fun registerStrongAuthTracker(tracker: android.app.trust.IStrongAuthTracker?) { throw UnsupportedOperationException("ILockSettings.registerStrongAuthTracker is not hosted") }
    override fun unregisterStrongAuthTracker(tracker: android.app.trust.IStrongAuthTracker?) { throw UnsupportedOperationException("ILockSettings.unregisterStrongAuthTracker is not hosted") }
    override fun requireStrongAuth(strongAuthReason: Int, userId: Int) { throw UnsupportedOperationException("ILockSettings.requireStrongAuth is not hosted") }
    override fun reportSuccessfulBiometricUnlock(isStrongBiometric: Boolean, userId: Int) { throw UnsupportedOperationException("ILockSettings.reportSuccessfulBiometricUnlock is not hosted") }
    override fun scheduleNonStrongBiometricIdleTimeout(userId: Int) { throw UnsupportedOperationException("ILockSettings.scheduleNonStrongBiometricIdleTimeout is not hosted") }
    override fun systemReady() { throw UnsupportedOperationException("ILockSettings.systemReady is not hosted") }
    override fun userPresent(userId: Int) { throw UnsupportedOperationException("ILockSettings.userPresent is not hosted") }
    override fun getStrongAuthForUser(userId: Int): Int = throw UnsupportedOperationException("ILockSettings.getStrongAuthForUser is not hosted")
    override fun hasPendingEscrowToken(userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.hasPendingEscrowToken is not hosted")
    override fun initRecoveryServiceWithSigFile(rootCertificateAlias: String?, recoveryServiceCertFile: ByteArray?, recoveryServiceSigFile: ByteArray?) { throw UnsupportedOperationException("ILockSettings.initRecoveryServiceWithSigFile is not hosted") }
    override fun getKeyChainSnapshot(): android.security.keystore.recovery.KeyChainSnapshot? = throw UnsupportedOperationException("ILockSettings.getKeyChainSnapshot is not hosted")
    override fun generateKey(alias: String?): String? = throw UnsupportedOperationException("ILockSettings.generateKey is not hosted")
    override fun generateKeyWithMetadata(alias: String?, metadata: ByteArray?): String? = throw UnsupportedOperationException("ILockSettings.generateKeyWithMetadata is not hosted")
    override fun importKey(alias: String?, keyBytes: ByteArray?): String? = throw UnsupportedOperationException("ILockSettings.importKey is not hosted")
    override fun importKeyWithMetadata(alias: String?, keyBytes: ByteArray?, metadata: ByteArray?): String? = throw UnsupportedOperationException("ILockSettings.importKeyWithMetadata is not hosted")
    override fun getKey(alias: String?): String? = throw UnsupportedOperationException("ILockSettings.getKey is not hosted")
    override fun removeKey(alias: String?) { throw UnsupportedOperationException("ILockSettings.removeKey is not hosted") }
    override fun setSnapshotCreatedPendingIntent(intent: android.app.PendingIntent?) { throw UnsupportedOperationException("ILockSettings.setSnapshotCreatedPendingIntent is not hosted") }
    override fun setServerParams(serverParams: ByteArray?) { throw UnsupportedOperationException("ILockSettings.setServerParams is not hosted") }
    override fun setRecoveryStatus(alias: String?, status: Int) { throw UnsupportedOperationException("ILockSettings.setRecoveryStatus is not hosted") }
    override fun getRecoveryStatus(): MutableMap<*, *>? = throw UnsupportedOperationException("ILockSettings.getRecoveryStatus is not hosted")
    override fun setRecoverySecretTypes(secretTypes: IntArray?) { throw UnsupportedOperationException("ILockSettings.setRecoverySecretTypes is not hosted") }
    override fun getRecoverySecretTypes(): IntArray? = throw UnsupportedOperationException("ILockSettings.getRecoverySecretTypes is not hosted")
    override fun startRecoverySessionWithCertPath(sessionId: String?, rootCertificateAlias: String?, verifierCertPath: android.security.keystore.recovery.RecoveryCertPath?, vaultParams: ByteArray?, vaultChallenge: ByteArray?, secrets: MutableList<android.security.keystore.recovery.KeyChainProtectionParams?>?): ByteArray? = throw UnsupportedOperationException("ILockSettings.startRecoverySessionWithCertPath is not hosted")
    override fun recoverKeyChainSnapshot(sessionId: String?, recoveryKeyBlob: ByteArray?, applicationKeys: MutableList<android.security.keystore.recovery.WrappedApplicationKey?>?): MutableMap<*, *>? = throw UnsupportedOperationException("ILockSettings.recoverKeyChainSnapshot is not hosted")
    override fun closeSession(sessionId: String?) { throw UnsupportedOperationException("ILockSettings.closeSession is not hosted") }
    override fun startRemoteLockscreenValidation(): android.app.RemoteLockscreenValidationSession? = throw UnsupportedOperationException("ILockSettings.startRemoteLockscreenValidation is not hosted")
    override fun validateRemoteLockscreen(encryptedCredential: ByteArray?): android.app.RemoteLockscreenValidationResult? = throw UnsupportedOperationException("ILockSettings.validateRemoteLockscreen is not hosted")
    override fun hasSecureLockScreen(): Boolean = throw UnsupportedOperationException("ILockSettings.hasSecureLockScreen is not hosted")
    override fun tryUnlockWithCachedUnifiedChallenge(userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.tryUnlockWithCachedUnifiedChallenge is not hosted")
    override fun removeCachedUnifiedChallenge(userId: Int) { throw UnsupportedOperationException("ILockSettings.removeCachedUnifiedChallenge is not hosted") }
    override fun registerWeakEscrowTokenRemovedListener(listener: com.android.internal.widget.IWeakEscrowTokenRemovedListener?): Boolean = throw UnsupportedOperationException("ILockSettings.registerWeakEscrowTokenRemovedListener is not hosted")
    override fun unregisterWeakEscrowTokenRemovedListener(listener: com.android.internal.widget.IWeakEscrowTokenRemovedListener?): Boolean = throw UnsupportedOperationException("ILockSettings.unregisterWeakEscrowTokenRemovedListener is not hosted")
    override fun addWeakEscrowToken(token: ByteArray?, userId: Int, callback: com.android.internal.widget.IWeakEscrowTokenActivatedListener?): Long = throw UnsupportedOperationException("ILockSettings.addWeakEscrowToken is not hosted")
    override fun removeWeakEscrowToken(handle: Long, userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.removeWeakEscrowToken is not hosted")
    override fun isWeakEscrowTokenActive(handle: Long, userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.isWeakEscrowTokenActive is not hosted")
    override fun isWeakEscrowTokenValid(handle: Long, token: ByteArray?, userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.isWeakEscrowTokenValid is not hosted")
    override fun unlockUserKeyIfUnsecured(userId: Int) { throw UnsupportedOperationException("ILockSettings.unlockUserKeyIfUnsecured is not hosted") }
    override fun writeRepairModeCredential(userId: Int): Boolean = throw UnsupportedOperationException("ILockSettings.writeRepairModeCredential is not hosted")
}
