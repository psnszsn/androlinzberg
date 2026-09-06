package dev.artzig

import android.app.Activity
import android.app.ActivityThread
import android.app.Application
import android.compat.Compatibility
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

internal object StockActivityCallbacksSmoke {
    fun run() {
        val base = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val application = checkNotNull(ActivityThread.currentApplication())
        val events = ArrayList<String>()
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) { events += "config:${config.fontScale}" }
            override fun onLowMemory() { events += "low" }
            override fun onTrimMemory(level: Int) { events += "trim:$level" }
        }
        for (local in listOf(true, false)) {
            val enabled = if (local) setOf(Context.OVERRIDABLE_COMPONENT_CALLBACKS) else emptySet<Long>()
            val disabled = if (local) emptySet<Long>() else setOf(Context.OVERRIDABLE_COMPONENT_CALLBACKS)
            Compatibility.setOverrides(Compatibility.ChangeConfig(enabled, disabled))
            val activity = Activity()
            val lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
                override fun onActivityConfigurationChanged(changed: Activity) {
                    if (changed === activity) events += "lifecycle"
                }
            }
            try {
                // Modern Activity owns callbacks even before its base context is attached.
                if (local) activity.registerComponentCallbacks(callback)
                activity.attach(base)
                if (!local) activity.registerComponentCallbacks(callback)
                application.registerActivityLifecycleCallbacks(lifecycle)
                events.clear()
                val config = Configuration(base.resources.configuration).apply { fontScale = 1.75f }
                activity.onConfigurationChanged(config)
                activity.onLowMemory()
                activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                check(events == if (local) listOf("lifecycle", "config:1.75", "low", "trim:20")
                    else listOf("lifecycle")) { "local=$local $events" }
                events.clear()
                application.onConfigurationChanged(config)
                application.onLowMemory()
                application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                check(events == if (local) emptyList<String>() else listOf("config:1.75", "low", "trim:20")) { events }
                activity.unregisterComponentCallbacks(callback)
                events.clear()
                activity.onConfigurationChanged(config)
                activity.onLowMemory()
                application.onLowMemory()
                check(events == listOf("lifecycle")) { events }
            } finally {
                activity.unregisterComponentCallbacks(callback)
                application.unregisterActivityLifecycleCallbacks(lifecycle)
                activity.window?.destroy()
                Compatibility.clearOverrides()
            }
        }
        System.err.println("stock-activity-callbacks: activity/application ownership, lifecycle ordering, memory callbacks and unregister passed")
    }
}
