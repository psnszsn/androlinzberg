package dev.artzig.system

import android.os.BatterySaverPolicyConfig
import android.os.Binder
import android.os.IBinder
import android.os.IPowerManager
import android.os.IWakeLockCallback
import android.os.PowerManager
import android.os.PowerSaveState
import android.os.Process
import android.os.WorkSource
import android.util.Log
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * The session's power manager over the real `IPowerManager` surface, published
 * as `Context.POWER_SERVICE`.
 *
 * Source note: `com.android.server.power.PowerManagerService` (`acquireWakeLockInternal`,
 * `releaseWakeLockInternal`, `updateWakeLockUidsInternal`, `isWakeLockLevelSupportedInternal`,
 * the `WakeLock` record and its death link, and the binder-side permission checks). The
 * AOSP service drives the display, the kernel suspend blocker and battery saver; this desktop
 * host is always interactive and never sleeps, so the service keeps the wake-lock accounting
 * (the observable part: reference counts per package, death cleanup, callbacks) and answers
 * every state query with the always-on values: interactive, no power save, no idle mode, no
 * low-power standby, no ambient display. Sleep, wake, reboot and shutdown stay system-only
 * (DEVICE_POWER / REBOOT) and are no-ops for the system.
 */
class HostedPowerManagerService : UnsupportedPowerManager() {
    /** PowerManagerService.WakeLock. */
    private inner class WakeLock(
        val lock: IBinder,
        var flags: Int,
        val tag: String,
        val packageName: String?,
        val ownerUid: Int,
        val ownerPid: Int,
        var workSource: WorkSource?,
        var historyTag: String?,
        var callback: IWakeLockCallback?,
        val displayId: Int,
    ) : IBinder.DeathRecipient {
        val acquireTime = System.currentTimeMillis()
        override fun binderDied() {
            // handleWakeLockDeath: the owner is gone, the lock goes with it.
            synchronized(lock) { wakeLocks.remove(lock) }
            Log.i(TAG, "wake lock released on death: tag=$tag pkg=$packageName")
        }
        val level: Int get() = flags and PowerManager.WAKE_LOCK_LEVEL_MASK
    }

    private val lock = Any()
    private val wakeLocks = LinkedHashMap<IBinder, WakeLock>()

