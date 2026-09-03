package dev.artzig.system

import android.app.AlarmManager
import android.app.IAlarmCompleteListener
import android.app.IAlarmListener
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.os.WorkSource
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The session's alarm manager over the real `IAlarmManager` surface, published
 * as `Context.ALARM_SERVICE`.
 *
 * Source note: `com.android.server.alarm.AlarmManagerService` (`set` validation,
 * `setImplLocked`, `removeLocked`, `deliverAlarmsLocked`, `DeliveryTracker`,
 * the repeating-alarm reschedule and `getNextAlarmClock`). The AOSP service
 * sits on the kernel alarm driver, doze, app standby and battery stats; this
 * host has none of those, so alarms are timers on one scheduled executor:
 * RTC types are converted to the elapsed clock at set time (AOSP does the
 * same in `setImpl`), a window is honored as "no earlier than", and delivery
 * sends the PendingIntent as its creator or calls the IAlarmListener with a
 * completion callback exactly as `DeliveryTracker` does. Exact alarms are
 * always permitted (no SCHEDULE_EXACT_ALARM policy without a shade).
 */
class HostedAlarmManagerService(private val context: Context) : UnsupportedAlarmManager() {
    /** The package manager binds calling packages to uids (AlarmManagerService checks the same). */
    @Volatile var packageService: HostedPackageManagerService? = null

    private class Alarm(
        val type: Int,
        var whenElapsed: Long,
        val windowLength: Long,
        val repeatInterval: Long,
        val flags: Int,
        val operation: PendingIntent?,
        val listener: IAlarmListener?,
        val listenerTag: String?,
        val alarmClock: AlarmManager.AlarmClockInfo?,
        val packageName: String?,
        val uid: Int,
    ) {
        var count = 0
        var future: ScheduledFuture<*>? = null
        val listenerBinder: IBinder? = listener?.asBinder()

        fun matches(operation: PendingIntent?, listener: IAlarmListener?): Boolean =
            if (operation != null) this.operation == operation
            else listener != null && listenerBinder == listener.asBinder()
    }

