package dev.artzig.system

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManagerInternal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.view.Display
import com.android.server.LocalServices
import com.android.server.SystemServiceManager
import com.android.server.am.PendingIntentController
import com.android.server.firewall.IntentFirewall
import com.android.server.input.InputManagerInternal
import com.android.server.input.InputManagerService
import com.android.server.policy.WindowManagerPolicy
import com.android.server.wm.ActivityTaskManagerService
import com.android.server.wm.WindowManagerInternal
import com.android.server.wm.WindowManagerService
import dev.artzig.window.HostInputDispatcher
import dev.artzig.window.HostedDesktopWindowPolicy
import dev.artzig.window.SurfaceFlingerDisplayOutput

/**
 * SystemServer's display -> ATMS -> device state -> WMS startup ordering.
 * Hardware display discovery and zygote/AMS startup are supplied by the session.
 * This owns the stock window services only; package/process authority remains AMS.
 */
internal class StockWindowServices private constructor() {
    // ActivityThread needs display metadata to create the very first Context.
    // Display permissions use that same Context after bootstrap has bound it.
    private class BootstrapContext : ContextWrapper(null) {
        fun bind(context: Context) { check(baseContext == null); attachBaseContext(context) }
    }
    private val context = BootstrapContext()
    private val handler = Handler(Looper.getMainLooper())
    private val display = SurfaceFlingerDisplayOutput.createService(context, handler)
    private lateinit var tasks: ActivityTaskManagerService
    private lateinit var windows: WindowManagerService
    private lateinit var input: InputManagerService
    private var hostInput: HostInputDispatcher? = null
    @Volatile private var shell: dev.artzig.window.SessionTaskShell.Controller? = null

    fun attachContext(value: Context) { context.bind(value) }

    /** ActivityManagerService constructor: start and initialize ATMS (RecentTasks,
     * intent firewall, pending intents) before the package manager serves queries. */
    fun startTasks(manager: SystemServiceManager, activities: HostedActivityManagerService) {
        tasks = manager.startService(ActivityTaskManagerService.Lifecycle::class.java).service
        val firewall = IntentFirewall(object : IntentFirewall.AMSInterface {
            override fun getAMSLock(): Any = activities.windowServiceLock()
            override fun checkComponentPermission(permission: String?, pid: Int, uid: Int,
                owningUid: Int, exported: Boolean): Int =
                HostActivityManagerAccess.checkComponentPermission(permission, pid, uid, owningUid, exported)
        }, handler)
        val pending = PendingIntentController(handler.looper, { android.os.UserHandle.USER_SYSTEM },
            { 2000 }, { intent -> context.getSystemService(android.app.AlarmManager::class.java).cancel(intent) })
        tasks.initialize(firewall, pending, handler.looper)
        pending.onActivityManagerInternalAdded()
        tasks.onActivityManagerInternalAdded()
        tasks.onInitPowerManagement()
    }

    fun setUsageStats(usage: android.app.usage.UsageStatsManagerInternal) { tasks.setUsageStatsManager(usage) }

    fun startWindows(manager: SystemServiceManager, activities: HostedActivityManagerService) {
        activities.attachSystemWindowProcess(tasks)
        manager.startService(com.android.server.devicestate.DeviceStateManagerService::class.java)
        // SystemServer.startOtherServices: InputManagerService before WMS. Its
        // JNI constructs inputflinger's InputManager over the host event hub.
        input = InputManagerService(context)
        val policy = Class.forName("com.android.server.policy.HostedDesktopWindowManagerPolicy")
            .getConstructor(HostedDesktopWindowPolicy::class.java)
            .newInstance(HostedDesktopWindowPolicy { token ->
                check(handler.post { shell?.toggleFullscreen(token) })
            }) as WindowManagerPolicy
        windows = checkNotNull(WindowManagerService.main(context, input, false, policy, tasks)) {
            "WMS construction failed on the display thread; see its preceding exception"
        }
        ServiceManager.addService(Context.WINDOW_SERVICE, windows)
        ServiceManager.addService(Context.INPUT_SERVICE, input)
        // Pinned RootWindowContainer.setWindowManager creates the default
        // DisplayContent under the WM lock. The host display attachment requests
        // traversal immediately, so it must follow that initialization.
        tasks.setWindowManager(windows)
        display.attachWindowManager(checkNotNull(LocalServices.getService(WindowManagerInternal::class.java)))
        windows.onInitReady()
        input.setWindowManagerCallbacks(windows.inputManagerCallback)
        input.start()
        // DisplayManagerService.windowManagerAndInputReady: viewports for the
        // reader. The compositor has already accelerated the seat's pointer,
        // whose deltas reproduce its positions (WaylandTaskInput); virtual
        // displays disable Android mouse scaling the same way.
        val inputInternal = checkNotNull(LocalServices.getService(InputManagerInternal::class.java))
        display.attachInput(inputInternal)
        inputInternal.setMouseScalingEnabled(false, Display.DEFAULT_DISPLAY)
        // Scaling applies to a mouse associated with the display; an
        // unassociated one only follows PointerChoreographer's default display.
        // The seat pointer's input port belongs to the session display, as a
        // device's input-port association would bind it.
        val displayUniqueId = checkNotNull(display.getDisplayInfo(Display.DEFAULT_DISPLAY)) { "Session display is absent" }.uniqueId
        input.addUniqueIdAssociationByPort(dev.artzig.window.HostInputDispatcher.POINTER_PORT, displayUniqueId)
        // External tablet tools likewise need their display, as a pen display's port would name it.
        input.addUniqueIdAssociationByPort(dev.artzig.window.HostInputDispatcher.TABLET_PORT, displayUniqueId)
        tasks.installSystemProviders()
        windows.displayReady()
    }

