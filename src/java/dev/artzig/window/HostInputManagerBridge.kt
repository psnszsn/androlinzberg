@file:Suppress("DEPRECATION")

package dev.artzig.window

import android.Manifest
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.SparseArray
import android.view.IInputFilter
import android.view.IInputFilterHost
import android.view.InputApplicationHandle
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceControl
import android.view.WindowManagerPolicyConstants
import java.util.OptionalInt

/**
 * WMS-facing input service for a compositor-owned seat.
 *
 * Source: Android 16 InputManagerService's WindowManagerCallbacks, setInputFilter,
 * InputFilterHost and dispatchUnhandledKey, and NativeInputManager's interception
 * policy. The full service starts evdev, sysfs, Bluetooth and hardware controllers
 * that are absent here. WMS retains its callbacks; stock InputDispatcher retains
 * routing. Desktop operations and device state are supplied by the seat owner.
 */
class HostInputManagerBridge(private val context: Context, private val desktop: Desktop) : AutoCloseable {
    interface Desktop {
        fun getInputDevices(): Array<InputDevice>
        fun canDispatchToDisplay(deviceId: Int, displayId: Int): Boolean
        fun getKeyCodeState(deviceId: Int, sourceMask: Int, keyCode: Int): Int
        fun getScanCodeState(deviceId: Int, sourceMask: Int, scanCode: Int): Int
        fun getSwitchState(deviceId: Int, sourceMask: Int, switchCode: Int): Int
        fun isDisplayInteractive(displayId: Int): Boolean
        fun onDisplayRemoved(displayId: Int)
        fun setSystemUiLightsOut(lightsOut: Boolean)
        fun notifyFocusedDisplayChanged(displayId: Int)
        fun pokeUserActivity(eventTimeNanos: Long, eventType: Int, displayId: Int)
        fun setPointerCapture(windowToken: IBinder?, sequence: Int)
        fun notifyDeviceInteraction(deviceId: Int, timestampNanos: Long, uids: IntArray)
        fun notifyUnsupportedHardwareEvent(kind: String)
    }

    /** Exact operations consumed by the stock WM InputManagerCallback. */
    interface WindowManagerCallbacks {
        fun notifyConfigurationChanged()
        fun notifyPointerLocationChanged(pointerLocationEnabled: Boolean)
        fun notifyLidSwitchChanged(whenNanos: Long, lidOpen: Boolean)
        fun notifyCameraLensCoverSwitchChanged(whenNanos: Long, lensCovered: Boolean)
        fun notifyInputChannelBroken(token: IBinder)
        fun notifyNoFocusedWindowAnr(applicationHandle: InputApplicationHandle)
        fun notifyWindowUnresponsive(token: IBinder, pid: OptionalInt, reason: String)
        fun notifyWindowResponsive(token: IBinder, pid: OptionalInt)
        fun interceptKeyBeforeQueueing(event: KeyEvent, policyFlags: Int): Int
        fun interceptMotionBeforeQueueingNonInteractive(displayId: Int, source: Int,
                                                        action: Int, whenNanos: Long, policyFlags: Int): Int
        fun interceptKeyBeforeDispatching(token: IBinder?, event: KeyEvent, policyFlags: Int): Long
        fun interceptUnhandledKey(event: KeyEvent, token: IBinder?): Boolean
        fun getPointerLayer(): Int
        fun getPointerDisplayId(): Int
        fun onPointerDownOutsideFocus(touchedToken: IBinder)
        fun notifyFocusChanged(oldToken: IBinder?, newToken: IBinder?)
        fun notifyDropWindow(token: IBinder, x: Float, y: Float)
        fun getParentSurfaceForPointers(displayId: Int): SurfaceControl?
        fun createSurfaceForGestureMonitor(name: String, displayId: Int): SurfaceControl?
        fun isKeyguardLocked(displayId: Int): Boolean
    }