    private val lock = Any()
    private val alarms = ArrayList<Alarm>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AlarmManager").apply { isDaemon = true }
    }

    // ------------------------------------------------------------ set / remove

    override fun set(
        callingPackage: String?,
        type: Int,
        triggerAtTime: Long,
        windowLength: Long,
        interval: Long,
        flags: Int,
        operation: PendingIntent?,
        listener: IAlarmListener?,
        listenerTag: String?,
        workSource: WorkSource?,
        alarmClock: AlarmManager.AlarmClockInfo?,
    ) {
        val callingUid = Binder.getCallingUid()
        // Package check: the calling package must belong to the calling uid.
        if (callingUid != Process.SYSTEM_UID) {
            val packages = packageService?.getPackagesForUid(callingUid)
            if (packages != null && packages.isNotEmpty() && !packages.contains(callingPackage)) {
                throw SecurityException("Package $callingPackage does not belong to the calling uid $callingUid")
            }
        }
        // Repeating alarms must use PendingIntent, not direct listener
        if (interval != 0L && listener != null) {
            throw IllegalArgumentException("Repeating alarms cannot use AlarmReceivers")
        }
        if (operation == null && listener == null) {
            throw IllegalArgumentException("PendingIntent and listener are both null")
        }
        if (workSource != null && callingUid != Process.SYSTEM_UID &&
            packageService?.checkUidPermission(android.Manifest.permission.UPDATE_DEVICE_STATS, callingUid) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("AlarmManager.set: uid $callingUid requires android.permission.UPDATE_DEVICE_STATS")
        }
        var trigger = triggerAtTime
        if (trigger < 0) {
            Log.w(TAG, "Invalid alarm trigger time! $trigger from uid=$callingUid pid=${Binder.getCallingPid()}")
            trigger = 0
        }
        var window = windowLength
        if (window > AlarmManager.INTERVAL_HALF_DAY) {
            Log.w(TAG, "Window length $window ms suspiciously long; limiting to 1 hour")
            window = AlarmManager.INTERVAL_HOUR
        }
        var effectiveFlags = flags
        // FLAG_IDLE_UNTIL is only for the system, and the flags reserved to internal policy are cleared.
        if (callingUid != Process.SYSTEM_UID) effectiveFlags = effectiveFlags and AlarmManager.FLAG_IDLE_UNTIL.inv()
        effectiveFlags = effectiveFlags and
            (AlarmManager.FLAG_WAKE_FROM_IDLE or AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED or AlarmManager.FLAG_ALLOW_WHILE_IDLE_COMPAT).inv()
        if (alarmClock != null) {
            effectiveFlags = effectiveFlags or AlarmManager.FLAG_WAKE_FROM_IDLE
            window = 0
        }
        // AlarmManagerService.setImpl: RTC alarms are converted to the elapsed clock.
        val nowElapsed = SystemClock.elapsedRealtime()
        val whenElapsed = when (type) {
            AlarmManager.RTC, AlarmManager.RTC_WAKEUP -> nowElapsed + (trigger - System.currentTimeMillis())
            AlarmManager.ELAPSED_REALTIME, AlarmManager.ELAPSED_REALTIME_WAKEUP -> trigger
            else -> throw IllegalArgumentException("Invalid alarm type $type")
        }
        val alarm = Alarm(type, whenElapsed, window, interval, effectiveFlags, operation, listener, listenerTag, alarmClock, callingPackage, callingUid)
        synchronized(lock) {
            // setImplLocked: a new alarm replaces every earlier one for the same operation/listener.
            removeLocked(operation, listener)
            alarms.add(alarm)
            scheduleLocked(alarm, nowElapsed)
        }
        Log.i(TAG, "set: pkg=$callingPackage type=$type in ${whenElapsed - nowElapsed}ms window=$window interval=$interval " +
            (if (listener != null) "listener=$listenerTag" else "operation=${operation?.creatorPackage}"))
    }

    private fun scheduleLocked(alarm: Alarm, nowElapsed: Long) {
        val delay = Math.max(0L, alarm.whenElapsed - nowElapsed)
        alarm.future = executor.schedule({ deliver(alarm) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun removeLocked(operation: PendingIntent?, listener: IAlarmListener?): Boolean {
        var removed = false
        val iterator = alarms.iterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.matches(operation, listener)) {
                alarm.future?.cancel(false)
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    override fun remove(operation: PendingIntent?, listener: IAlarmListener?) {
        if (operation == null && listener == null) {
            Log.w(TAG, "remove() with no intent or listener")
            return
        }
        synchronized(lock) { removeLocked(operation, listener) }
    }

    override fun removeAll(packageName: String?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("removeAll requires the system")
        synchronized(lock) {
            val iterator = alarms.iterator()
            while (iterator.hasNext()) {
                val alarm = iterator.next()
                if (alarm.packageName == packageName) {
                    alarm.future?.cancel(false)
                    iterator.remove()
                }
            }
        }
    }

    // ------------------------------------------------------------ delivery (DeliveryTracker)

    /** The completion callback listener alarms receive (DeliveryTracker.alarmComplete). */
    private val completeListener = object : IAlarmCompleteListener.Stub() {
        override fun alarmComplete(who: IBinder?) {
            Log.d(TAG, "listener alarm complete: $who")
        }
    }

    private fun deliver(alarm: Alarm) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!alarms.remove(alarm)) return // removed while pending
            alarm.count++
            if (alarm.repeatInterval > 0) {
                // deliverAlarmsLocked: the next trigger is the first period boundary after now.
                val delta = nowElapsed - alarm.whenElapsed
                val skipped = if (delta > 0) delta / alarm.repeatInterval else 0
                alarm.whenElapsed += alarm.repeatInterval * (1 + skipped)
                alarms.add(alarm)
                scheduleLocked(alarm, nowElapsed)
            }
        }
        val operation = alarm.operation
        if (operation != null) {
            try {
                // DeliveryTracker.deliverLocked: the intent carries the alarm count;
                // the PendingIntent sends as its creator on the real broadcast queue.
                operation.send(context, 0, Intent().putExtra(Intent.EXTRA_ALARM_COUNT, alarm.count), null, null)
            } catch (e: PendingIntent.CanceledException) {
                // The PendingIntent was cancelled: the alarm goes with it (AOSP removeImpl).
                Log.w(TAG, "alarm PendingIntent cancelled: pkg=${alarm.packageName}")
                synchronized(lock) { removeLocked(operation, null) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "alarm delivery failed: pkg=${alarm.packageName}", e)
            }
        } else {
            try {
                alarm.listener?.doAlarm(completeListener)
            } catch (e: RemoteException) {
                // The listener's process is gone; nothing else to deliver to.
                Log.w(TAG, "alarm listener gone: pkg=${alarm.packageName} tag=${alarm.listenerTag}")
            }
        }
    }

    // ------------------------------------------------------------ queries

    override fun getNextAlarmClock(userId: Int): AlarmManager.AlarmClockInfo? = synchronized(lock) {
        alarms.filter { it.alarmClock != null }.minByOrNull { it.whenElapsed }?.alarmClock
    }

    override fun getNextWakeFromIdleTime(): Long = synchronized(lock) {
        alarms.filter { it.flags and AlarmManager.FLAG_WAKE_FROM_IDLE != 0 }.minOfOrNull { it.whenElapsed } ?: Long.MAX_VALUE
    }

    /** No SCHEDULE_EXACT_ALARM policy on the desktop: every app may schedule exact alarms. */
    override fun canScheduleExactAlarms(packageName: String?): Boolean = true
    override fun hasScheduleExactAlarm(packageName: String?, userId: Int): Boolean = true

    /** The host clock is Linux's; the session may not set it (AOSP: SET_TIME, false when nothing changed). */
    override fun setTime(millis: Long): Boolean {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setTime requires android.permission.SET_TIME")
        return false
    }

    override fun setTimeZone(zone: String?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setTimeZone requires android.permission.SET_TIME_ZONE")
        // AOSP writes persist.sys.timezone and broadcasts ACTION_TIMEZONE_CHANGED; the
        // hosted session follows the desktop's zone.
    }

    override fun getConfigVersion(): Int = 0

    /** Pending alarms for dumps and the system test. */
    fun pendingAlarmCount(): Int = synchronized(lock) { alarms.size }

    private companion object {
        const val TAG = "HostedAlarmManager"
    }
}