    fun systemReady() {
        windows.systemReady()
        input.systemRunning()
        tasks.onSystemReady()
        // AMS.systemReady calls ATMS.onSystemReady before retrieveSettings;
        // the latter consumes local services initialized by the former.
        tasks.retrieveSettings(context.contentResolver)
        if (System.getenv("AZK_TASK_WINDOWS") == "1") {
            // Pinned LaunchParamsUtil.getDefaultFreeformSize chooses bounded
            // density-based initial task sizes on a freeform display. Set the
            // display policy before any application can allocate a root buffer.
            windows.setWindowingMode(Display.DEFAULT_DISPLAY,
                android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM)
        }
    }

    fun bootCompleted() {
        windows.enableScreenAfterBoot()
        if (System.getenv("AZK_TASK_WINDOWS") == "1") {
            check(dev.artzig.testing.HostedRootFrames.stockClients) { "Task windows require stock window clients" }
            com.android.server.wm.HostedNativeDrag(windows, handler)
            hostInput = HostInputDispatcher(object : HostInputDispatcher.Callbacks {
                override fun inputWindowTasks(tokens: Array<IBinder>): Array<IBinder?> =
                    com.android.server.wm.HostedTaskInputTargets.inputWindowTasks(windows, tokens)
                // The reader thread reports capture; lock the compositor's pointer on the shell thread.
                override fun pointerCapture(windowToken: IBinder?, sequence: Int) {
                    check(handler.post { shell?.pointerCapture(windowToken, sequence) })
                }
            })
            shell = dev.artzig.window.SessionTaskShell.start(handler, context,
                { token -> com.android.server.wm.HostedTaskInputTargets.taskId(windows, token) },
                { token, uid, pid -> com.android.server.wm.HostedTaskInputTargets.windowTask(windows, token, uid, pid) })
        }
    }

    fun enableScreenAfterBoot(booted: Boolean) {
        // ATMS.LocalService also updates event dispatch under its WM lock.
        // Calling WMS.enableScreenAfterBoot alone leaves dispatch disabled.
        checkNotNull(LocalServices.getService(com.android.server.wm.ActivityTaskManagerInternal::class.java))
            .enableScreenAfterBoot(booted)
    }

    fun close() {
        shell?.close(); shell = null
        hostInput?.close(); hostInput = null
    }
    fun verifyStartup() {
        val diagnostics = org.json.JSONObject(dev.artzig.ArtzigTestDiagnostics.snapshot(Long.MAX_VALUE, 0L))
        val issues = diagnostics.getJSONArray("issues")
        check(issues.length() == 0) { "Stock window service startup reported runtime issues: $issues" }
    }

    companion object {
        fun prepare(): StockWindowServices = StockWindowServices().also { services ->
            ServiceManager.addService(Context.DISPLAY_SERVICE, services.display)
            val local = Class.forName("android.hardware.display.HostedDisplayManagerInternal")
                .getConstructor(dev.artzig.window.HostedSessionDisplayService::class.java)
                .newInstance(services.display) as DisplayManagerInternal
            LocalServices.addService(DisplayManagerInternal::class.java, local)
        }
    }
}
