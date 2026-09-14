package dev.artzig.system

import android.content.Intent
import android.os.BatteryManager
import android.os.BatteryProperty
import android.os.Process
import android.util.Log
import com.android.internal.app.IBatteryStats
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The session's battery state: the `IBatteryPropertiesRegistrar` binder
 * (`"batteryproperties"`) the real `BatteryManager` queries, the sticky
 * `ACTION_BATTERY_CHANGED` broadcast on the real broadcast queue, and the
 * `IBatteryStats` binder (`"batterystats"`) `BatteryManager.isCharging` asks.
 *
 * Source note: `com.android.server.BatteryService` (`processValuesLocked`,
 * `sendBatteryChangedIntentLocked`, the `BatteryPropertiesRegistrar` and the
 * `plugType`/`isCharging` derivations). The health HAL is replaced by the Linux
 * power-supply class: the first `Battery` supply provides level, status,
 * health, voltage, temperature, technology and the charge/current/energy
 * counters, and any online `Mains`/`USB` supply is the plug type. A host without
 * a battery reports what a docked device reports: not present, level 100, AC
 * power. The supply is re-read on the AOSP update cadence (every 60 s) and the
 * sticky broadcast is re-sent when anything changed.
 */
class HostedBatteryService(private val activityManager: HostedActivityManagerService) {
    /** BatteryService.HealthInfo reduced to what the broadcast and properties carry. */
    data class Snapshot(
        val present: Boolean,
        val level: Int,
        val status: Int,
        val health: Int,
        val plugged: Int,
        val voltageMillivolts: Int,
        val temperatureTenths: Int,
        val technology: String,
        val chargeCounterMicroAh: Long,
        val currentNowMicroA: Long,
        val currentAverageMicroA: Long,
        val energyCounterNanoWh: Long,
        val cycleCount: Int,
    )