    @Volatile private var windowCallbacks: WindowManagerCallbacks? = null
    private val wm: WindowManagerCallbacks
        get() = checkNotNull(windowCallbacks) { "WMS input callbacks must be installed before dispatch" }
    private val filterLock = Any()
    private var filter: IInputFilter? = null
    private var filterHost: FilterHost? = null
    private var closed = false
    // Called only by InputDispatcher; released after stopping its thread.
    private val fallbackActions = SparseArray<KeyCharacterMap.FallbackAction>()
    private val dispatcher = HostInputDispatcher(Policy())

    fun setWindowManagerCallbacks(callbacks: WindowManagerCallbacks) {
        check(windowCallbacks == null) { "WMS input callbacks already installed" }
        windowCallbacks = callbacks
    }

    fun connectSurfaceFlinger() {
        checkNotNull(windowCallbacks) { "Install WMS callbacks before publishing inputflinger" }
        dispatcher.connectSurfaceFlinger()
    }

    fun createInputChannel(name: String): InputChannel = dispatcher.createInputChannel(name)
    fun removeInputChannel(token: IBinder) = dispatcher.removeInputChannel(token)
    fun monitorInput(name: String, displayId: Int): InputChannel =
        dispatcher.createInputMonitor(name, displayId, Binder.getCallingPid())
    fun getInputDevices(): Array<InputDevice> = desktop.getInputDevices()
    fun canDispatchToDisplay(deviceId: Int, displayId: Int): Boolean = desktop.canDispatchToDisplay(deviceId, displayId)
    fun getKeyCodeState(deviceId: Int, sourceMask: Int, keyCode: Int): Int = desktop.getKeyCodeState(deviceId, sourceMask, keyCode)
    fun getScanCodeState(deviceId: Int, sourceMask: Int, scanCode: Int): Int = desktop.getScanCodeState(deviceId, sourceMask, scanCode)
    fun getSwitchState(deviceId: Int, sourceMask: Int, switchCode: Int): Int = desktop.getSwitchState(deviceId, sourceMask, switchCode)
    fun setFocusedApplication(displayId: Int, application: InputApplicationHandle?) = dispatcher.setFocusedApplication(displayId, application)
    fun setFocusedDisplay(displayId: Int) = dispatcher.setFocusedDisplay(displayId)
    fun setInputDispatchMode(enabled: Boolean, frozen: Boolean) = dispatcher.setInputDispatchMode(enabled, frozen)
    fun setInTouchMode(inTouchMode: Boolean, pid: Int, uid: Int, hasPermission: Boolean, displayId: Int): Boolean =
        dispatcher.setInTouchMode(inTouchMode, pid, uid, hasPermission, displayId)
    fun transferTouch(to: IBinder, displayId: Int): Boolean = dispatcher.transferTouchOnDisplay(to, displayId)
    fun transferTouchGesture(from: IBinder, to: IBinder, entireGesture: Boolean): Boolean = dispatcher.transferTouch(from, to, false, entireGesture)
    fun startDragAndDrop(from: IBinder, to: IBinder): Boolean = dispatcher.transferTouch(from, to, true, false)
    fun setSystemUiLightsOut(lightsOut: Boolean) = desktop.setSystemUiLightsOut(lightsOut)
    fun onDisplayRemoved(displayId: Int) {
        desktop.onDisplayRemoved(displayId)
        dispatcher.displayRemoved(displayId)
    }
    fun requestPointerCapture(token: IBinder, enabled: Boolean) = dispatcher.requestPointerCapture(token, enabled)
    fun notifyPointerCaptureChanged(token: IBinder?, sequence: Int) = dispatcher.notifyPointerCaptureChanged(token, sequence)
    fun setDisplayEligibilityForPointerCapture(displayId: Int, eligible: Boolean) = dispatcher.setDisplayEligibilityForPointerCapture(displayId, eligible)
    fun pilferPointers(token: IBinder) = dispatcher.pilferPointers(token)

