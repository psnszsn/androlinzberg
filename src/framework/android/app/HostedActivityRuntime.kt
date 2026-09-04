package android.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import java.util.ArrayDeque

/**
 * In-process activity lifecycle used when AMS and system_server are absent.
 *
 * The ordering follows the vendored AOSP ActivityThread launch, pause, stop,
 * destroy, relaunch, and activity-configuration paths. The complete AOSP
 * ActivityThread cannot run here because its Binder transaction scheduler,
 * ActivityTaskManager, WindowManagerService, and process records are absent.
 */
object HostedActivityRuntime {
    private const val TAG = "HostedActivityRuntime"

    private var host: ActivityThread.HostAdapter? = null
    private var activities: Map<String, ActivityInfo> = emptyMap()
    private val backStack = ArrayDeque<Activity>()
    private var current: Activity? = null

    @JvmStatic
    fun configure(
        adapter: ActivityThread.HostAdapter?,
        activityInfos: Map<String, ActivityInfo>?,
    ) {
        check(current == null) { "cannot replace the activity host while an activity is running" }
        host = adapter
        activities = activityInfos
            ?.mapValues { ActivityInfo(it.value) }
            .orEmpty()
        backStack.clear()
    }

    @JvmStatic
    fun isConfigured(): Boolean = host != null

    @JvmStatic
    fun currentActivity(): Activity? = current

    @JvmStatic
    fun manifestOrientation(activityClassName: String?): Int =
        activityClassName
            ?.let(activities::get)
            ?.screenOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @JvmStatic
    fun launchMain(activityClassName: String): Activity =
        launch(activityClassName, Intent().setComponent(component(activityClassName)), null, false)

    @JvmStatic
    fun startActivity(intent: Intent) = startActivity(null, null, intent, -1)

    @JvmStatic
    fun startActivity(caller: Activity?, resultWho: String?, intent: Intent, requestCode: Int) {
        // AOSP Instrumentation.execStartActivity() hands implicit intents to ActivityTaskManager,
        // which resolves them through PackageManager and may launch the system ResolverActivity.
        // system_server and the system resolver package are absent here, so the host adapter owns
        // that resolution seam and can return a synthetic desktop-backed Activity.
        val component = resolveActivity(intent)
            ?: throw android.content.ActivityNotFoundException("No Activity found to handle $intent")
        val resolvedIntent = if (intent.component == component) intent else Intent(intent).setComponent(component)
        launch(
            component.className,
            resolvedIntent,
            null,
            true,
            resultWho = if (caller === current) resultWho else null,
            resultRequestCode = if (caller === current) requestCode else -1,
        )
    }

    @JvmStatic
    fun resolveActivity(intent: Intent): ComponentName? =
        intent.component ?: requireHost().resolveActivity(Intent(intent))

    @JvmStatic
    fun finishActivity(activity: Activity) {
        if (activity !== current) return
        val resultWho = activity.`getResultWho$artzig`()
        val resultRequestCode = activity.`getResultRequestCode$artzig`()
        val resultCode = activity.`getResultCode$artzig`()
        val resultData = activity.`getResultData$artzig`()
        destroy(activity, changingConfigurations = false)
        current = null
        val previous = backStack.pollFirst()
        if (previous != null) {
            previous.performStart()
            if (resultRequestCode >= 0) {
                previous.dispatchActivityResult(
                    resultWho,
                    resultRequestCode,
                    resultCode,
                    resultData,
                )
            }
            previous.performResume()
            current = previous
            requireHost().showActivity(previous)
            Log.i(TAG, "finished ${activity.javaClass.name}, back to ${previous.javaClass.name}")
        }
    }

    @JvmStatic
    fun recreateActivity(activity: Activity) {
        if (activity !== current) return
        val savedState = Bundle()
        activity.performSaveInstanceState(savedState)
        val className = activity.javaClass.name
        val intent = activity.intent?.let(::Intent)
        val requestedOrientation = activity.requestedOrientation
        val resultWho = activity.`getResultWho$artzig`()
        val resultRequestCode = activity.`getResultRequestCode$artzig`()
        val resultCode = activity.`getResultCode$artzig`()
        val resultData = activity.`getResultData$artzig`()?.let(::Intent)
        val retained = destroy(activity, changingConfigurations = true)
        current = null
        val replacement = launch(
            activityClassName = className,
            intent = intent,
            savedState = savedState,
            addCurrentToBackStack = false,
            requestedOrientation = requestedOrientation,
            lastNonConfigurationInstances = retained,
            resultWho = resultWho,
            resultRequestCode = resultRequestCode,
            resultCode = resultCode,
            resultData = resultData,
        )
        Log.i(TAG, "recreated ${replacement.javaClass.name} for configuration change")
    }

