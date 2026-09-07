package dev.artzig

import android.app.ActivityThread
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Binder
import android.os.IBinder
import android.os.LocaleList
import java.util.Locale

class StockConfigurationService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
    override fun onCreate() { super.onCreate(); created = true }
    override fun onDestroy() { super.onDestroy(); created = false }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        check(resources.configuration.orientation == config.orientation)
        StockConfigurationSmoke.events += "service:${config.fontScale}"
        sequences += config.seq
    }
    companion object {
        var created = false
        val sequences = ArrayList<Int>()
    }
}

internal object StockConfigurationSmoke {
    val events = ArrayList<String>()

    fun run(pump: () -> Unit) {
        HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val thread = ActivityThread.currentActivityThread()
        val application = checkNotNull(ActivityThread.currentApplication())
        val original = Configuration(application.resources.configuration)
        val appThread: android.app.IApplicationThread = thread.applicationThread
        val token = Binder()
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) {
                check(application.resources.configuration.orientation == config.orientation)
                events += "application:${config.fontScale}"
            }
            override fun onLowMemory() = Unit
            override fun onTrimMemory(level: Int) = Unit
        }
        application.registerComponentCallbacks(callback)
        events.clear()
        StockConfigurationService.sequences.clear()
        try {
            appThread.scheduleCreateService(token, ServiceInfo().apply {
                applicationInfo = application.applicationInfo
                packageName = application.packageName
                name = StockConfigurationService::class.java.name
            }, null, 0)
            pump()
            check(StockConfigurationService.created)
            val first = Configuration(original).apply {
                seq = maxOf(1, thread.configuration.seq + 1)
                fontScale += 0.25f
                orientation = if (original.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                setLocales(LocaleList(Locale.US, Locale.FRANCE))
            }
            thread.handleConfigurationChanged(first, Context.DEVICE_ID_DEFAULT)
            check(events == listOf("application:${first.fontScale}", "service:${first.fontScale}")) { events }
            check(LocaleList.getDefault().get(0) == Locale.US)
            thread.handleConfigurationChanged(Configuration(first), Context.DEVICE_ID_DEFAULT)
            check(events.size == 2)
            // Sequence zero is unconditional, so it cannot represent a stale delivery.
            // Use increasing nonzero sequences to prove a pending newer value wins.
            val next = Configuration(first).apply { seq += 1; fontScale += 0.25f }
            thread.updatePendingConfiguration(next)
            thread.handleConfigurationChanged(first, Context.DEVICE_ID_DEFAULT)
            check(events == listOf("application:${first.fontScale}", "service:${first.fontScale}",
                "application:${next.fontScale}", "service:${next.fontScale}")) { events }
            check(StockConfigurationService.sequences.size == 2)
            System.err.println("stock-configuration: resources precede application/service callbacks, with equivalent-update suppression and pending updates")
        } finally {
            appThread.scheduleStopService(token)
            pump()
            application.unregisterComponentCallbacks(callback)
            // Sequence zero is AOSP's unconditional configuration update.
            thread.handleConfigurationChanged(Configuration(original).apply { seq = 0 }, Context.DEVICE_ID_DEFAULT)
            check(!StockConfigurationService.created)
        }
    }
}
