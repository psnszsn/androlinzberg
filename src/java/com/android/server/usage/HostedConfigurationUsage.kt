package com.android.server.usage

import android.app.usage.ConfigurationStats
import android.app.usage.UsageEvents
import android.content.res.Configuration

/**
 * UsageStatsService.LocalService.reportConfigurationChange and
 * UserUsageStatsService.reportEvent over stock IntervalStats. The host retains
 * session history in memory instead of the Android usage database and rollover
 * files. Configuration copies, event deltas and aggregation remain stock.
 */
class HostedConfigurationUsage(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val intervals = mutableMapOf<Int, IntervalStats>()

    fun report(config: Configuration, userId: Int) = synchronized(lock) {
        val timestamp = now()
        val stats = intervals.getOrPut(userId) {
            IntervalStats().apply { beginTime = timestamp; endTime = timestamp }
        }
        val full = Configuration(config)
        val event = UsageEvents.Event(UsageEvents.Event.CONFIGURATION_CHANGE, timestamp).apply {
            mPackage = "android"
            mConfiguration = stats.activeConfiguration?.let { Configuration.generateDelta(it, full) } ?: Configuration(full)
        }
        stats.addEvent(event)
        stats.updateConfigurationStats(full, timestamp)
    }

    fun query(userId: Int, begin: Long, end: Long): List<ConfigurationStats> = synchronized(lock) {
        val stats = intervals[userId] ?: return@synchronized emptyList()
        if (begin >= end || stats.beginTime >= end || stats.endTime < begin)
            return@synchronized emptyList()
        stats.configurations.values.map {
            ConfigurationStats(it).apply { mConfiguration = Configuration(it.mConfiguration) }
        }
    }

    fun events(userId: Int, begin: Long, end: Long): List<UsageEvents.Event> = synchronized(lock) {
        val stats = intervals[userId] ?: return@synchronized emptyList()
        buildList {
            for (i in 0 until stats.events.size()) {
                val event = stats.events.get(i)
                if (event.mTimeStamp >= begin && event.mTimeStamp < end) {
                    add(UsageEvents.Event(event).apply { mConfiguration = Configuration(event.mConfiguration) })
                }
            }
        }
    }
}
