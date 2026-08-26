package dev.artzig

import android.app.Activity
import android.app.ActivityThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.HostedPackageParser
import android.content.res.Configuration
import android.content.res.HostedConfigurationController
import android.os.Bundle

/** Behavioral smoke tests whose surface is the hosted framework modules. */
internal object FrameworkModuleSmoke {
    private val events = mutableListOf<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        testPackageNames()
        testConfigurationCalculation()
        testActivityLifecycle()
        println("hosted framework module smoke: PASS")
    }

    private fun testPackageNames() {
        check(HostedPackageParser.buildClassName("org.example", ".MainActivity") ==
            "org.example.MainActivity")
        check(HostedPackageParser.buildClassName("org.example", "MainActivity") ==
            "org.example.MainActivity")
        check(HostedPackageParser.buildClassName("org.example", "other.MainActivity") ==
            "other.MainActivity")
    }

    private fun testConfigurationCalculation() {
        val base = Configuration().apply { setToDefaults() }
        val phone = HostedConfigurationController.calculate(
            base,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            420,
            800,
            120,
        )
        check(!phone.landscape)
        check(phone.configuration.screenWidthDp == 420)
        check(phone.configuration.screenHeightDp == 800)

        val wide = HostedConfigurationController.calculate(
            phone.configuration,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            1000,
            700,
            120,
        )
        check(wide.landscape)
        check(wide.changes and ActivityInfo.CONFIG_ORIENTATION != 0)
        check(wide.changes and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        check(wide.changes and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE != 0)

        val lockedPortrait = HostedConfigurationController.calculate(
            wide.configuration,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            1000,
            700,
            120,
        )
        check(!lockedPortrait.landscape)
        check(lockedPortrait.displayWidth == 700 && lockedPortrait.displayHeight == 1000)
    }

    private fun testActivityLifecycle() {
        events.clear()
        val firstName = FirstActivity::class.java.name
        val secondName = SecondActivity::class.java.name
        val firstInfo = activityInfo(firstName).apply {
            configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
        }
        val secondInfo = activityInfo(secondName)
        val adapter = RecordingHostAdapter()

        ActivityThread.configureActivityHost(
            adapter,
            mapOf(firstName to firstInfo, secondName to secondInfo),
        )
        try {
            val first = ActivityThread.launchMainActivity(firstName)
            check(events == listOf("first:create", "first:start", "first:resume", "show:first")) {
                "main launch ordering: $events"
            }

            ActivityThread.startActivityInProcess(
                Intent().setComponent(ComponentName("org.example", secondName)),
            )
            check(events.takeLast(6) == listOf(
                "first:pause",
                "second:create",
                "second:start",
                "second:resume",
                "first:stop",
                "show:second",
            )) { "second launch ordering: $events" }

            checkNotNull(ActivityThread.currentActivity()).finish()
            check(ActivityThread.currentActivity() === first)
            check(events.takeLast(7) == listOf(
                "second:pause",
                "second:stop",
                "second:destroy",
                "clear",
                "first:start",
                "first:resume",
                "show:first",
            )) { "finish ordering: $events" }

            val handledConfiguration = Configuration().apply { setToDefaults() }
            ActivityThread.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_SCREEN_SIZE,
                600,
                900,
            )
            check(events.takeLast(2) == listOf("resize:first:600x900", "first:config")) {
                "handled configuration ordering: $events"
            }

            ActivityThread.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_ORIENTATION,
                900,
                600,
            )
            check(ActivityThread.currentActivity() !== first)
            check(events.contains("first:save"))
            check(events.takeLast(5) == listOf(
                "first:create:saved",
                "first:start",
                "first:restore",
                "first:resume",
                "show:first",
            )) { "relaunch restore ordering: $events" }
        } finally {
            ActivityThread.shutdownActivitiesInProcess()
        }
    }

    private fun activityInfo(className: String) = ActivityInfo().apply {
        name = className
        packageName = "org.example"
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private class RecordingHostAdapter : ActivityThread.HostAdapter {
        override fun getActivityContext(): Context? = null

        override fun getActivityClassLoader(): ClassLoader =
            checkNotNull(javaClass.classLoader)

        override fun showActivity(activity: Activity) {
            events += "show:${(activity as RecordingActivity).label}"
        }

        override fun clearActivityWindows() {
            events += "clear"
        }

        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) {
            events += "resize:${(activity as RecordingActivity).label}:${width}x$height"
        }
    }

    open class RecordingActivity(private val activityLabel: String) : Activity() {
        val label: String get() = activityLabel

        override fun attach(base: Context?, activityInfo: ActivityInfo?) {
            setRequestedOrientation(
                activityInfo?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            )
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            events += if (savedInstanceState?.getString("state") == "saved") {
                "$activityLabel:create:saved"
            } else {
                "$activityLabel:create"
            }
        }

        override fun onStart() { events += "$activityLabel:start" }
        override fun onResume() { events += "$activityLabel:resume" }
        override fun onPause() { events += "$activityLabel:pause" }
        override fun onStop() { events += "$activityLabel:stop" }
        override fun onDestroy() { events += "$activityLabel:destroy" }
        override fun onConfigurationChanged(newConfig: Configuration) {
            events += "$activityLabel:config"
        }

        override fun onSaveInstanceState(outState: Bundle) {
            events += "$activityLabel:save"
            outState.putString("state", "saved")
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            check(savedInstanceState.getString("state") == "saved")
            events += "$activityLabel:restore"
        }
    }

    class FirstActivity : RecordingActivity("first")
    class SecondActivity : RecordingActivity("second")
}
