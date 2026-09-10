package dev.artzig.system

import android.app.usage.UsageEvents
import android.content.res.Configuration
import com.android.server.usage.HostedConfigurationUsage

object HostedConfigurationUsageSmoke {
    fun run() {
        verifyActivityUsage()
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

    private fun verifyActivityUsage() {
        var now = 100L
        val history = com.android.server.usage.HostedActivityUsage { now }
        val first = android.content.ComponentName("fixture", "First")
        val second = android.content.ComponentName("fixture", "Second")
        val root = android.content.ComponentName("fixture.root", "Root")
        history.report(first, 0, UsageEvents.Event.ACTIVITY_RESUMED, 1, root)
        now = 150
        history.report(second, 0, UsageEvents.Event.ACTIVITY_RESUMED, 2, root)
        now = 200
        history.report(first, 0, UsageEvents.Event.ACTIVITY_PAUSED, 1, null)
        history.report(first, 10, UsageEvents.Event.ACTIVITY_RESUMED, 1, root)
        now = 250
        history.report(first, 0, UsageEvents.Event.ACTIVITY_STOPPED, 1, null)
        now = 300
        history.report(second, 0, UsageEvents.Event.ACTIVITY_PAUSED, 2, null)
        now = 400
        history.report(second, 0, UsageEvents.Event.ACTIVITY_DESTROYED, 2, null)
        now = 450
        history.report(second, 0, UsageEvents.Event.ACTIVITY_DESTROYED, 2, null)
        now = 500
        history.report("fixture", 0, UsageEvents.Event.APP_COMPONENT_USED)
        val stats = history.query(0, 0, 600).single()
        check(stats.totalTimeInForeground == 200L && stats.totalTimeVisible == 300L)
        check(stats.lastTimeAnyComponentUsed == 500L)
        val events = history.events(0, 0, 600)
        check(events.map { it.eventType } == listOf(UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED))
        check(events.all { it.taskRootPackageName == root.packageName && it.taskRootClassName == root.className })
        check(history.events(10, 0, 600).single().instanceId == 1)
        check(history.events(0, 150, 300).map { it.timeStamp } == listOf(150L, 200L, 250L))
        stats.mTotalTimeVisible = 999
        events[0].mClass = "mutated"
        check(history.query(0, 0, 600).single().totalTimeVisible == 300L)
        check(history.events(0, 0, 600).first().className == "First")

        val service = HostedUsageStatsService(stockActivities = true)
        val local = Class.forName("android.app.usage.HostedUsageStatsManagerInternal")
            .getConstructor(HostedUsageStatsService::class.java).newInstance(service)
            as android.app.usage.UsageStatsManagerInternal
        val type = android.app.usage.UsageStatsManagerInternal::class.java
        val previous = com.android.server.LocalServices.getService(type)
        com.android.server.LocalServices.removeServiceForTest(type)
        com.android.server.LocalServices.addService(type, local)
        try {
            val activities = HostActivityManagerInternalAccess(HostedActivityManagerService(),
                HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle())), HostedUserLifecycle())
            val token = android.os.Binder()
            var destroyed = false
            activities.setVoiceInteractionManagerProvider {
                check(it === token)
                // Usage reporting precedes the voice notification.
                val e = UsageEvents.Event()
                val queried = service.queryEvents(0, System.currentTimeMillis() + 1, "android")!!
                val types = ArrayList<Int>()
                while (queried.getNextEvent(e)) types.add(e.eventType)
                check(types == listOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_STOPPED))
                destroyed = true
            }
            activities.updateActivityUsageStats(first, 0, UsageEvents.Event.ACTIVITY_RESUMED, token, root, android.app.assist.ActivityId(2, token))
            activities.updateActivityUsageStats(first, 0, UsageEvents.Event.ACTIVITY_DESTROYED, token, null, android.app.assist.ActivityId(2, token))
            check(destroyed)
            val e = UsageEvents.Event()
            val queried = service.queryEvents(0, System.currentTimeMillis() + 1, "android")!!
            check(queried.getNextEvent(e) && e.instanceId == token.hashCode())
            check(e.taskRootPackageName == root.packageName && e.taskRootClassName == root.className)
        } finally {
            com.android.server.LocalServices.removeServiceForTest(type)
            if (previous != null) com.android.server.LocalServices.addService(type, previous)
        }
    }
}
