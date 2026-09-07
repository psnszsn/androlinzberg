package dev.artzig.system

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManagerInternal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.view.Display
import android.view.InputDevice
import com.android.server.LocalServices
import com.android.server.SystemServiceManager
import com.android.server.am.PendingIntentController
import com.android.server.firewall.IntentFirewall
import com.android.server.policy.WindowManagerPolicy
import com.android.server.wm.ActivityTaskManagerService
import com.android.server.wm.WindowManagerInternal
import com.android.server.wm.WindowManagerService
import dev.artzig.window.HostInputManagerBridge
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
    private val devices = HostedInputManagerService(devicesChanged = { input.notifyInputDevicesChanged() }, captureRequest = { token, enabled ->
        input.requestPointerCapture(token, enabled)
    }, pointerIconRequest = { icon, displayId, deviceId, pointerId, token ->
        setPointerIcon(icon, displayId, deviceId, pointerId, token)
    })
    private lateinit var tasks: ActivityTaskManagerService
    private lateinit var windows: WindowManagerService
    private lateinit var input: HostInputManagerBridge
    @Volatile private var shell: dev.artzig.window.SessionTaskShell.Controller? = null

    fun testInputIdle(sequence: Long): Boolean = shell?.testInputQueued(sequence) == true && input.waitForIdle()

    fun attachContext(value: Context) { context.bind(value) }

    fun startTasks(manager: SystemServiceManager) {
        tasks = manager.startService(ActivityTaskManagerService.Lifecycle::class.java).service
    }

    fun setUsageStats(usage: android.app.usage.UsageStatsManagerInternal) { tasks.setUsageStatsManager(usage) }

    fun startWindows(manager: SystemServiceManager, activities: HostedActivityManagerService) {
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
        activities.attachSystemWindowProcess(tasks)
        manager.startService(com.android.server.devicestate.DeviceStateManagerService::class.java)
        input = HostInputManagerBridge(context, BootstrapSeat())
        val policy = Class.forName("com.android.server.policy.HostedDesktopWindowManagerPolicy")
            .getConstructor(HostedDesktopWindowPolicy::class.java)
            .newInstance(HostedDesktopWindowPolicy()) as WindowManagerPolicy
        windows = checkNotNull(WindowManagerService.main(context, input, false, policy, tasks)) {
            "WMS construction failed on the display thread; see its preceding exception"
        }
        ServiceManager.addService(Context.WINDOW_SERVICE, windows)
        input.setWindowManagerCallbacks(windows.inputManagerCallback)
        display.attachWindowManager(checkNotNull(LocalServices.getService(WindowManagerInternal::class.java)))
        tasks.setWindowManager(windows)
        windows.onInitReady()
        input.connectSurfaceFlinger()
        tasks.installSystemProviders()
        windows.displayReady()
    }

    fun systemReady() {
        windows.systemReady()
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
            check(android.view.HostedRootFrames.stockClients) { "Task windows require stock window clients" }
            shell = dev.artzig.window.SessionTaskShell.start(handler, input, devices::publishKeyboardMap, devices::publishSeatCapabilities) { token ->
                com.android.server.wm.HostedTaskInputTargets.taskId(windows, token)
            }
        }
    }

    fun enableScreenAfterBoot(booted: Boolean) {
        // ATMS.LocalService also updates event dispatch under its WM lock.
        // Calling WMS.enableScreenAfterBoot alone leaves dispatch disabled.
        checkNotNull(LocalServices.getService(com.android.server.wm.ActivityTaskManagerInternal::class.java))
            .enableScreenAfterBoot(booted)
    }

    private fun setPointerIcon(icon: android.view.PointerIcon, displayId: Int, deviceId: Int,
        pointerId: Int, token: IBinder?): Boolean {
        if (token == null || displayId != Display.DEFAULT_DISPLAY || deviceId != 0 || pointerId != 0 ||
            !input.isPointerInWindow(token, displayId, deviceId, pointerId)) return false
        val work = java.util.concurrent.FutureTask(java.util.concurrent.Callable {
            // Revalidate after crossing to the shell thread; hover may have left
            // this input window while the Binder request was queued.
            if (!input.isPointerInWindow(token, displayId, deviceId, pointerId)) false
            else shell?.pointerIcon(token, icon.type) == true
        })
        if (Looper.myLooper() == handler.looper) work.run() else check(handler.post(work))
        try { return work.get(3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (failure: java.util.concurrent.TimeoutException) {
            work.cancel(false); handler.removeCallbacks(work)
            return false
        } catch (failure: InterruptedException) {
            work.cancel(false); handler.removeCallbacks(work)
            Thread.currentThread().interrupt()
            return false
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw IllegalStateException("Wayland cursor request failed", failure.cause ?: failure)
        }
    }

    fun close() { shell?.close(); shell = null }
    fun taskForObservedWindow(token: android.os.IBinder, uid: Int, pid: Int): Int? =
        com.android.server.wm.HostedTaskInputTargets.windowTask(windows, token, uid, pid)

    fun verifyStartup() {
        val diagnostics = org.json.JSONObject(dev.artzig.ArtzigTestDiagnostics.snapshot(Long.MAX_VALUE, 0L))
        val issues = diagnostics.getJSONArray("issues")
        check(issues.length() == 0) { "Stock window service startup reported runtime issues: $issues" }
    }

    /**
     * The initial Android virtual keyboard. Wayland seat capabilities and events
     * will replace this bootstrap source when the session presenter connects.
     * No pointer capture is acknowledged before that connection exists.
     */
    private inner class BootstrapSeat : HostInputManagerBridge.Desktop {
        override fun getInputDevices(): Array<InputDevice> =
            devices.inputDeviceIds.map { checkNotNull(devices.getInputDevice(it)) }.toTypedArray()
        override fun canDispatchToDisplay(deviceId: Int, displayId: Int): Boolean =
            displayId == Display.DEFAULT_DISPLAY && devices.getInputDevice(deviceId) != null
        override fun getKeyCodeState(deviceId: Int, sourceMask: Int, keyCode: Int): Int = HostInputManagerBridge.KEY_STATE_UNKNOWN
        override fun getScanCodeState(deviceId: Int, sourceMask: Int, scanCode: Int): Int = HostInputManagerBridge.KEY_STATE_UNKNOWN
        override fun getSwitchState(deviceId: Int, sourceMask: Int, switchCode: Int): Int = HostInputManagerBridge.KEY_STATE_UNKNOWN
        override fun isDisplayInteractive(displayId: Int): Boolean =
            display.getDisplayInfo(displayId)?.state == Display.STATE_ON
        override fun onDisplayRemoved(displayId: Int) { require(displayId != Display.DEFAULT_DISPLAY) }
        override fun setSystemUiLightsOut(lightsOut: Boolean) { /* Virtual keyboard has no lights. */ }
        override fun notifyFocusedDisplayChanged(displayId: Int) { require(displayId == Display.DEFAULT_DISPLAY) }
        override fun pokeUserActivity(eventTimeNanos: Long, eventType: Int, displayId: Int) {
            context.getSystemService(android.os.PowerManager::class.java)
                .userActivity(eventTimeNanos / 1_000_000, eventType, 0)
        }
        override fun setPointerCapture(windowToken: IBinder?, sequence: Int) {
            // Native policy callbacks run outside the dispatcher lock. Post to
            // the shell handler before taking WMS's lock or calling Wayland.
            check(handler.post {
                val current = shell
                if (current != null) current.pointerCapture(windowToken, sequence)
                else input.notifyPointerCaptureChanged(null, sequence)
            })
        }
        override fun notifyDeviceInteraction(deviceId: Int, timestampNanos: Long, uids: IntArray) { }
        override fun notifyUnsupportedHardwareEvent(kind: String) {
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("WaylandSeat", kind, "No Android hardware input source")
        }
    }

    companion object {
        fun prepare(): StockWindowServices = StockWindowServices().also { services ->
            ServiceManager.addService(Context.DISPLAY_SERVICE, services.display)
            val local = Class.forName("android.hardware.display.HostedDisplayManagerInternal")
                .getConstructor(dev.artzig.window.HostedSessionDisplayService::class.java)
                .newInstance(services.display) as DisplayManagerInternal
            LocalServices.addService(DisplayManagerInternal::class.java, local)
            ServiceManager.addService(Context.INPUT_SERVICE, services.devices)
        }
    }
}
