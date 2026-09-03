package dev.artzig.system

import android.os.CombinedVibration
import android.os.IBinder
import android.os.IVibratorStateListener
import android.os.VibrationAttributes
import android.os.VibratorInfo
import android.util.Log

/**
 * The session's vibrator manager over the real `IVibratorManagerService` surface,
 * published as `Context.VIBRATOR_MANAGER_SERVICE`.
 *
 * Source note: `com.android.server.vibrator.VibratorManagerService` for a device
 * whose vibrator HAL reports no vibrators (`getVibratorIds` empty, no
 * capabilities): every vibrate request is validated exactly as
 * `VibratorManagerService.vibrateWithPermissionCheck` validates it (non-null
 * effect and attributes, `CombinedVibration.validate`) and then dropped, which is
 * what the AOSP service does when no vibrator can play it. Listeners for an
 * unknown vibrator id are refused as in AOSP.
 */
class HostedVibratorManagerService : android.os.IVibratorManagerService.Stub(), android.os.IVibratorManagerService by unsupported<android.os.IVibratorManagerService>() {
    override fun asBinder(): android.os.IBinder = this

    override fun getVibratorIds(): IntArray? = IntArray(0)
    override fun getCapabilities(): Int = 0
    override fun getVibratorInfo(vibratorId: Int): VibratorInfo? = null
    override fun isVibrating(vibratorId: Int): Boolean = false
    override fun registerVibratorStateListener(vibratorId: Int, listener: IVibratorStateListener?): Boolean = false
    override fun unregisterVibratorStateListener(vibratorId: Int, listener: IVibratorStateListener?): Boolean = false

    override fun setAlwaysOnEffect(uid: Int, opPkg: String?, alwaysOnId: Int, vibration: CombinedVibration?, attributes: VibrationAttributes?): Boolean {
        // VibratorManagerService.setAlwaysOnEffect: no vibrator supports always-on.
        vibration?.validate()
        return false
    }

    override fun vibrate(uid: Int, deviceId: Int, opPkg: String?, vibration: CombinedVibration?, attributes: VibrationAttributes?, reason: String?, token: IBinder?) {
        requireNotNull(vibration) { "vibration must not be null" }
        requireNotNull(attributes) { "attributes must not be null" }
        requireNotNull(token) { "token must not be null" }
        vibration.validate()
        Log.d(TAG, "vibrate ignored (no vibrators): pkg=$opPkg usage=${attributes.usage} reason=$reason")
    }

    override fun cancelVibrate(usageFilter: Int, token: IBinder?) {}
    override fun performHapticFeedback(uid: Int, deviceId: Int, opPkg: String?, constant: Int, reason: String?, flags: Int, privFlags: Int) {}
    override fun performHapticFeedbackForInputDevice(uid: Int, deviceId: Int, opPkg: String?, constant: Int, inputDeviceId: Int, inputSource: Int, reason: String?, flags: Int, privFlags: Int) {}

    /** No vibrator supports vendor sessions: AOSP returns null when the session cannot start. */
    override fun startVendorVibrationSession(uid: Int, deviceId: Int, opPkg: String?, vibratorIds: IntArray?, attributes: VibrationAttributes?, reason: String?, callback: android.os.vibrator.IVibrationSessionCallback?): android.os.ICancellationSignal? = null

    private companion object {
        const val TAG = "HostedVibratorManager"
    }
}
