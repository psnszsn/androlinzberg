package dev.artzig

import android.app.Activity
import android.app.ActivityClient
import android.app.ActivityThread
import android.app.IApplicationThread
import android.app.servertransaction.ActivityRelaunchItem
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.TransactionExecutorHelper
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.MergedConfiguration
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.WindowManagerGlobal
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Desktop process boundary around the unchanged ActivityThread.
 * AOSP main() prepares and attaches the thread then owns Looper.loop(). The host already
 * owns that loop, so bootstrap invokes the same constructor/attach entry points. Window
 * presentation observes stock WindowManagerGlobal registrations; lifecycle stays in stock
 * ClientTransaction execution. Reflection is confined to bootstrap, process teardown and the task configuration boundary. */
object HostedActivityProcess {
    interface HostAdapter {
        fun showActivity(activity: Activity)
        fun clearActivityWindows()
        fun resizeActivityRoot(activity: Activity, width: Int, height: Int)
    }

    private var adapter: HostAdapter? = null
    private var presented: Activity? = null
    private var observing = false
    private var refreshPending = false
    // Presentation starts the host frame that removes ViewRootImpl's traversal barrier.
    // Like DisplayEventReceiver, it must be able to run while that barrier is present.
    private val handler by lazy { Handler.createAsync(Looper.getMainLooper()) }
    private val listener = Consumer<List<View>> { schedulePresentationUpdate() }

    @JvmStatic
    fun attachApplication(): ActivityThread {
        check(ActivityThread.currentActivityThread() == null)
        val constructor = ActivityThread::class.java.getDeclaredConstructor().apply { isAccessible = true }
        val thread = constructor.newInstance()
        val attach = ActivityThread::class.java.getDeclaredMethod("attach",
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val startSequence = System.getenv("AZK_APP_START_SEQUENCE")?.toLong() ?: 0L
        require(startSequence >= 0)
        try { attach.invoke(thread, false, startSequence) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        initializeMainHandler(thread)
        return thread
    }

    @JvmStatic
    fun configureActivityHost(value: HostAdapter) {
        adapter = value
        presented = null
        if (!observing) {
            observing = true
            WindowManagerGlobal.getInstance().addWindowViewsListener(Executor { it.run() }, listener)
        }
        schedulePresentationUpdate()
    }

    /** Called for window membership and stop/resume changes. The posted refresh runs after
     * handleResumeActivity has made its newly registered (initially invisible) decor visible. */
    @JvmStatic
    fun schedulePresentationUpdate() {
        if (adapter == null || refreshPending) return
        refreshPending = true
        handler.post {
            refreshPending = false
            refreshPresentation()
        }
    }

    private fun refreshPresentation() {
        val host = adapter ?: return
        val thread = ActivityThread.currentActivityThread() ?: return
        val activity = WindowManagerGlobal.getInstance().windowViews.asReversed().firstNotNullOfOrNull { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            val root = view.viewRootImpl
            if (params?.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION ||
                root == null || root.`isWindowStopped$artzig`() || view.visibility != View.VISIBLE) null
            else thread.getActivity(params.token)
        }
        if (activity === presented) return
        presented = activity
        if (activity == null) host.clearActivityWindows() else host.showActivity(activity)
    }

    @JvmStatic fun currentActivity(): Activity? { refreshPresentation(); return presented }

    /** Task-side configuration decision, using ActivityInfo's stock handled mask and
     * stock configuration/relaunch transactions rather than reproducing lifecycle callbacks. */
    @JvmStatic
    fun applyConfigurationInProcess(config: Configuration, changes: Int, width: Int, height: Int): Boolean {
        val thread = ActivityThread.currentActivityThread()
        val record = currentActivity()?.activityToken?.let(thread::getActivityClient)
        val previous = Configuration(thread.configuration)
        val activityChanges = if (record == null) changes else android.window.SizeConfigurationBuckets.filterDiff(
            changes, previous, config, sizeConfigurations(record),
        )
        thread.handleConfigurationChanged(config, Context.DEVICE_ID_DEFAULT)
        if (record == null) return false
        if (activityChanges and checkNotNull(thread.getActivity(record.token)).activityInfo.realConfigChanged.inv() == 0) {
            adapter?.resizeActivityRoot(checkNotNull(thread.getActivity(record.token)), width, height)
            if (changes != 0) thread.handleActivityConfigurationChanged(record,
                activityOverride(record), Display.INVALID_DISPLAY, record.activityWindowInfo)
            return false
        }
        val appThread: IApplicationThread = thread.applicationThread
        val transaction = ClientTransaction(appThread)
        ActivityClient.getInstance().activityLocalRelaunch(record.token)
        transaction.addTransactionItem(ActivityRelaunchItem(record.token, null, null, activityChanges,
            MergedConfiguration(config, activityOverride(record)), false, record.activityWindowInfo))
        transaction.addTransactionItem(TransactionExecutorHelper.getLifecycleRequestForCurrentState(record))
        thread.executeTransaction(transaction)
        refreshPresentation()
        return true
    }

    // The client reports these same resource buckets to ATMS. Local compositor updates
    // use them at the task boundary before deciding whether to request a stock relaunch.
    private fun sizeConfigurations(record: ActivityThread.ActivityClientRecord): android.window.SizeConfigurationBuckets? {
        val field = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("mSizeConfigurations")
            .apply { isAccessible = true }
        return field.get(record) as? android.window.SizeConfigurationBuckets
    }

    // ATMS normally supplies this override from its ActivityRecord. The hosted compositor
    // changes the process display in place, so preserve the stock client's latest override.
    private fun activityOverride(record: ActivityThread.ActivityClientRecord): Configuration {
        val field = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("overrideConfig")
            .apply { isAccessible = true }
        return (field.get(record) as? Configuration)?.let(::Configuration) ?: Configuration()
    }

    /** AOSP main initializes this handler after attach; the host owns the outer Looper. */
    @JvmStatic fun initializeMainHandler(thread: ActivityThread) {
        val field = ActivityThread::class.java.getDeclaredField("sMainThreadHandler").apply { isAccessible = true }
        if (field.get(null) == null) field.set(null, thread.handler)
    }

    @JvmStatic
    fun shutdownActivitiesInProcess() {
        val thread = ActivityThread.currentActivityThread() ?: return
        val field = ActivityThread::class.java.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val records = field.get(thread) as Map<IBinder, ActivityThread.ActivityClientRecord>
        for (record in records.values.toList()) {
            if (thread.getActivityClient(record.token) === record)
                thread.handleDestroyActivity(record, true, false, "host window closed")
        }
        refreshPresentation()
        adapter = null
    }

    @JvmStatic
    fun shutdownServices() {
        val thread = ActivityThread.currentActivityThread() ?: return
        val field = ActivityThread::class.java.getDeclaredField("mServices").apply { isAccessible = true }
        val services = field.get(thread) as Map<*, *>
        val appThread: IApplicationThread = thread.applicationThread
        for (token in services.keys.toList()) appThread.scheduleStopService(token as IBinder)
    }

    /** onTerminate is a host process-exit notification, never an Android lifecycle patch. */
    @JvmStatic fun shutdownApplication() { ActivityThread.currentApplication()?.onTerminate() }
}
