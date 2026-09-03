@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IPowerManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedPowerManager : android.os.IPowerManager.Stub() {
    override fun acquireWakeLock(lock: android.os.IBinder?, flags: Int, tag: String?, packageName: String?, ws: android.os.WorkSource?, historyTag: String?, displayId: Int, callback: android.os.IWakeLockCallback?) { throw UnsupportedOperationException("IPowerManager.acquireWakeLock is not hosted") }
    override fun acquireWakeLockWithUid(lock: android.os.IBinder?, flags: Int, tag: String?, packageName: String?, uidtoblame: Int, displayId: Int, callback: android.os.IWakeLockCallback?) { throw UnsupportedOperationException("IPowerManager.acquireWakeLockWithUid is not hosted") }
    override fun releaseWakeLock(lock: android.os.IBinder?, flags: Int) { throw UnsupportedOperationException("IPowerManager.releaseWakeLock is not hosted") }
    override fun updateWakeLockUids(lock: android.os.IBinder?, uids: IntArray?) { throw UnsupportedOperationException("IPowerManager.updateWakeLockUids is not hosted") }
    override fun setPowerBoost(boost: Int, durationMs: Int) { throw UnsupportedOperationException("IPowerManager.setPowerBoost is not hosted") }
    override fun setPowerMode(mode: Int, enabled: Boolean) { throw UnsupportedOperationException("IPowerManager.setPowerMode is not hosted") }
    override fun setPowerModeChecked(mode: Int, enabled: Boolean): Boolean = throw UnsupportedOperationException("IPowerManager.setPowerModeChecked is not hosted")
    override fun updateWakeLockWorkSource(lock: android.os.IBinder?, ws: android.os.WorkSource?, historyTag: String?) { throw UnsupportedOperationException("IPowerManager.updateWakeLockWorkSource is not hosted") }
    override fun updateWakeLockCallback(lock: android.os.IBinder?, callback: android.os.IWakeLockCallback?) { throw UnsupportedOperationException("IPowerManager.updateWakeLockCallback is not hosted") }
    override fun isWakeLockLevelSupported(level: Int): Boolean = throw UnsupportedOperationException("IPowerManager.isWakeLockLevelSupported is not hosted")
    override fun isWakeLockLevelSupportedWithDisplayId(level: Int, displayId: Int): Boolean = throw UnsupportedOperationException("IPowerManager.isWakeLockLevelSupportedWithDisplayId is not hosted")
    override fun addScreenTimeoutPolicyListener(displayId: Int, listener: android.os.IScreenTimeoutPolicyListener?) { throw UnsupportedOperationException("IPowerManager.addScreenTimeoutPolicyListener is not hosted") }
    override fun removeScreenTimeoutPolicyListener(displayId: Int, listener: android.os.IScreenTimeoutPolicyListener?) { throw UnsupportedOperationException("IPowerManager.removeScreenTimeoutPolicyListener is not hosted") }
    override fun userActivity(displayId: Int, time: Long, event: Int, flags: Int) { throw UnsupportedOperationException("IPowerManager.userActivity is not hosted") }
    override fun wakeUp(time: Long, reason: Int, details: String?, opPackageName: String?) { throw UnsupportedOperationException("IPowerManager.wakeUp is not hosted") }
    override fun wakeUpWithDisplayId(time: Long, reason: Int, details: String?, opPackageName: String?, displayId: Int) { throw UnsupportedOperationException("IPowerManager.wakeUpWithDisplayId is not hosted") }
    override fun goToSleep(time: Long, reason: Int, flags: Int) { throw UnsupportedOperationException("IPowerManager.goToSleep is not hosted") }
    override fun goToSleepWithDisplayId(displayId: Int, time: Long, reason: Int, flags: Int) { throw UnsupportedOperationException("IPowerManager.goToSleepWithDisplayId is not hosted") }
    override fun nap(time: Long) { throw UnsupportedOperationException("IPowerManager.nap is not hosted") }
    override fun getBrightnessConstraint(displayId: Int, constraint: Int): Float = throw UnsupportedOperationException("IPowerManager.getBrightnessConstraint is not hosted")
    override fun isInteractive(): Boolean = throw UnsupportedOperationException("IPowerManager.isInteractive is not hosted")
    override fun isDisplayInteractive(displayId: Int): Boolean = throw UnsupportedOperationException("IPowerManager.isDisplayInteractive is not hosted")
    override fun areAutoPowerSaveModesEnabled(): Boolean = throw UnsupportedOperationException("IPowerManager.areAutoPowerSaveModesEnabled is not hosted")
    override fun isPowerSaveMode(): Boolean = throw UnsupportedOperationException("IPowerManager.isPowerSaveMode is not hosted")
    override fun getPowerSaveState(serviceType: Int): android.os.PowerSaveState? = throw UnsupportedOperationException("IPowerManager.getPowerSaveState is not hosted")
    override fun setPowerSaveModeEnabled(mode: Boolean): Boolean = throw UnsupportedOperationException("IPowerManager.setPowerSaveModeEnabled is not hosted")
    override fun isBatterySaverSupported(): Boolean = throw UnsupportedOperationException("IPowerManager.isBatterySaverSupported is not hosted")
    override fun getFullPowerSavePolicy(): android.os.BatterySaverPolicyConfig? = throw UnsupportedOperationException("IPowerManager.getFullPowerSavePolicy is not hosted")
    override fun setFullPowerSavePolicy(config: android.os.BatterySaverPolicyConfig?): Boolean = throw UnsupportedOperationException("IPowerManager.setFullPowerSavePolicy is not hosted")
    override fun setDynamicPowerSaveHint(powerSaveHint: Boolean, disableThreshold: Int): Boolean = throw UnsupportedOperationException("IPowerManager.setDynamicPowerSaveHint is not hosted")
    override fun setAdaptivePowerSavePolicy(config: android.os.BatterySaverPolicyConfig?): Boolean = throw UnsupportedOperationException("IPowerManager.setAdaptivePowerSavePolicy is not hosted")
    override fun setAdaptivePowerSaveEnabled(enabled: Boolean): Boolean = throw UnsupportedOperationException("IPowerManager.setAdaptivePowerSaveEnabled is not hosted")
    override fun getPowerSaveModeTrigger(): Int = throw UnsupportedOperationException("IPowerManager.getPowerSaveModeTrigger is not hosted")
    override fun setBatteryDischargePrediction(timeRemaining: android.os.ParcelDuration?, isCustomized: Boolean) { throw UnsupportedOperationException("IPowerManager.setBatteryDischargePrediction is not hosted") }
    override fun getBatteryDischargePrediction(): android.os.ParcelDuration? = throw UnsupportedOperationException("IPowerManager.getBatteryDischargePrediction is not hosted")
    override fun isBatteryDischargePredictionPersonalized(): Boolean = throw UnsupportedOperationException("IPowerManager.isBatteryDischargePredictionPersonalized is not hosted")
    override fun isDeviceIdleMode(): Boolean = throw UnsupportedOperationException("IPowerManager.isDeviceIdleMode is not hosted")
    override fun isLightDeviceIdleMode(): Boolean = throw UnsupportedOperationException("IPowerManager.isLightDeviceIdleMode is not hosted")
    override fun isLowPowerStandbySupported(): Boolean = throw UnsupportedOperationException("IPowerManager.isLowPowerStandbySupported is not hosted")
    override fun isLowPowerStandbyEnabled(): Boolean = throw UnsupportedOperationException("IPowerManager.isLowPowerStandbyEnabled is not hosted")
    override fun setLowPowerStandbyEnabled(enabled: Boolean) { throw UnsupportedOperationException("IPowerManager.setLowPowerStandbyEnabled is not hosted") }
    override fun setLowPowerStandbyActiveDuringMaintenance(activeDuringMaintenance: Boolean) { throw UnsupportedOperationException("IPowerManager.setLowPowerStandbyActiveDuringMaintenance is not hosted") }
    override fun forceLowPowerStandbyActive(active: Boolean) { throw UnsupportedOperationException("IPowerManager.forceLowPowerStandbyActive is not hosted") }
    override fun setLowPowerStandbyPolicy(policy: android.os.IPowerManager.LowPowerStandbyPolicy?) { throw UnsupportedOperationException("IPowerManager.setLowPowerStandbyPolicy is not hosted") }
    override fun getLowPowerStandbyPolicy(): android.os.IPowerManager.LowPowerStandbyPolicy? = throw UnsupportedOperationException("IPowerManager.getLowPowerStandbyPolicy is not hosted")
    override fun isExemptFromLowPowerStandby(): Boolean = throw UnsupportedOperationException("IPowerManager.isExemptFromLowPowerStandby is not hosted")
    override fun isReasonAllowedInLowPowerStandby(reason: Int): Boolean = throw UnsupportedOperationException("IPowerManager.isReasonAllowedInLowPowerStandby is not hosted")
    override fun isFeatureAllowedInLowPowerStandby(feature: String?): Boolean = throw UnsupportedOperationException("IPowerManager.isFeatureAllowedInLowPowerStandby is not hosted")
    override fun acquireLowPowerStandbyPorts(token: android.os.IBinder?, ports: MutableList<android.os.IPowerManager.LowPowerStandbyPortDescription?>?) { throw UnsupportedOperationException("IPowerManager.acquireLowPowerStandbyPorts is not hosted") }
    override fun releaseLowPowerStandbyPorts(token: android.os.IBinder?) { throw UnsupportedOperationException("IPowerManager.releaseLowPowerStandbyPorts is not hosted") }
    override fun getActiveLowPowerStandbyPorts(): MutableList<android.os.IPowerManager.LowPowerStandbyPortDescription?>? = throw UnsupportedOperationException("IPowerManager.getActiveLowPowerStandbyPorts is not hosted")
    override fun reboot(confirm: Boolean, reason: String?, wait: Boolean) { throw UnsupportedOperationException("IPowerManager.reboot is not hosted") }
    override fun rebootSafeMode(confirm: Boolean, wait: Boolean) { throw UnsupportedOperationException("IPowerManager.rebootSafeMode is not hosted") }
    override fun shutdown(confirm: Boolean, reason: String?, wait: Boolean) { throw UnsupportedOperationException("IPowerManager.shutdown is not hosted") }
    override fun crash(message: String?) { throw UnsupportedOperationException("IPowerManager.crash is not hosted") }
    override fun getLastShutdownReason(): Int = throw UnsupportedOperationException("IPowerManager.getLastShutdownReason is not hosted")
    override fun getLastSleepReason(): Int = throw UnsupportedOperationException("IPowerManager.getLastSleepReason is not hosted")
    override fun setStayOnSetting(`val`: Int) { throw UnsupportedOperationException("IPowerManager.setStayOnSetting is not hosted") }
    override fun boostScreenBrightness(time: Long) { throw UnsupportedOperationException("IPowerManager.boostScreenBrightness is not hosted") }
    override fun acquireWakeLockAsync(lock: android.os.IBinder?, flags: Int, tag: String?, packageName: String?, ws: android.os.WorkSource?, historyTag: String?) { throw UnsupportedOperationException("IPowerManager.acquireWakeLockAsync is not hosted") }
    override fun releaseWakeLockAsync(lock: android.os.IBinder?, flags: Int) { throw UnsupportedOperationException("IPowerManager.releaseWakeLockAsync is not hosted") }
    override fun updateWakeLockUidsAsync(lock: android.os.IBinder?, uids: IntArray?) { throw UnsupportedOperationException("IPowerManager.updateWakeLockUidsAsync is not hosted") }
    override fun isScreenBrightnessBoosted(): Boolean = throw UnsupportedOperationException("IPowerManager.isScreenBrightnessBoosted is not hosted")
    override fun setAttentionLight(on: Boolean, color: Int) { throw UnsupportedOperationException("IPowerManager.setAttentionLight is not hosted") }
    override fun setDozeAfterScreenOff(on: Boolean) { throw UnsupportedOperationException("IPowerManager.setDozeAfterScreenOff is not hosted") }
    override fun isAmbientDisplayAvailable(): Boolean = throw UnsupportedOperationException("IPowerManager.isAmbientDisplayAvailable is not hosted")
    override fun suppressAmbientDisplay(token: String?, suppress: Boolean) { throw UnsupportedOperationException("IPowerManager.suppressAmbientDisplay is not hosted") }
    override fun isAmbientDisplaySuppressedForToken(token: String?): Boolean = throw UnsupportedOperationException("IPowerManager.isAmbientDisplaySuppressedForToken is not hosted")
    override fun isAmbientDisplaySuppressed(): Boolean = throw UnsupportedOperationException("IPowerManager.isAmbientDisplaySuppressed is not hosted")
    override fun isAmbientDisplaySuppressedForTokenByApp(token: String?, appUid: Int): Boolean = throw UnsupportedOperationException("IPowerManager.isAmbientDisplaySuppressedForTokenByApp is not hosted")
    override fun forceSuspend(): Boolean = throw UnsupportedOperationException("IPowerManager.forceSuspend is not hosted")
}