    private fun enforceSystem(what: String, permission: String) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("$what requires android.permission.$permission")
        }
    }

    // ------------------------------------------------------------ wake locks

    override fun acquireWakeLock(lock: IBinder?, flags: Int, tag: String?, packageName: String?, ws: WorkSource?, historyTag: String?, displayId: Int, callback: IWakeLockCallback?) {
        requireNotNull(lock) { "lock must not be null" }
        // PowerManager.validateWakeLockParameters: the level must be one of the known levels.
        when (flags and PowerManager.WAKE_LOCK_LEVEL_MASK) {
            PowerManager.PARTIAL_WAKE_LOCK, PowerManager.SCREEN_DIM_WAKE_LOCK, PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            PowerManager.FULL_WAKE_LOCK, PowerManager.DOZE_WAKE_LOCK, PowerManager.DRAW_WAKE_LOCK,
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK -> {}
            else -> throw IllegalArgumentException("Must specify a valid wake lock level.")
        }
        requireNotNull(tag) { "The tag must not be null" }
        if (ws != null && Binder.getCallingUid() != Process.SYSTEM_UID) {
            // Only the system attributes a wake lock to another work source.
            throw SecurityException("acquireWakeLock with a WorkSource requires android.permission.UPDATE_DEVICE_STATS")
        }
        val record = WakeLock(lock, flags, tag, packageName, Binder.getCallingUid(), Binder.getCallingPid(), ws, historyTag, callback, displayId)
        synchronized(this.lock) {
            val existing = wakeLocks[lock]
            if (existing != null) {
                // acquireWakeLockInternal: an existing lock updates its properties.
                existing.flags = flags
                existing.workSource = ws
                existing.historyTag = historyTag
                existing.callback = callback
                return
            }
            wakeLocks[lock] = record
        }
        try {
            lock.linkToDeath(record, 0)
        } catch (e: android.os.RemoteException) {
            synchronized(this.lock) { wakeLocks.remove(lock) }
            throw IllegalArgumentException("Wake lock is already dead.")
        }
        Log.i(TAG, "acquire: tag=$tag pkg=$packageName level=${record.level} uid=${record.ownerUid}")
    }

    override fun acquireWakeLockWithUid(lock: IBinder?, flags: Int, tag: String?, packageName: String?, uidtoblame: Int, displayId: Int, callback: IWakeLockCallback?) =
        acquireWakeLock(lock, flags, tag, packageName, if (uidtoblame >= 0 && Binder.getCallingUid() == Process.SYSTEM_UID) WorkSource(uidtoblame) else null, null, displayId, callback)

    override fun acquireWakeLockAsync(lock: IBinder?, flags: Int, tag: String?, packageName: String?, ws: WorkSource?, historyTag: String?) =
        acquireWakeLock(lock, flags, tag, packageName, ws, historyTag, android.view.Display.INVALID_DISPLAY, null)

    override fun releaseWakeLock(lock: IBinder?, flags: Int) {
        requireNotNull(lock) { "lock must not be null" }
        val record = synchronized(this.lock) { wakeLocks.remove(lock) }
        if (record == null) {
            Log.w(TAG, "Wake lock not active: $lock from uid ${Binder.getCallingUid()}")
            return
        }
        lock.unlinkToDeath(record, 0)
        Log.i(TAG, "release: tag=${record.tag} pkg=${record.packageName}")
    }

    override fun releaseWakeLockAsync(lock: IBinder?, flags: Int) = releaseWakeLock(lock, flags)

    override fun updateWakeLockUids(lock: IBinder?, uids: IntArray?) {
        enforceSystem("updateWakeLockUids", "UPDATE_DEVICE_STATS")
        synchronized(this.lock) { wakeLocks[lock ?: return]?.workSource = uids?.let { ws -> WorkSource().also { for (u in ws) it.add(u) } } }
    }

    override fun updateWakeLockUidsAsync(lock: IBinder?, uids: IntArray?) = updateWakeLockUids(lock, uids)

    override fun updateWakeLockWorkSource(lock: IBinder?, ws: WorkSource?, historyTag: String?) {
        enforceSystem("updateWakeLockWorkSource", "UPDATE_DEVICE_STATS")
        synchronized(this.lock) {
            val record = wakeLocks[lock ?: return] ?: throw IllegalArgumentException("Wake lock not active: $lock")
            record.workSource = ws
            record.historyTag = historyTag
        }
    }

    override fun updateWakeLockCallback(lock: IBinder?, callback: IWakeLockCallback?) {
        synchronized(this.lock) {
            val record = wakeLocks[lock ?: return] ?: throw IllegalArgumentException("Wake lock not active: $lock")
            record.callback = callback
        }
    }

    /** isWakeLockLevelSupportedInternal: no proximity sensor; every other level is a no-op that "works". */
    override fun isWakeLockLevelSupported(level: Int): Boolean = when (level) {
        PowerManager.PARTIAL_WAKE_LOCK, PowerManager.SCREEN_DIM_WAKE_LOCK, PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
        PowerManager.FULL_WAKE_LOCK, PowerManager.DOZE_WAKE_LOCK, PowerManager.DRAW_WAKE_LOCK -> true
        else -> false
    }

    override fun isWakeLockLevelSupportedWithDisplayId(level: Int, displayId: Int): Boolean = isWakeLockLevelSupported(level)

    /** Held wake locks of a package (dump and the system test). */
    fun wakeLockCount(packageName: String?): Int = synchronized(lock) { wakeLocks.values.count { packageName == null || it.packageName == packageName } }

    /** PowerManagerService.WakeLock.getLockLevelString. */
    private fun levelToString(level: Int): String = when (level) {
        PowerManager.FULL_WAKE_LOCK -> "FULL_WAKE_LOCK"
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK -> "SCREEN_BRIGHT_WAKE_LOCK"
        PowerManager.SCREEN_DIM_WAKE_LOCK -> "SCREEN_DIM_WAKE_LOCK"
        PowerManager.PARTIAL_WAKE_LOCK -> "PARTIAL_WAKE_LOCK"
        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK -> "PROXIMITY_SCREEN_OFF_WAKE_LOCK"
        PowerManager.DOZE_WAKE_LOCK -> "DOZE_WAKE_LOCK"
        PowerManager.DRAW_WAKE_LOCK -> "DRAW_WAKE_LOCK"
        PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK -> "SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK"
        else -> "???"
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>?) {
        pw.println("POWER MANAGER (dumpsys power)")
        pw.println("Wake Locks: size=${wakeLockCount(null)}")
        synchronized(lock) {
            for (w in wakeLocks.values) {
                pw.println("  ${levelToString(w.level)} '${w.tag}' (uid=${w.ownerUid}, pid=${w.ownerPid}, pkg=${w.packageName}, acquired=${w.acquireTime})")
            }
        }
        pw.flush()
    }

    // ------------------------------------------------------------ device state (always on)

    override fun userActivity(displayId: Int, time: Long, event: Int, flags: Int) {}
    override fun addScreenTimeoutPolicyListener(displayId: Int, listener: android.os.IScreenTimeoutPolicyListener?) {}
    override fun removeScreenTimeoutPolicyListener(displayId: Int, listener: android.os.IScreenTimeoutPolicyListener?) {}
    override fun wakeUp(time: Long, reason: Int, details: String?, opPackageName: String?) = enforceSystem("wakeUp", "DEVICE_POWER")
    override fun wakeUpWithDisplayId(time: Long, reason: Int, details: String?, opPackageName: String?, displayId: Int) = enforceSystem("wakeUp", "DEVICE_POWER")
    override fun goToSleep(time: Long, reason: Int, flags: Int) = enforceSystem("goToSleep", "DEVICE_POWER")
    override fun goToSleepWithDisplayId(displayId: Int, time: Long, reason: Int, flags: Int) = enforceSystem("goToSleep", "DEVICE_POWER")
    override fun nap(time: Long) = enforceSystem("nap", "DEVICE_POWER")
    override fun boostScreenBrightness(time: Long) = enforceSystem("boostScreenBrightness", "DEVICE_POWER")
    override fun isScreenBrightnessBoosted(): Boolean = false
    override fun getBrightnessConstraint(displayId: Int, constraint: Int): Float = 0.5f
    override fun isInteractive(): Boolean = true
    override fun isDisplayInteractive(displayId: Int): Boolean = true
    override fun areAutoPowerSaveModesEnabled(): Boolean = false
    override fun isPowerSaveMode(): Boolean = false
    override fun getPowerSaveState(serviceType: Int): PowerSaveState? =
        PowerSaveState.Builder().setBatterySaverEnabled(false).setGlobalBatterySaverEnabled(false).build()
    override fun setPowerSaveModeEnabled(mode: Boolean): Boolean {
        enforceSystem("setPowerSaveModeEnabled", "POWER_SAVER")
        return false
    }
    override fun isBatterySaverSupported(): Boolean = false
    override fun getFullPowerSavePolicy(): BatterySaverPolicyConfig? = BatterySaverPolicyConfig.Builder().build()
    override fun setFullPowerSavePolicy(config: BatterySaverPolicyConfig?): Boolean = false
    override fun setDynamicPowerSaveHint(powerSaveHint: Boolean, disableThreshold: Int): Boolean = false
    override fun setAdaptivePowerSavePolicy(config: BatterySaverPolicyConfig?): Boolean = false
    override fun setAdaptivePowerSaveEnabled(enabled: Boolean): Boolean = false
    override fun getPowerSaveModeTrigger(): Int = PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE
    override fun setBatteryDischargePrediction(timeRemaining: android.os.ParcelDuration?, isCustomized: Boolean) = enforceSystem("setBatteryDischargePrediction", "BATTERY_PREDICTION")
    override fun getBatteryDischargePrediction(): android.os.ParcelDuration? = null
    override fun isBatteryDischargePredictionPersonalized(): Boolean = false
    override fun isDeviceIdleMode(): Boolean = false
    override fun isLightDeviceIdleMode(): Boolean = false
    override fun isLowPowerStandbySupported(): Boolean = false
    override fun isLowPowerStandbyEnabled(): Boolean = false
    override fun setLowPowerStandbyEnabled(enabled: Boolean) = enforceSystem("setLowPowerStandbyEnabled", "MANAGE_LOW_POWER_STANDBY")
    override fun setLowPowerStandbyActiveDuringMaintenance(activeDuringMaintenance: Boolean) = enforceSystem("setLowPowerStandbyActiveDuringMaintenance", "MANAGE_LOW_POWER_STANDBY")
    override fun forceLowPowerStandbyActive(active: Boolean) = enforceSystem("forceLowPowerStandbyActive", "MANAGE_LOW_POWER_STANDBY")
    override fun setLowPowerStandbyPolicy(policy: IPowerManager.LowPowerStandbyPolicy?) = enforceSystem("setLowPowerStandbyPolicy", "MANAGE_LOW_POWER_STANDBY")
    override fun getLowPowerStandbyPolicy(): IPowerManager.LowPowerStandbyPolicy? = null
    override fun isExemptFromLowPowerStandby(): Boolean = true
    override fun isReasonAllowedInLowPowerStandby(reason: Int): Boolean = true
    override fun isFeatureAllowedInLowPowerStandby(feature: String?): Boolean = true
    override fun acquireLowPowerStandbyPorts(token: IBinder?, ports: MutableList<IPowerManager.LowPowerStandbyPortDescription?>?) {}
    override fun releaseLowPowerStandbyPorts(token: IBinder?) {}
    override fun getActiveLowPowerStandbyPorts(): MutableList<IPowerManager.LowPowerStandbyPortDescription?>? = ArrayList()
    override fun setPowerBoost(boost: Int, durationMs: Int) {}
    override fun setPowerMode(mode: Int, enabled: Boolean) {}
    override fun setPowerModeChecked(mode: Int, enabled: Boolean): Boolean = false
    override fun reboot(confirm: Boolean, reason: String?, wait: Boolean) = enforceSystem("reboot", "REBOOT")
    override fun rebootSafeMode(confirm: Boolean, wait: Boolean) = enforceSystem("rebootSafeMode", "REBOOT")
    override fun shutdown(confirm: Boolean, reason: String?, wait: Boolean) = enforceSystem("shutdown", "REBOOT")
    override fun crash(message: String?) = enforceSystem("crash", "REBOOT")
    override fun getLastShutdownReason(): Int = PowerManager.SHUTDOWN_REASON_UNKNOWN
    override fun getLastSleepReason(): Int = PowerManager.GO_TO_SLEEP_REASON_MIN
    override fun setStayOnSetting(`val`: Int) = enforceSystem("setStayOnSetting", "WRITE_SETTINGS")
    override fun setAttentionLight(on: Boolean, color: Int) = enforceSystem("setAttentionLight", "DEVICE_POWER")
    override fun setDozeAfterScreenOff(on: Boolean) = enforceSystem("setDozeAfterScreenOff", "DEVICE_POWER")
    override fun isAmbientDisplayAvailable(): Boolean = false
    override fun suppressAmbientDisplay(token: String?, suppress: Boolean) {}
    override fun isAmbientDisplaySuppressedForToken(token: String?): Boolean = false
    override fun isAmbientDisplaySuppressed(): Boolean = false
    override fun isAmbientDisplaySuppressedForTokenByApp(token: String?, appUid: Int): Boolean = false
    override fun forceSuspend(): Boolean {
        enforceSystem("forceSuspend", "DEVICE_POWER")
        return false
    }

    private companion object {
        const val TAG = "HostedPowerManager"
    }
}

