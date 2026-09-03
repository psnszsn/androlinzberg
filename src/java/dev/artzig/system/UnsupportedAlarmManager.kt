@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IAlarmManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedAlarmManager : android.app.IAlarmManager.Stub() {
    override fun set(callingPackage: String?, type: Int, triggerAtTime: Long, windowLength: Long, interval: Long, flags: Int, operation: android.app.PendingIntent?, listener: android.app.IAlarmListener?, listenerTag: String?, workSource: android.os.WorkSource?, alarmClock: android.app.AlarmManager.AlarmClockInfo?) { throw UnsupportedOperationException("IAlarmManager.set is not hosted") }
    override fun setTime(millis: Long): Boolean = throw UnsupportedOperationException("IAlarmManager.setTime is not hosted")
    override fun setTimeZone(zone: String?) { throw UnsupportedOperationException("IAlarmManager.setTimeZone is not hosted") }
    override fun remove(operation: android.app.PendingIntent?, listener: android.app.IAlarmListener?) { throw UnsupportedOperationException("IAlarmManager.remove is not hosted") }
    override fun removeAll(packageName: String?) { throw UnsupportedOperationException("IAlarmManager.removeAll is not hosted") }
    override fun getNextWakeFromIdleTime(): Long = throw UnsupportedOperationException("IAlarmManager.getNextWakeFromIdleTime is not hosted")
    override fun getNextAlarmClock(userId: Int): android.app.AlarmManager.AlarmClockInfo? = throw UnsupportedOperationException("IAlarmManager.getNextAlarmClock is not hosted")
    override fun canScheduleExactAlarms(packageName: String?): Boolean = throw UnsupportedOperationException("IAlarmManager.canScheduleExactAlarms is not hosted")
    override fun hasScheduleExactAlarm(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IAlarmManager.hasScheduleExactAlarm is not hosted")
    override fun getConfigVersion(): Int = throw UnsupportedOperationException("IAlarmManager.getConfigVersion is not hosted")
}