    private val lock = Any()
    private var snapshot: Snapshot = readSnapshot()
    private val activityUsage = HostedActivityBatteryUsage(isOnBattery(snapshot))
    private val videoUsage = HostedVideoBatteryUsage(isOnBattery(snapshot))
    private var sequence = 0
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "BatteryService").apply { isDaemon = true } }

    /** BatteryStatsService queues its timestamped activity timer update before
     * publishing ACTIVITY_FOREGROUND_STATE_CHANGED. */
    fun updateActivityUsage(activity: android.content.ComponentName, uid: Int, resumed: Boolean) {
        synchronized(lock) {
            val timestamp = android.os.SystemClock.elapsedRealtime()
            executor.execute { activityUsage.activity(uid, resumed, timestamp) }
        }
        com.android.internal.util.FrameworkStatsLog.write(
            com.android.internal.util.FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED,
            uid, activity.packageName, activity.shortClassName,
            if (resumed) com.android.internal.util.FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__FOREGROUND
            else com.android.internal.util.FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__BACKGROUND)
    }

    fun updateForegroundTimeIfOnBattery(packageName: String, uid: Int, duration: Long) {
        synchronized(lock) { executor.execute { activityUsage.foregroundCpu(packageName, uid, duration) } }
    }

    fun updateProcessCpu(processName: String, uid: Int, userMs: Long, systemMs: Long) {
        synchronized(lock) { executor.execute { activityUsage.processCpu(processName, uid, userMs, systemMs) } }
    }

    fun isolatedUidAdded(uid: Int, owner: Int) {
        synchronized(lock) { executor.execute { videoUsage.isolatedUidAdded(uid, owner) } }
    }

    fun isolatedUidRemoved(uid: Int, owner: Int) {
        synchronized(lock) { executor.execute { videoUsage.isolatedUidRemoved(uid, owner) } }
    }

    /** BatteryService.isPoweredLocked: unknown status counts as powered. */
    fun isPowered(plugTypeSet: Int): Boolean = synchronized(lock) {
        snapshot.status == BatteryManager.BATTERY_STATUS_UNKNOWN ||
            snapshot.plugged and plugTypeSet != 0
    }

    private fun isOnBattery(value: Snapshot): Boolean = value.plugged == 0 &&
        value.status != BatteryManager.BATTERY_STATUS_UNKNOWN

    val registrar: android.os.IBatteryPropertiesRegistrar.Stub = object :
        android.os.IBatteryPropertiesRegistrar.Stub(),
        android.os.IBatteryPropertiesRegistrar by unsupported<android.os.IBatteryPropertiesRegistrar>() {
        override fun asBinder(): android.os.IBinder = this

        /** BatteryPropertiesRegistrar.getProperty: 0 on success, -1 for a property the supply has not got. */
        override fun getProperty(id: Int, prop: BatteryProperty?): Int {
            prop ?: return -1
            val s = synchronized(lock) { snapshot }
            when (id) {
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER -> if (s.chargeCounterMicroAh < 0) return -1 else prop.setLong(s.chargeCounterMicroAh)
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW -> if (s.currentNowMicroA == Long.MIN_VALUE) return -1 else prop.setLong(s.currentNowMicroA)
                BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE -> if (s.currentAverageMicroA == Long.MIN_VALUE) return -1 else prop.setLong(s.currentAverageMicroA)
                BatteryManager.BATTERY_PROPERTY_CAPACITY -> prop.setLong(s.level.toLong())
                BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER -> if (s.energyCounterNanoWh < 0) return -1 else prop.setLong(s.energyCounterNanoWh)
                BatteryManager.BATTERY_PROPERTY_STATUS -> prop.setLong(s.status.toLong())
                BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY -> prop.setLong(BatteryManager.CHARGING_POLICY_DEFAULT.toLong())
                BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH -> if (!s.present) return -1 else prop.setLong(100)
                else -> return -1
            }
            return 0
        }

        override fun scheduleUpdate() {
            executor.execute { update() }
        }
    }

    /**
     * Stock IBatteryStats transport for BatteryManager and codec video timers.
     * Other methods fail through the shared unsupported diagnostic delegate.
     */
    val batteryStats: IBatteryStats.Stub = object : IBatteryStats.Stub(),
        IBatteryStats by unsupported<IBatteryStats>() {
        override fun asBinder(): android.os.IBinder = this

        // BatteryStatsService checks UPDATE_DEVICE_STATS before queuing a
        // timestamped timer change, then emits MEDIA_CODEC_STATE_CHANGED.
        private fun video(uid: Int, state: Int) {
            val caller = android.os.Binder.getCallingUid()
            if (activityManager.checkUidPermission(android.Manifest.permission.UPDATE_DEVICE_STATS, caller) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Video battery accounting requires android.permission.UPDATE_DEVICE_STATS")
            synchronized(lock) {
                val timestamp = android.os.SystemClock.elapsedRealtime()
                executor.execute {
                    when (state) {
                        com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__ON -> videoUsage.start(uid, timestamp)
                        com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__OFF -> videoUsage.stop(uid, timestamp)
                        else -> videoUsage.reset(timestamp)
                    }
                }
            }
            com.android.internal.util.FrameworkStatsLog.write_non_chained(
                com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, uid, null, state)
        }
        override fun noteStartVideo(uid: Int) = video(uid,
            com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__ON)
        override fun noteStopVideo(uid: Int) = video(uid,
            com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__OFF)
        override fun noteResetVideo() = video(-1,
            com.android.internal.util.FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__RESET)

        override fun isCharging(): Boolean {
            val s = synchronized(lock) { snapshot }
            // BatteryStatsImpl.isCharging: plugged in and not discharging.
            return s.plugged != 0 && s.status != BatteryManager.BATTERY_STATUS_DISCHARGING && s.status != BatteryManager.BATTERY_STATUS_NOT_CHARGING
        }
        override fun computeChargeTimeRemaining(): Long = -1
        override fun setChargingStateUpdateDelayMillis(delay: Int): Boolean {
            if (android.os.Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setChargingStateUpdateDelayMillis requires android.permission.POWER_SAVER")
            return false
        }
        override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
            HostedPermissions(checkNotNull(activityManager.packageService))
                .enforce(android.Manifest.permission.DUMP, "batterystats dump")
            val values = executor.submit<List<HostedActivityBatteryUsage.Snapshot>> {
                activityUsage.snapshot()
            }.get(2, TimeUnit.SECONDS)
            writer.println("Hosted session activity durations while on battery (milliseconds):")
            for (value in values) writer.println("uid=${value.uid} foreground=${value.foregroundMs} " +
                "cpu=${value.foregroundCpuMs} processes=${value.processCpuMs}")
            val video = executor.submit<HostedVideoBatteryUsage.Snapshot> { videoUsage.snapshot() }.get(2, TimeUnit.SECONDS)
            writer.println("Hosted session video duration on battery (microseconds): total=${video.totalUs} uid=${video.uidUs}")
        }
    }

    fun start() {
        sendBatteryChangedIntent(snapshot)
        executor.scheduleWithFixedDelay({ update() }, UPDATE_INTERVAL_MILLIS, UPDATE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun update() {
        val fresh = readSnapshot()
        val changed = synchronized(lock) {
            if (fresh == snapshot) false else {
                snapshot = fresh
                val timestamp = android.os.SystemClock.elapsedRealtime()
                // Queue under the same lock as activity/video events: an event
                // already waiting on this executor precedes the power change.
                executor.execute {
                    activityUsage.powerChanged(isOnBattery(fresh), timestamp)
                    videoUsage.powerChanged(isOnBattery(fresh), timestamp)
                }
                true
            }
        }
        if (changed) sendBatteryChangedIntent(fresh)
    }

    /** BatteryService.sendBatteryChangedIntentLocked. */
    private fun sendBatteryChangedIntent(s: Snapshot) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_REPLACE_PENDING)
        val seq = synchronized(lock) { ++sequence }
        intent.putExtra(BatteryManager.EXTRA_SEQUENCE, seq)
        intent.putExtra(BatteryManager.EXTRA_STATUS, s.status)
        intent.putExtra(BatteryManager.EXTRA_HEALTH, s.health)
        intent.putExtra(BatteryManager.EXTRA_PRESENT, s.present)
        intent.putExtra(BatteryManager.EXTRA_LEVEL, s.level)
        intent.putExtra(BatteryManager.EXTRA_BATTERY_LOW, s.present && s.plugged == 0 && s.level <= LOW_BATTERY_WARNING_LEVEL)
        intent.putExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE)
        intent.putExtra(BatteryManager.EXTRA_ICON_SMALL, iconFor(s))
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, s.plugged)
        intent.putExtra(BatteryManager.EXTRA_VOLTAGE, s.voltageMillivolts)
        intent.putExtra(BatteryManager.EXTRA_TEMPERATURE, s.temperatureTenths)
        intent.putExtra(BatteryManager.EXTRA_TECHNOLOGY, s.technology)
        intent.putExtra(BatteryManager.EXTRA_INVALID_CHARGER, 0)
        intent.putExtra(BatteryManager.EXTRA_MAX_CHARGING_CURRENT, 0)
        intent.putExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, 0)
        intent.putExtra(BatteryManager.EXTRA_CHARGE_COUNTER, s.chargeCounterMicroAh.toInt())
        intent.putExtra(BatteryManager.EXTRA_CYCLE_COUNT, s.cycleCount)
        intent.putExtra(BatteryManager.EXTRA_CHARGING_STATUS, BatteryManager.CHARGING_POLICY_DEFAULT)
        activityManager.broadcastStickyFromSystem(intent)
        Log.i(TAG, "battery: present=${s.present} level=${s.level} status=${s.status} plugged=${s.plugged} temp=${s.temperatureTenths}")
    }

    /** BatteryService: the status bar's small battery icon for the state. */
    private fun iconFor(s: Snapshot): Int = when (s.status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> com.android.internal.R.drawable.stat_sys_battery_charge
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> com.android.internal.R.drawable.stat_sys_battery
        else -> com.android.internal.R.drawable.stat_sys_battery_unknown
    }

    // ------------------------------------------------------------ /sys/class/power_supply

    private fun readSnapshot(): Snapshot {
        val root = File(POWER_SUPPLY_ROOT)
        val supplies = root.listFiles()?.sortedBy { it.name } ?: emptyList()
        var battery: File? = null
        var plugged = 0
        for (supply in supplies) {
            when (readLine(File(supply, "type"))) {
                "Battery" -> if (battery == null && readLine(File(supply, "present")) != "0") battery = supply
                "Mains" -> if (readLine(File(supply, "online")) == "1") plugged = plugged or BatteryManager.BATTERY_PLUGGED_AC
                "USB", "USB_PD", "USB_C", "USB_DCP", "USB_CDP", "USB_ACA" -> if (readLine(File(supply, "online")) == "1") plugged = plugged or BatteryManager.BATTERY_PLUGGED_USB
                "Wireless" -> if (readLine(File(supply, "online")) == "1") plugged = plugged or BatteryManager.BATTERY_PLUGGED_WIRELESS
            }
        }
        if (battery == null) {
            // A host without a battery: the values BatteryService reports for a
            // device without one (not present) on external power.
            return Snapshot(false, 100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_HEALTH_UNKNOWN,
                if (plugged != 0) plugged else BatteryManager.BATTERY_PLUGGED_AC, 0, 0, "", -1, Long.MIN_VALUE, Long.MIN_VALUE, -1, 0)
        }
        val status = when (readLine(File(battery, "status"))) {
            "Charging" -> BatteryManager.BATTERY_STATUS_CHARGING
            "Discharging" -> BatteryManager.BATTERY_STATUS_DISCHARGING
            "Not charging" -> BatteryManager.BATTERY_STATUS_NOT_CHARGING
            "Full" -> BatteryManager.BATTERY_STATUS_FULL
            else -> BatteryManager.BATTERY_STATUS_UNKNOWN
        }
        val health = when (readLine(File(battery, "health"))) {
            "Good" -> BatteryManager.BATTERY_HEALTH_GOOD
            "Overheat" -> BatteryManager.BATTERY_HEALTH_OVERHEAT
            "Dead" -> BatteryManager.BATTERY_HEALTH_DEAD
            "Over voltage" -> BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE
            "Unspecified failure" -> BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE
            "Cold" -> BatteryManager.BATTERY_HEALTH_COLD
            else -> BatteryManager.BATTERY_HEALTH_UNKNOWN
        }
        return Snapshot(
            present = true,
            level = readLong(File(battery, "capacity"))?.toInt()?.coerceIn(0, 100) ?: 100,
            status = status,
            health = health,
            plugged = plugged,
            voltageMillivolts = (readLong(File(battery, "voltage_now")) ?: 0L).let { (it / 1000).toInt() },
            temperatureTenths = (readLong(File(battery, "temp")) ?: 0L).toInt(),
            technology = readLine(File(battery, "technology")) ?: "",
            chargeCounterMicroAh = readLong(File(battery, "charge_counter")) ?: readLong(File(battery, "charge_now")) ?: -1L,
            currentNowMicroA = readLong(File(battery, "current_now")) ?: Long.MIN_VALUE,
            currentAverageMicroA = readLong(File(battery, "current_avg")) ?: Long.MIN_VALUE,
            energyCounterNanoWh = readLong(File(battery, "energy_now"))?.let { it * 1000 } ?: -1L,
            cycleCount = readLong(File(battery, "cycle_count"))?.toInt() ?: 0,
        )
    }

    private fun readLine(file: File): String? = try {
        if (file.isFile) file.readText().trim() else null
    } catch (e: java.io.IOException) {
        null
    }

    private fun readLong(file: File): Long? = readLine(file)?.toLongOrNull()

    private companion object {
        const val TAG = "HostedBatteryService"
        const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        const val BATTERY_SCALE = 100
        /** BatteryService.mLowBatteryWarningLevel default (config_lowBatteryWarningLevel). */
        const val LOW_BATTERY_WARNING_LEVEL = 15
        const val UPDATE_INTERVAL_MILLIS = 60_000L
    }
}
