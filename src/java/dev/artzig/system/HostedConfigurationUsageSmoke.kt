package dev.artzig.system

import android.app.usage.UsageEvents
import android.content.res.Configuration
import com.android.server.usage.HostedConfigurationUsage

object HostedConfigurationUsageSmoke {
    fun run() {
        var now = 100L
        val usage = HostedConfigurationUsage { now }
        val config = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            densityDpi = 160
        }
        usage.report(config, 0)
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        now = 200
        usage.report(config, 0)
        now = 300
        config.orientation = Configuration.ORIENTATION_PORTRAIT
        usage.report(config, 0)
        val stats = usage.query(0, 0, 400).associateBy { it.configuration.orientation }
        check(stats.size == 2)
        check(stats.getValue(Configuration.ORIENTATION_PORTRAIT).activationCount == 2)
        check(stats.getValue(Configuration.ORIENTATION_PORTRAIT).totalTimeActive == 100L)
        check(stats.getValue(Configuration.ORIENTATION_LANDSCAPE).activationCount == 1)
        check(stats.getValue(Configuration.ORIENTATION_LANDSCAPE).totalTimeActive == 100L)
        check(usage.query(1, 0, 400).isEmpty())
        check(usage.query(0, 400, 500).isEmpty())
        val events = usage.events(0, 100, 300)
        check(events.size == 2 && events.all { it.eventType == UsageEvents.Event.CONFIGURATION_CHANGE })
        check(events[0].configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        check(events[0].configuration.densityDpi == 160)
        check(events[1].configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        check(events[1].configuration.densityDpi == 0) // Stock configuration delta.
        events[0].configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        stats.values.first().configuration.densityDpi = 900
        check(usage.events(0, 100, 200).single().configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        check(usage.query(0, 0, 400).all { it.configuration.densityDpi == 160 })

        val service = HostedUsageStatsService()
        val local = Class.forName("android.app.usage.HostedUsageStatsManagerInternal")
            .getConstructor(HostedUsageStatsService::class.java)
            .newInstance(service) as android.app.usage.UsageStatsManagerInternal
        local.reportConfigurationChange(null, 0)
        val begin = System.currentTimeMillis()
        local.reportConfigurationChange(config, 0)
        val end = System.currentTimeMillis() + 1
        check(service.queryConfigurationStats(0, begin, end, "android")!!.list.size == 1)
        val result = checkNotNull(service.queryEvents(begin, end, "android"))
        val event = UsageEvents.Event()
        check(result.getNextEvent(event) && event.eventType == UsageEvents.Event.CONFIGURATION_CHANGE)
        check(event.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
    }
}