    /** For the trusted compositor transport; Binder injection is permission checked separately. */
    fun dispatchDesktopEvent(event: InputEvent) = dispatcher.notifyInputEvent(event)

    fun setInputFilter(value: IInputFilter?) = synchronized(filterLock) {
        check(!closed) { "Input manager is closed" }
        if (filter === value) return@synchronized
        disconnectFilterLocked()
        if (value != null) {
            filter = value
            filterHost = FilterHost()
            try { value.install(filterHost) } catch (_: RemoteException) { }
        }
        dispatcher.setInputFilterEnabled(value != null)
    }

    private fun disconnectFilterLocked() {
        val old = filter ?: return
        filter = null
        filterHost!!.disconnected = true
        filterHost = null
        try { old.uninstall() } catch (_: RemoteException) { }
    }

    private inner class FilterHost : IInputFilterHost.Stub() {
        var disconnected = false // filterLock
        override fun sendInputEvent(event: InputEvent, policyFlags: Int) {
            // InputManagerService.checkCallingPermission accepts calls from its
            // own process before consulting the package permission authority.
            if (Binder.getCallingPid() != android.os.Process.myPid()) {
                context.enforceCallingPermission(Manifest.permission.INJECT_EVENTS, "sendInputEvent")
            }
            synchronized(filterLock) {
                if (!disconnected) dispatcher.injectInputEvent(event, -1, 0, 0,
                    policyFlags or WindowManagerPolicyConstants.FLAG_FILTERED)
            }
        }
    }

