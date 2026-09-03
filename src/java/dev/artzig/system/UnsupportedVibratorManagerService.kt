@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IVibratorManagerService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedVibratorManagerService : android.os.IVibratorManagerService.Stub() {
    override fun getVibratorIds(): IntArray? = throw UnsupportedOperationException("IVibratorManagerService.getVibratorIds is not hosted")
    override fun getCapabilities(): Int = throw UnsupportedOperationException("IVibratorManagerService.getCapabilities is not hosted")
    override fun getVibratorInfo(vibratorId: Int): android.os.VibratorInfo? = throw UnsupportedOperationException("IVibratorManagerService.getVibratorInfo is not hosted")
    override fun isVibrating(vibratorId: Int): Boolean = throw UnsupportedOperationException("IVibratorManagerService.isVibrating is not hosted")
    override fun registerVibratorStateListener(vibratorId: Int, listener: android.os.IVibratorStateListener?): Boolean = throw UnsupportedOperationException("IVibratorManagerService.registerVibratorStateListener is not hosted")
    override fun unregisterVibratorStateListener(vibratorId: Int, listener: android.os.IVibratorStateListener?): Boolean = throw UnsupportedOperationException("IVibratorManagerService.unregisterVibratorStateListener is not hosted")
    override fun setAlwaysOnEffect(uid: Int, opPkg: String?, alwaysOnId: Int, vibration: android.os.CombinedVibration?, attributes: android.os.VibrationAttributes?): Boolean = throw UnsupportedOperationException("IVibratorManagerService.setAlwaysOnEffect is not hosted")
    override fun vibrate(uid: Int, deviceId: Int, opPkg: String?, vibration: android.os.CombinedVibration?, attributes: android.os.VibrationAttributes?, reason: String?, token: android.os.IBinder?) { throw UnsupportedOperationException("IVibratorManagerService.vibrate is not hosted") }
    override fun cancelVibrate(usageFilter: Int, token: android.os.IBinder?) { throw UnsupportedOperationException("IVibratorManagerService.cancelVibrate is not hosted") }
    override fun performHapticFeedback(uid: Int, deviceId: Int, opPkg: String?, constant: Int, reason: String?, flags: Int, privFlags: Int) { throw UnsupportedOperationException("IVibratorManagerService.performHapticFeedback is not hosted") }
    override fun performHapticFeedbackForInputDevice(uid: Int, deviceId: Int, opPkg: String?, constant: Int, inputDeviceId: Int, inputSource: Int, reason: String?, flags: Int, privFlags: Int) { throw UnsupportedOperationException("IVibratorManagerService.performHapticFeedbackForInputDevice is not hosted") }
    override fun startVendorVibrationSession(uid: Int, deviceId: Int, opPkg: String?, vibratorIds: IntArray?, attributes: android.os.VibrationAttributes?, reason: String?, callback: android.os.vibrator.IVibrationSessionCallback?): android.os.ICancellationSignal? = throw UnsupportedOperationException("IVibratorManagerService.startVendorVibrationSession is not hosted")
}
