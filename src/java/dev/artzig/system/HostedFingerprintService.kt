package dev.artzig.system

import android.Manifest.permission.*
import android.app.AppOpsManager
import android.hardware.fingerprint.*
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle

/**
 * FingerprintService's empty-provider path. The host has no biometric HAL or
 * enrolled sensors. Keep that capability state behind its stock Binder API;
 * FingerprintManager owns callback dispatch and the deprecated API behavior.
 */
class HostedFingerprintService(
    private val permissions: HostedPermissions,
    private val appOps: HostedAppOpsService,
) : IFingerprintService.Stub(), IFingerprintService by unsupported<IFingerprintService>() {
    private fun internal() = permissions.enforce(USE_BIOMETRIC_INTERNAL, "fingerprint")

    private fun canUse(packageName: String?, attributionTag: String?): Boolean {
        if (!permissions.has(USE_FINGERPRINT)) permissions.enforce(USE_BIOMETRIC, "fingerprint")
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID) return true
        if (UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM) return false
        permissions.enforceCallerPackage(packageName, "fingerprint")
        // Pinned AppOpEnums.APP_OP_USE_BIOMETRIC / APP_OP_USE_FINGERPRINT.
        // Both operations are noted in AOSP's short-circuit order.
        return appOps.noteOperation(78, uid, packageName, attributionTag, false, null, false)?.opMode ==
            AppOpsManager.MODE_ALLOWED ||
            appOps.noteOperation(55, uid, packageName, attributionTag, false, null, false)?.opMode ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun checkUser(userId: Int) {
        if (userId != UserHandle.getCallingUserId()) permissions.enforce(INTERACT_ACROSS_USERS, "fingerprint")
    }

    override fun getSensorPropertiesInternal(opPackageName: String?): MutableList<FingerprintSensorPropertiesInternal> {
        if (!permissions.has(USE_BIOMETRIC_INTERNAL)) permissions.enforce(TEST_BIOMETRIC, "fingerprint sensors")
        return mutableListOf()
    }

    override fun getSensorProperties(sensorId: Int, opPackageName: String?): FingerprintSensorPropertiesInternal? {
        internal()
        return null
    }

    override fun isHardwareDetectedDeprecated(opPackageName: String?, attributionTag: String?): Boolean {
        canUse(opPackageName, attributionTag)
        return false
    }

    override fun isHardwareDetected(sensorId: Int, opPackageName: String?): Boolean {
        internal()
        return false
    }

    override fun getEnrolledFingerprints(userId: Int, opPackageName: String?, attributionTag: String?): MutableList<Fingerprint> {
        if (canUse(opPackageName, attributionTag)) checkUser(userId)
        return mutableListOf()
    }

    override fun hasEnrolledFingerprintsDeprecated(userId: Int, opPackageName: String?, attributionTag: String?): Boolean {
        if (canUse(opPackageName, attributionTag)) checkUser(userId)
        return false
    }

    override fun hasEnrolledFingerprints(sensorId: Int, userId: Int, opPackageName: String?): Boolean {
        internal()
        return false
    }

    override fun authenticate(token: IBinder?, operationId: Long, receiver: IFingerprintServiceReceiver?,
        options: FingerprintAuthenticateOptions): Long {
        if (!canUse(options.opPackageName, options.attributionTag)) return -1
        if (options.sensorId != FingerprintManager.SENSOR_ID_ANY) internal()
        // FingerprintService.authenticate returns -1 without invoking the
        // receiver when getSingleProvider cannot select a fingerprint sensor.
        return -1
    }

    override fun cancelAuthentication(token: IBinder?, opPackageName: String?, attributionTag: String?, requestId: Long) {
        canUse(opPackageName, attributionTag)
    }

    override fun getAuthenticatorId(sensorId: Int, callingUserId: Int): Long {
        internal()
        return 0
    }

    override fun getLockoutModeForUser(sensorId: Int, userId: Int): Int {
        internal()
        return 0 // LockoutTracker.LOCKOUT_NONE, as for an absent AOSP provider.
    }

    override fun isClientActive(): Boolean {
        permissions.enforce(MANAGE_FINGERPRINT, "fingerprint client state")
        return false
    }

    override fun addAuthenticatorsRegisteredCallback(callback: IFingerprintAuthenticatorsRegisteredCallback?) {
        internal()
        if (callback == null) return
        // BiometricServiceRegistry broadcasts even an empty registered sensor
        // list, unregisters after invocation, and ignores dead remote clients.
        val callbacks = RemoteCallbackList<IFingerprintAuthenticatorsRegisteredCallback>()
        try {
            if (!callbacks.register(callback)) return
            val count = callbacks.beginBroadcast()
            try {
                for (index in 0 until count) {
                    try { callbacks.getBroadcastItem(index).onAllAuthenticatorsRegistered(emptyList()) }
                    catch (_: RemoteException) {}
                }
            } finally { callbacks.finishBroadcast() }
        } finally { callbacks.kill() }
    }
}