    /**
     * Applies an already-calculated process configuration. The package manager
     * side decides which changes an Activity declared as handled; this module
     * owns the resulting callback or relaunch lifecycle.
     */
    @JvmStatic
    fun applyConfiguration(
        configuration: Configuration,
        changes: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val activity = current ?: return false
        val handled = activities[activity.javaClass.name]?.configChanges
            ?: activity.activityInfo.configChanges
        val unhandled = changes and handled.inv()
        if (unhandled != 0) {
            recreateActivity(activity)
            return true
        }
        requireHost().resizeActivityRoot(activity, width, height)
        if (changes != 0) {
            ActivityThread.currentApplication()?.onConfigurationChanged(configuration)
            activity.onConfigurationChanged(configuration)
        }
        return false
    }

    @JvmStatic
    fun shutdown() {
        current?.let { destroy(it, changingConfigurations = false) }
        current = null
        while (backStack.isNotEmpty()) {
            destroy(backStack.removeFirst(), changingConfigurations = false)
        }
        backStack.clear()
        host = null
        activities = emptyMap()
    }

    @JvmStatic
    fun shouldRelaunch(handledChanges: Int, changes: Int): Boolean =
        changes and handledChanges.inv() != 0

    private fun launch(
        activityClassName: String,
        intent: Intent?,
        savedState: Bundle?,
        addCurrentToBackStack: Boolean,
        requestedOrientation: Int? = null,
        lastNonConfigurationInstances: Activity.NonConfigurationInstances? = null,
        resultWho: String? = null,
        resultRequestCode: Int = -1,
        resultCode: Int = Activity.RESULT_CANCELED,
        resultData: Intent? = null,
    ): Activity {
        val adapter = requireHost()
        // AOSP ActivityThread.performLaunchActivity: the intent's extras and the
        // saved state resolve their classes against the app's loader.
        intent?.setExtrasClassLoader(adapter.activityClassLoader)
        savedState?.setClassLoader(adapter.activityClassLoader)
        val previous = current
        if (addCurrentToBackStack) previous?.performPause()

        try {
            val requestedInfo = activities[activityClassName]
            val implementationClassName = requestedInfo?.targetActivity ?: activityClassName
            val activityClass = Class.forName(
                implementationClassName,
                true,
                adapter.activityClassLoader,
            )
            val activity = activityClass.getDeclaredConstructor().newInstance() as Activity
            val info = requestedInfo ?: ActivityInfo().apply {
                name = implementationClassName
                packageName = adapter.activityContext.packageName
                applicationInfo = adapter.activityContext.applicationInfo
            }
            // AOSP performLaunchActivity: every Activity gets its own base context
            // (ContextImpl.createActivityContext) over the package's LoadedApk.
            activity.attach(ActivityThread.currentActivityThread().createActivityContext(info), info)
            activity.`setLastNonConfigurationInstances$artzig`(lastNonConfigurationInstances)
            activity.`setResultTarget$artzig`(resultWho, resultRequestCode)
            activity.setResult(resultCode, resultData)
            // AOSP ActivityRecord owns requested orientation across an ActivityThread relaunch.
            // system_server is absent, so the in-process lifecycle seam carries that state.
            activity.setRequestedOrientation(requestedOrientation ?: info.screenOrientation)
            if (intent != null) activity.intent = intent
            // AOSP ActivityThread.performLaunchActivity() applies the resolved ActivityInfo theme
            // after Activity.attach() and before onCreate(). The complete ActivityThread cannot run
            // without system_server, so preserve that ordering in the in-process lifecycle seam.
            val theme = info.getThemeResource()
            if (theme != 0) activity.setTheme(theme)
            activity.performCreate(savedState)
            activity.performStart()
            if (savedState != null) activity.performRestoreInstanceState(savedState)
            activity.performResume()

            if (addCurrentToBackStack && previous != null) {
                previous.performStop()
                backStack.addFirst(previous)
            }
            current = activity
            adapter.showActivity(activity)
            Log.i(TAG, "started $activityClassName as $implementationClassName")
            return activity
        } catch (throwable: Throwable) {
            if (addCurrentToBackStack) previous?.performResume()
            throw RuntimeException("Unable to start activity $activityClassName", throwable)
        }
    }

    private fun destroy(
        activity: Activity,
        changingConfigurations: Boolean,
    ): Activity.NonConfigurationInstances? {
        activity.`setChangingConfigurations$artzig`(changingConfigurations)
        activity.performPause()
        activity.performStop()
        val retained = if (changingConfigurations) activity.retainNonConfigurationInstances() else null
        activity.performDestroy()
        activity.`setChangingConfigurations$artzig`(false)
        requireHost().clearActivityWindows()
        return retained
    }

    private fun component(activityClassName: String): ComponentName {
        val info = activities[activityClassName]
        return ComponentName(info?.packageName ?: "", activityClassName)
    }

    private fun requireHost(): ActivityThread.HostAdapter =
        checkNotNull(host) { "activity host is not configured" }
}
