package dev.artzig

import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.MessageQueue
import android.os.Process
import android.os.SystemClock
import android.view.InputApplicationHandle
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputWindowHandle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.WindowManager
import dev.artzig.window.HostInputManagerBridge
import android.view.IInputFilter
import android.view.IInputFilterHost
import java.util.OptionalInt
import java.util.concurrent.CopyOnWriteArrayList

/** Real SF metadata -> native dispatcher -> stock Java receiver. Policy is a test observer. */
object HostInputDispatcherSmoke {
    @JvmStatic private external fun startInputPublication()

    fun run() {
        val looper = checkNotNull(Looper.myLooper())
        val observed = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()
        lateinit var dispatcher: HostInputManagerBridge
        val callbacks = object : HostInputManagerBridge.WindowManagerCallbacks {
            override fun notifyNoFocusedWindowAnr(application: InputApplicationHandle) {
                if (application.name == "AwaitingWindow" && application.dispatchingTimeoutMillis == 150L)
                    observed.add("anr:no-window")
                else unexpected.add("no focus ANR: ${application.name}")
            }
            override fun notifyWindowUnresponsive(token: IBinder, pid: OptionalInt, reason: String) { unexpected.add("ANR $pid $reason") }
            override fun notifyWindowResponsive(token: IBinder, pid: OptionalInt) { unexpected.add("responsive $pid") }
            override fun notifyInputChannelBroken(token: IBinder) { observed.add("broken") }
            override fun notifyFocusChanged(oldToken: IBinder?, newToken: IBinder?) { observed.add("policy:focus") }
            override fun interceptKeyBeforeQueueing(event: KeyEvent, policyFlags: Int): Int {
                observed.add("queue:${event.action}")
                if (event.action == KeyEvent.ACTION_DOWN) {
                    try { dispatcher.close(); unexpected.add("Reentrant dispatcher close succeeded") }
                    catch (_: IllegalStateException) { observed.add("close:reentrant-rejected") }
                }
                return HostInputManagerBridge.ACTION_PASS_TO_USER
            }
            override fun interceptMotionBeforeQueueingNonInteractive(displayId: Int, source: Int, action: Int, whenNanos: Long, policyFlags: Int) =
                HostInputManagerBridge.ACTION_PASS_TO_USER
            override fun interceptKeyBeforeDispatching(token: IBinder?, event: KeyEvent, policyFlags: Int): Long {
                observed.add("dispatch:${event.action}")
                return 0
            }
            override fun interceptUnhandledKey(event: KeyEvent, token: IBinder?): Boolean = false
            override fun onPointerDownOutsideFocus(token: IBinder) { observed.add("outside") }
            override fun notifyDropWindow(token: IBinder, x: Float, y: Float) { unexpected.add("drop") }
            override fun notifyConfigurationChanged() { observed.add("configuration") }
            override fun notifyPointerLocationChanged(pointerLocationEnabled: Boolean) { observed.add("pointer-location") }
            override fun notifyLidSwitchChanged(whenNanos: Long, lidOpen: Boolean) { unexpected.add("lid") }
            override fun notifyCameraLensCoverSwitchChanged(whenNanos: Long, lensCovered: Boolean) { unexpected.add("camera switch") }
            override fun getPointerLayer() = 1
            override fun getPointerDisplayId() = 0
            override fun getParentSurfaceForPointers(displayId: Int): SurfaceControl? = null
            override fun createSurfaceForGestureMonitor(name: String, displayId: Int): SurfaceControl? = null
            override fun isKeyguardLocked(displayId: Int) = false
        }
        val desktop = object : HostInputManagerBridge.Desktop {
            override fun getInputDevices() = emptyArray<InputDevice>()
            override fun canDispatchToDisplay(deviceId: Int, displayId: Int) = displayId == 0
            override fun getKeyCodeState(deviceId: Int, sourceMask: Int, keyCode: Int) = -1
            override fun getScanCodeState(deviceId: Int, sourceMask: Int, scanCode: Int) = -1
            override fun getSwitchState(deviceId: Int, sourceMask: Int, switchCode: Int) = -1
            override fun isDisplayInteractive(displayId: Int) = displayId == 0
            override fun onDisplayRemoved(displayId: Int) { observed.add("removed:$displayId") }
            override fun setSystemUiLightsOut(lightsOut: Boolean) { observed.add("lights:$lightsOut") }
            override fun notifyFocusedDisplayChanged(displayId: Int) { observed.add("display:$displayId") }
            override fun pokeUserActivity(eventTimeNanos: Long, eventType: Int, displayId: Int) { observed.add("activity") }
            override fun setPointerCapture(windowToken: IBinder?, sequence: Int) {
                dispatcher.notifyPointerCaptureChanged(windowToken, sequence)
            }
            override fun notifyDeviceInteraction(deviceId: Int, timestampNanos: Long, uids: IntArray) {
                check(uids.all { it == Process.myUid() })
            }
            override fun notifyUnsupportedHardwareEvent(kind: String) { unexpected.add(kind) }
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!condition() && SystemClock.uptimeMillis() < deadline) {
                val message = looper.queue.`pollDueMessage$artzig`()
                if (message == null) Thread.sleep(1)
                else try { message.target.dispatchMessage(message) }
                finally { MessageQueue.`recycleDispatched$artzig`(message) }
            }
            check(condition()) { "Timed out waiting for $label: $observed; errors=$unexpected" }
            check(unexpected.isEmpty()) { unexpected.toString() }
            System.err.println("HostInputManagerBridge gate: $label")
        }
        dispatcher = HostInputManagerBridge(HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES"), 1280, 720, 120, 0), desktop)
        dispatcher.setWindowManagerCallbacks(callbacks)
        dispatcher.setInputDispatchMode(true, false)
        dispatcher.setFocusedApplication(0, InputApplicationHandle(Binder(), "AwaitingWindow", 150))
        val beforeWindow = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0, 7, 30, 0, InputDevice.SOURCE_KEYBOARD)
        dispatcher.dispatchDesktopEvent(beforeWindow)
        await("application ANR metadata before a window exists") { "anr:no-window" in observed }
        val beforeWindowUp = KeyEvent.changeAction(beforeWindow, KeyEvent.ACTION_UP)
        dispatcher.dispatchDesktopEvent(beforeWindowUp)
        beforeWindowUp.recycle()
        beforeWindow.recycle()
        val channels = (0..1).map { dispatcher.createInputChannel("JavaInput$it") }
        val monitor = dispatcher.monitorInput("JavaMonitor", 0)
        val monitorReceiver = object : InputEventReceiver(monitor, looper) {
            override fun onInputEvent(event: InputEvent) {
                if (event is MotionEvent) observed.add("monitor:${event.actionMasked}")
                finishInputEvent(event, true)
            }
        }
        val application = InputApplicationHandle(Binder(), "DispatcherJni", 5000)
        val roots = (0..1).map { SurfaceControl.Builder().setName("JavaInputRoot$it").setContainerLayer().build() }
        val colors = (0..1).map { SurfaceControl.Builder().setName("JavaInputColor$it").setColorLayer().setParent(roots[it]).build() }
        val windows = (0..1).map { index ->
            InputWindowHandle(application, 0).apply {
                token = channels[index].token
                name = "JavaInput$index"
                ownerPid = Process.myPid()
                ownerUid = Process.myUid()
                layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION
                dispatchingTimeoutMillis = 5000
                frame.set(0, 0, 64, 64)
                touchableRegion.set(frame)
                scaleFactor = 1f
            }
        }
        val receivers = (0..1).map { index ->
            object : InputEventReceiver(channels[index], looper) {
                override fun onFocusEvent(hasFocus: Boolean) { observed.add("focus:$index:$hasFocus") }
                override fun onPointerCaptureEvent(pointerCaptureEnabled: Boolean) {
                    observed.add("capture:$index:$pointerCaptureEnabled")
                }
                override fun onInputEvent(event: InputEvent) {
                    when (event) {
                        is KeyEvent -> {
                            observed.add("key:$index:${event.action}:${event.keyCode}")
                            observed.add("repeat:$index:${event.keyCode}:${event.repeatCount}:${event.isCanceled}")
                        }
                        is MotionEvent -> {
                            observed.add("motion:$index:${event.actionMasked}:${event.x.toInt()}:${event.y.toInt()}")
                            observed.add("raw:$index:${event.actionMasked}:${event.rawX.toInt()}:${event.rawY.toInt()}")
                            if (event.source == InputDevice.SOURCE_MOUSE_RELATIVE)
                                observed.add("relative:$index:${event.actionMasked}:${event.x.toInt()}:${event.y.toInt()}:${event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).toInt()}:${event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).toInt()}")
                        }
                        else -> unexpected.add("event $event")
                    }
                    finishInputEvent(event, true)
                }
            }
        }
        try {
            dispatcher.connectSurfaceFlinger()
            startInputPublication()
            dispatcher.setInputDispatchMode(true, false)
            dispatcher.setFocusedDisplay(0)
            dispatcher.setFocusedApplication(0, application)
            val display = checkNotNull(com.android.server.display.DisplayControl.getPhysicalDisplayToken(
                com.android.server.display.DisplayControl.getPhysicalDisplayIds().single()))
            SurfaceControl.Transaction().use { tx ->
                tx.setDisplayLayerStack(display, 0)
                    .setDisplayProjection(display, 0, Rect(0, 0, 1280, 720), Rect(0, 0, 1280, 720))
                repeat(2) { index ->
                    tx.setPosition(roots[index], 100f + 40f * index, 100f)
                        .setWindowCrop(roots[index], 64, 64).setLayer(roots[index], index + 100)
                        .setInputWindowInfo(roots[index], windows[index]).show(roots[index])
                        .setColor(colors[index], floatArrayOf(0f, 1f, 0f))
                        .setWindowCrop(colors[index], 64, 64).show(colors[index])
                }
                tx.setFocusedWindow(checkNotNull(channels[0].token), "JavaInput0", 0).apply()
            }
            await("left focus") { "focus:0:true" in observed }
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_ENTER,
                    0, 0, 7, 28, 0, InputDevice.SOURCE_KEYBOARD)
                dispatcher.dispatchDesktopEvent(event)
            }
            await("focused key delivery") { "key:0:1:66" in observed }
            check("queue:0" in observed && "dispatch:0" in observed && "close:reentrant-rejected" in observed)
            // The filter owns its input copy and may reinject asynchronously. A detached
            // host must stop injecting before uninstall is delivered to the old filter.
            lateinit var savedFilterHost: IInputFilterHost
            val filter = object : IInputFilter.Stub() {
                override fun install(host: IInputFilterHost) {
                    savedFilterHost = host
                    observed.add("filter:install")
                }
                override fun uninstall() { observed.add("filter:uninstall") }
                override fun filterInputEvent(event: InputEvent, policyFlags: Int) {
                    try {
                        check(event is KeyEvent)
                        val changed = KeyEvent(event.downTime, event.eventTime, event.action,
                            KeyEvent.KEYCODE_H, event.repeatCount, event.metaState,
                            event.deviceId, event.scanCode, event.flags, event.source)
                        savedFilterHost.sendInputEvent(changed, policyFlags)
                        changed.recycle()
                        observed.add("filter:event")
                    } finally { event.recycle() }
                }
            }
            dispatcher.setInputFilter(filter)
            dispatcher.setInputFilter(filter)
            check(observed.count { it == "filter:install" } == 1)
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_G,
                    0, 0, 7, 34, 0, InputDevice.SOURCE_KEYBOARD)
                dispatcher.dispatchDesktopEvent(event)
                event.recycle()
            }
            await("filter reinjection") { "key:0:1:${KeyEvent.KEYCODE_H}" in observed }
            check(observed.none { it == "key:0:0:${KeyEvent.KEYCODE_G}" })
            dispatcher.setInputFilter(null)
            check("filter:uninstall" in observed)
            val stale = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_J, 0, 0, 7, 36, 0, InputDevice.SOURCE_KEYBOARD)
            savedFilterHost.sendInputEvent(stale, 0)
            stale.recycle()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_K,
                    0, 0, 7, 37, 0, InputDevice.SOURCE_KEYBOARD)
                dispatcher.dispatchDesktopEvent(event)
                event.recycle()
            }
            await("delivery after filter removal") { "key:0:1:${KeyEvent.KEYCODE_K}" in observed }
            check(observed.none { it == "key:0:0:${KeyEvent.KEYCODE_J}" })
            val down = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 160f, 120f, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            down.displayId = 0
            try {
                dispatcher.dispatchDesktopEvent(down)
            } finally { down.recycle() }
            await("SF overlap and coordinate transform") { "motion:1:0:20:20" in observed }
            await("global input monitor") { "monitor:0" in observed }
            await("pointer outside focus policy") { "outside" in observed }
            val hidden = java.util.concurrent.CountDownLatch(1)
            SurfaceControl.Transaction().use {
                it.hide(roots[1]).addWindowInfosReportedListener { hidden.countDown() }.apply()
            }
            await("hidden window metadata") { hidden.count == 0L }
            // Pinned DispatcherTouchState retains a hidden window's current gesture;
            // removing its WindowInfo, rather than visibility alone, cancels it.
            val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 160f, 120f, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            up.displayId = 0
            try {
                dispatcher.dispatchDesktopEvent(up)
            } finally { up.recycle() }
            await("hidden window completes existing gesture") { "motion:1:1:20:20" in observed }
            SurfaceControl.Transaction().use {
                it.show(roots[1]).setFocusedWindow(checkNotNull(channels[1].token), "JavaInput1", 0).apply()
            }
            await("focus transfer") { "focus:0:false" in observed && "focus:1:true" in observed }
            dispatcher.setDisplayEligibilityForPointerCapture(0, true)
            dispatcher.requestPointerCapture(checkNotNull(channels[1].token), true)
            await("pointer capture acknowledgment") { "capture:1:true" in observed }
            dispatcher.requestPointerCapture(checkNotNull(channels[1].token), false)
            await("pointer capture release") { "capture:1:false" in observed }
            observed.clear()
            dispatcher.setDesktopKeyboardFocus(false)
            await("desktop keyboard leave removes native window focus") { "focus:1:false" in observed }
            val hiddenFocusReady = java.util.concurrent.CountDownLatch(1)
            SurfaceControl.Transaction().use {
                it.setFocusedWindow(checkNotNull(channels[0].token), "JavaInput0", 0)
                    .addWindowInfosReportedListener { hiddenFocusReady.countDown() }.apply()
            }
            await("Android focus updates while desktop keyboard is absent") { hiddenFocusReady.count == 0L }
            val inactiveUntil = SystemClock.uptimeMillis() + 100
            await("background Android cannot regain compositor keyboard focus") { SystemClock.uptimeMillis() >= inactiveUntil }
            check("focus:0:true" !in observed)
            dispatcher.setDesktopKeyboardFocus(true)
            await("desktop reentry restores latest SF focus request") { "focus:0:true" in observed }
            SurfaceControl.Transaction().use {
                it.setFocusedWindow(checkNotNull(channels[1].token), "JavaInput1", 0).apply()
            }
            await("restore initial task seat focus") { "focus:1:true" in observed }
            val seat = dev.artzig.window.WaylandTaskInput(object : dev.artzig.window.WaylandTaskInput.Android {
                override fun keyboardActive(active: Boolean) = dispatcher.setDesktopKeyboardFocus(active)
                override fun focusTask(taskId: Int) {
                    val index = taskId - 1
                    SurfaceControl.Transaction().use {
                        it.setFocusedWindow(checkNotNull(channels[index].token), "JavaInput$index", 0).apply()
                    }
                }
                override fun dispatch(event: InputEvent, repeatable: Boolean) = dispatcher.dispatchDesktopEvent(event, repeatable)
                override fun keyboardLayout(map: android.view.KeyCharacterMap) { }
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) =
                    dispatcher.setKeyRepeatConfiguration(timeoutMillis, delayMillis, enabled)
            })
            val leftSeat = seat.open(dev.artzig.window.TaskGeometry.Task(1, 1))
            val rightSeat = seat.open(dev.artzig.window.TaskGeometry.Task(2, 2))
            try {
                val desktopReady = java.util.concurrent.CountDownLatch(1)
                SurfaceControl.Transaction().use {
                    it.setDisplayProjection(display, 0, Rect(0, 0, 16384, 16384), Rect(0, 0, 16384, 16384))
                        .setPosition(roots[1], 12000f, 100f)
                        .addWindowInfosReportedListener { desktopReady.countDown() }.apply()
                }
                await("synthetic display input metadata") { desktopReady.count == 0L }
                leftSeat.committed(dev.artzig.window.TaskGeometry.Bounds(100, 100, 64, 64),
                    dev.artzig.window.WaylandTaskShell.Size(32, 32))
                rightSeat.committed(dev.artzig.window.TaskGeometry.Bounds(12000, 100, 64, 64),
                    dev.artzig.window.WaylandTaskShell.Size(64, 64))
                observed.clear()
                leftSeat.keyboardFocus(true)
                await("task seat keyboard focus") { "focus:0:true" in observed }
                rightSeat.pointer(dev.artzig.window.WaylandTaskInput.POINTER_ENTER, 0, false, 8f, 8f)
                await("far task hover and raw coordinates") {
                    "motion:1:9:8:8" in observed && "raw:1:9:12008:108" in observed
                }
                check("focus:0:false" !in observed)
                leftSeat.pointer(dev.artzig.window.WaylandTaskInput.POINTER_BUTTON, 0x110, true, 8f, 8f)
                leftSeat.pointer(dev.artzig.window.WaylandTaskInput.POINTER_BUTTON, 0x110, false, 8f, 8f)
                await("scaled task pointer delivery") { "motion:0:1:16:16" in observed && "raw:0:1:116:116" in observed }
                observed.clear()
                dispatcher.requestPointerCapture(checkNotNull(channels[0].token), true)
                await("task seat native capture") { "capture:0:true" in observed }
                leftSeat.captureChanged(true)
                leftSeat.relativePointer(3f, -4f)
                await("relative input keeps deltas without synthetic origin") { "relative:0:2:6:-8:6:-8" in observed }
                leftSeat.pointer(dev.artzig.window.WaylandTaskInput.POINTER_BUTTON, 0x110, true, 99f, 99f)
                await("captured mouse button") { "relative:0:11:0:0:0:0" in observed }
                leftSeat.captureChanged(false)
                await("capture loss cancels relative source") { "relative:0:3:0:0:0:0" in observed }
                dispatcher.requestPointerCapture(checkNotNull(channels[0].token), false)
                await("task seat native capture release") { "capture:0:false" in observed }
                seat.repeatConfiguration(0, 10)
                observed.clear()
                leftSeat.key(30, true)
                await("task seat key delivery") { "key:0:0:${KeyEvent.KEYCODE_A}" in observed }
                val quietUntil = SystemClock.uptimeMillis() + 100
                await("Wayland zero rate disables repeats") { SystemClock.uptimeMillis() >= quietUntil }
                check(observed.count { it == "key:0:0:${KeyEvent.KEYCODE_A}" } == 1)
                leftSeat.key(30, false)
                seat.repeatConfiguration(50, 20)
                observed.clear()
                leftSeat.key(30, true, false)
                await("non-repeating XKB key delivery") { "key:0:0:${KeyEvent.KEYCODE_A}" in observed }
                val noRepeatUntil = SystemClock.uptimeMillis() + 100
                await("per-key XKB repeat exclusion") { SystemClock.uptimeMillis() >= noRepeatUntil }
                check(observed.count { it == "key:0:0:${KeyEvent.KEYCODE_A}" } == 1)
                leftSeat.key(30, false)
                observed.clear()
                leftSeat.key(30, true)
                await("stock repeat timer and count") { "repeat:0:${KeyEvent.KEYCODE_A}:1:false" in observed }
                rightSeat.keyboardFocus(true)
                await("task seat focus cancels held keys") {
                    "repeat:0:${KeyEvent.KEYCODE_A}:0:true" in observed && "focus:1:true" in observed
                }
                rightSeat.key(48, true); rightSeat.key(48, false)
                await("new focused task key delivery") { "key:1:1:${KeyEvent.KEYCODE_B}" in observed }
            } finally {
                leftSeat.close(); rightSeat.close()
                val restored = java.util.concurrent.CountDownLatch(1)
                SurfaceControl.Transaction().use {
                    it.setDisplayProjection(display, 0, Rect(0, 0, 1280, 720), Rect(0, 0, 1280, 720))
                        .setPosition(roots[1], 140f, 100f)
                        .addWindowInfosReportedListener { restored.countDown() }.apply()
                }
                await("restored dispatcher fixture geometry") { restored.count == 0L }
            }
            val secondDown = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 120f, 120f, 0)
            secondDown.source = InputDevice.SOURCE_TOUCHSCREEN
            secondDown.displayId = 0
            try {
                dispatcher.dispatchDesktopEvent(secondDown)
            } finally { secondDown.recycle() }
            await("left touch") { "motion:0:0:20:20" in observed }
            SurfaceControl.Transaction().use { it.reparent(roots[0], null).apply() }
            await("removed window gesture cancellation") { observed.any { it.startsWith("motion:0:3:") } }
            dispatcher.onDisplayRemoved(9)
            check("removed:9" in observed)
            receivers[1].dispose()
            channels[1].dispose()
            await("channel death policy") { "broken" in observed }
            // WindowInfos publication starts with SF bootFinished/inputflinger.
            // Exercise the Java observer in this live system input session.
            dev.artzig.window.StockWindowInfosSmoke.granted()
        } finally {
            monitorReceiver.dispose()
            monitor.dispose()
            receivers.forEach { it.dispose() }
            channels.forEach { it.dispose() }
            SurfaceControl.Transaction().use { tx -> roots.forEach { tx.remove(it) }; tx.apply() }
            colors.forEach { it.release() }
            roots.forEach { it.release() }
            dispatcher.close()
            dispatcher.close()
        }
        var closedRejected = false
        try { dispatcher.createInputChannel("closed") }
        catch (_: IllegalStateException) { closedRejected = true }
        check(closedRejected) { "Closed dispatcher accepted a channel" }
        check(unexpected.isEmpty()) { unexpected.toString() }
        println("HostInputManagerBridge: WM policy, filter reinjection/disconnect, SF focus/geometry, monitor, transformed touch, cancellation, channel death and shutdown passed")
    }
}