/**
 * The thermal service `PowerManager` binds next to the power service
 * (`Context.THERMAL_SERVICE`). Source note: `com.android.server.power.ThermalManagerService`
 * for a device whose thermal HAL reports nothing: no temperatures, no cooling devices,
 * status NONE, unknown headroom, listeners accepted and never called.
 */
class HostedThermalService : UnsupportedThermalService() {
    private val lock = Any()
    private val eventListeners = ArrayList<android.os.IThermalEventListener>()
    private val statusListeners = ArrayList<android.os.IThermalStatusListener>()
    private val headroomListeners = ArrayList<android.os.IThermalHeadroomListener>()

    override fun registerThermalEventListener(listener: android.os.IThermalEventListener?): Boolean =
        synchronized(lock) { eventListeners.add(listener ?: return false) }
    override fun registerThermalEventListenerWithType(listener: android.os.IThermalEventListener?, type: Int): Boolean =
        registerThermalEventListener(listener)
    override fun unregisterThermalEventListener(listener: android.os.IThermalEventListener?): Boolean =
        synchronized(lock) { eventListeners.removeAll { it.asBinder() == listener?.asBinder() } }
    override fun getCurrentTemperatures(): Array<android.os.Temperature?>? = arrayOfNulls(0)
    override fun getCurrentTemperaturesWithType(type: Int): Array<android.os.Temperature?>? = arrayOfNulls(0)
    override fun registerThermalStatusListener(listener: android.os.IThermalStatusListener?): Boolean {
        val l = listener ?: return false
        synchronized(lock) { statusListeners.add(l) }
        // ThermalManagerService reports the current status on registration.
        try {
            l.onStatusChange(PowerManager.THERMAL_STATUS_NONE)
        } catch (e: android.os.RemoteException) {
            return false
        }
        return true
    }
    override fun unregisterThermalStatusListener(listener: android.os.IThermalStatusListener?): Boolean =
        synchronized(lock) { statusListeners.removeAll { it.asBinder() == listener?.asBinder() } }
    override fun getCurrentThermalStatus(): Int = PowerManager.THERMAL_STATUS_NONE
    override fun getCurrentCoolingDevices(): Array<android.os.CoolingDevice?>? = arrayOfNulls(0)
    override fun getCurrentCoolingDevicesWithType(type: Int): Array<android.os.CoolingDevice?>? = arrayOfNulls(0)
    /** No thermal HAL: the headroom is unknown (NaN), as ThermalManagerService reports without one. */
    override fun getThermalHeadroom(forecastSeconds: Int): Float = Float.NaN
    override fun getThermalHeadroomThresholds(): FloatArray? = FloatArray(PowerManager.THERMAL_STATUS_SHUTDOWN + 1) { Float.NaN }
    override fun registerThermalHeadroomListener(listener: android.os.IThermalHeadroomListener?): Boolean =
        synchronized(lock) { headroomListeners.add(listener ?: return false) }
    override fun unregisterThermalHeadroomListener(listener: android.os.IThermalHeadroomListener?): Boolean =
        synchronized(lock) { headroomListeners.removeAll { it.asBinder() == listener?.asBinder() } }
}