    private inner class Policy : HostInputDispatcher.Callbacks {
        override fun notifyNoFocusedWindowAnr(application: InputApplicationHandle) = wm.notifyNoFocusedWindowAnr(application)
        override fun notifyWindowUnresponsive(token: IBinder, pid: Int, reason: String) = wm.notifyWindowUnresponsive(token, optionalPid(pid), reason)
        override fun notifyWindowResponsive(token: IBinder, pid: Int) = wm.notifyWindowResponsive(token, optionalPid(pid))
        override fun notifyInputChannelBroken(token: IBinder) = wm.notifyInputChannelBroken(token)
        override fun notifyFocusChanged(oldToken: IBinder?, newToken: IBinder?) = wm.notifyFocusChanged(oldToken, newToken)
        override fun notifyFocusedDisplayChanged(displayId: Int) = desktop.notifyFocusedDisplayChanged(displayId)
        override fun onPointerDownOutsideFocus(token: IBinder) = wm.onPointerDownOutsideFocus(token)
        override fun notifyDropWindow(token: IBinder, x: Float, y: Float) = wm.notifyDropWindow(token, x, y)
        override fun pokeUserActivity(eventTimeNanos: Long, eventType: Int, displayId: Int) = desktop.pokeUserActivity(eventTimeNanos, eventType, displayId)
        override fun setPointerCapture(windowToken: IBinder?, sequence: Int) = desktop.setPointerCapture(windowToken, sequence)
        override fun notifyDeviceInteraction(deviceId: Int, timestampNanos: Long, uids: IntArray) = desktop.notifyDeviceInteraction(deviceId, timestampNanos, uids)
        override fun notifyUnsupportedHardwareEvent(kind: String) = desktop.notifyUnsupportedHardwareEvent(kind)
        override fun filterInputEvent(event: InputEvent, policyFlags: Int): Boolean {
            synchronized(filterLock) {
                val current = filter
                if (current != null) {
                    try { current.filterInputEvent(event, policyFlags) } catch (_: RemoteException) { }
                    return false
                }
            }
            event.recycle()
            return true
        }
        override fun interceptKeyBeforeQueueing(event: KeyEvent, policyFlags: Int): Int {
            val flags = interactiveFlags(event.displayId, policyFlags)
            val pass = if (flags and HostInputDispatcher.POLICY_FLAG_TRUSTED == 0)
                flags and WindowManagerPolicyConstants.FLAG_INTERACTIVE != 0
            else wm.interceptKeyBeforeQueueing(event, flags) and ACTION_PASS_TO_USER != 0
            return if (pass) flags or HostInputDispatcher.POLICY_FLAG_PASS_TO_USER else flags
        }
        override fun interceptMotionBeforeQueueing(displayId: Int, source: Int, action: Int,
                                                   whenNanos: Long, policyFlags: Int): Int {
            val flags = interactiveFlags(displayId, policyFlags)
            val interactive = flags and WindowManagerPolicyConstants.FLAG_INTERACTIVE != 0
            val pass = if (flags and HostInputDispatcher.POLICY_FLAG_TRUSTED == 0 ||
                flags and WindowManagerPolicyConstants.FLAG_INJECTED != 0) interactive
            else interactive || wm.interceptMotionBeforeQueueingNonInteractive(
                displayId, source, action, whenNanos, flags) and ACTION_PASS_TO_USER != 0
            return if (pass) flags or HostInputDispatcher.POLICY_FLAG_PASS_TO_USER else flags
        }
        override fun interceptKeyBeforeDispatching(token: IBinder?, event: KeyEvent, policyFlags: Int): Long =
            wm.interceptKeyBeforeDispatching(token, event, policyFlags)
        override fun dispatchUnhandledKey(token: IBinder?, event: KeyEvent, policyFlags: Int): KeyEvent? {
            if (wm.interceptUnhandledKey(event, token) || event.flags and KeyEvent.FLAG_FALLBACK != 0) return null
            val initialDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            val action = (if (initialDown) event.keyCharacterMap.getFallbackAction(event.keyCode, event.metaState)
                          else fallbackActions.get(event.keyCode)) ?: return null
            var fallback: KeyEvent? = KeyEvent.obtain(event.downTime, event.eventTime, event.action,
                action.keyCode, event.repeatCount, action.metaState, event.deviceId, event.scanCode,
                event.flags or KeyEvent.FLAG_FALLBACK, event.source, event.displayId, null)
            val actions = wm.interceptKeyBeforeQueueing(fallback!!, policyFlags)
            if (actions and ACTION_PASS_TO_USER == 0 ||
                interceptKeyBeforeDispatching(token, fallback, policyFlags) != 0L ||
                wm.interceptUnhandledKey(fallback, token)) {
                fallback.recycle()
                fallback = null
            }
            if (initialDown) fallbackActions.put(event.keyCode, action)
            else if (event.action == KeyEvent.ACTION_UP) {
                fallbackActions.remove(event.keyCode)
                action.recycle()
            }
            return fallback
        }
    }

    private fun interactiveFlags(displayId: Int, flags: Int): Int =
        if (desktop.isDisplayInteractive(if (displayId == -1) 0 else displayId))
            flags or WindowManagerPolicyConstants.FLAG_INTERACTIVE else flags

    override fun close() {
        // Native close rejects callback-thread shutdown before changing state.
        dispatcher.close()
        synchronized(filterLock) {
            if (closed) return
            closed = true
            disconnectFilterLocked()
        }
        for (i in 0 until fallbackActions.size()) fallbackActions.valueAt(i).recycle()
        fallbackActions.clear()
    }

    companion object {
        const val KEY_STATE_UNKNOWN = -1
        const val KEY_STATE_UP = 0
        const val KEY_STATE_DOWN = 1
        const val KEY_STATE_VIRTUAL = 2
        const val BTN_MOUSE = 0x110
        const val SW_LID = 0
        const val SW_CAMERA_LENS_COVER = 9
        const val ACTION_PASS_TO_USER = 1
        private fun optionalPid(pid: Int): OptionalInt = if (pid < 0) OptionalInt.empty() else OptionalInt.of(pid)
    }
}
