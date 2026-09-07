package com.android.server.usage

import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStats
import android.content.ComponentName

/** UsageStatsService.reportEvent and UserUsageStatsService.reportEvent at the
 * hosted persistence boundary. Stock IntervalStats/UsageStats own overlapping
 * activity accounting; the host keeps session history without Android's disk
 * database, app-time-limit controller, standby estimation or daily rollover. */
class HostedActivityUsage(private val now: () -> Long = System::currentTimeMillis) {
    private class User(val stats: IntervalStats) {
        val visible = HashMap<Int, ComponentName?>()
        var lastBackgroundedPackage: String? = null
    }
    private val lock = Any()
    private val users = HashMap<Int, User>()

    fun report(component: ComponentName, userId: Int, type: Int, instanceId: Int,
        taskRoot: ComponentName?) = report(Event(type, now()).apply {
            mPackage = component.packageName
            mClass = component.className
            mInstanceId = instanceId
            mTaskRootPackage = taskRoot?.packageName
            mTaskRootClass = taskRoot?.className
        }, userId)

    fun report(packageName: String, userId: Int, type: Int) =
        report(Event(type, now()).apply { mPackage = packageName }, userId)

    private fun report(event: Event, userId: Int) = synchronized(lock) {
        val user = users.getOrPut(userId) {
            User(IntervalStats().apply { beginTime = event.mTimeStamp; endTime = event.mTimeStamp })
        }
        fun root() = event.mTaskRootPackage?.let { ComponentName(it, event.mTaskRootClass) }
        fun repairRoot(previous: ComponentName?) {
            if (event.mTaskRootPackage == null) {
                event.mTaskRootPackage = previous?.packageName
                event.mTaskRootClass = previous?.className
            }
        }
        when (event.mEventType) {
            Event.ACTIVITY_RESUMED -> if (!user.visible.containsKey(event.mInstanceId))
                user.visible[event.mInstanceId] = root()
            Event.ACTIVITY_PAUSED -> {
                if (!user.visible.containsKey(event.mInstanceId)) user.visible[event.mInstanceId] = root()
                else repairRoot(user.visible[event.mInstanceId])
            }
            Event.ACTIVITY_DESTROYED, Event.ACTIVITY_STOPPED -> {
                event.mEventType = Event.ACTIVITY_STOPPED
                if (!user.visible.containsKey(event.mInstanceId)) return@synchronized
                repairRoot(user.visible.remove(event.mInstanceId))
            }
        }
        if (event.mEventType !in setOf(Event.SYSTEM_INTERACTION, Event.FLUSH_TO_DISK,
                Event.DEVICE_SHUTDOWN, Event.APP_COMPONENT_USED)) user.stats.addEvent(event)
        val launch = event.mEventType == Event.ACTIVITY_RESUMED &&
            event.mPackage != user.lastBackgroundedPackage
        if (event.mEventType == Event.ACTIVITY_PAUSED) user.lastBackgroundedPackage = event.mPackage
        user.stats.update(event.mPackage, event.mClass, event.mTimeStamp, event.mEventType, event.mInstanceId)
        if (launch) user.stats.incrementAppLaunchCount(event.mPackage)
    }

    fun query(userId: Int, begin: Long, end: Long): List<UsageStats> = synchronized(lock) {
        val stats = users[userId]?.stats ?: return@synchronized emptyList()
        if (begin >= end || stats.beginTime >= end || stats.endTime < begin) return@synchronized emptyList()
        stats.packageStats.values.map(::UsageStats)
    }

    fun events(userId: Int, begin: Long, end: Long): List<Event> = synchronized(lock) {
        val stats = users[userId]?.stats ?: return@synchronized emptyList()
        buildList {
            for (i in 0 until stats.events.size()) {
                val event = stats.events.get(i)
                if (event.mTimeStamp >= begin && event.mTimeStamp < end) add(Event(event))
            }
        }
    }
}
