package dev.artzig.window

import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Binder transports a stock Surface; the session's Zig presenter owns the
 * consumer queue and all Wayland objects on its protocol thread. */
internal class RemoteTaskPresenter(private val remote: IWaylandTaskPresenter,
    private val handler: Handler, private val input: WaylandTaskInput,
    private val taskForToken: (android.os.IBinder) -> Int?,
    private val taskForEditor: (android.os.IBinder, Int, Int) -> Int?) : WaylandTaskShell.Presenter {
    private val windows = mutableMapOf<Int, Window>()
    private val outgoingDrag by lazy {
        WaylandOutgoingDrag(handler, checkNotNull(com.android.server.LocalServices.getService(
            com.android.server.wm.HostedNativeDrag::class.java)), remote, object : WaylandOutgoingDrag.Host {
            override fun origin(source: com.android.server.wm.HostedNativeDrag.Source): WaylandOutgoingDrag.Origin? {
                val window = windows[source.taskId] ?: return null
                val bounds = window.bounds ?: return null
                val kind: Int
                val id: Int
                // Stock InputReader assigns device IDs; the seat's mouse and
                // touchscreen are the only devices with these sources.
                if (source.inputSource and android.view.InputDevice.SOURCE_MOUSE == android.view.InputDevice.SOURCE_MOUSE) {
                    kind = 0; id = 0
                } else if (source.inputSource and android.view.InputDevice.SOURCE_TOUCHSCREEN == android.view.InputDevice.SOURCE_TOUCHSCREEN) {
                    kind = 1; id = window.seat.nativeTouchId(source.pointerId) ?: return null
                } else return null
                val claim = remote.claimNativeDrag(window.task.id, window.task.generation, window.size.configurationGeneration, kind, id)
                if (claim <= 0) return null
                return WaylandOutgoingDrag.Origin(window.task, window.size, bounds, kind, id, claim)
            }
            override fun current(origin: WaylandOutgoingDrag.Origin): Boolean = windows[origin.task.id]?.let {
                it.task == origin.task && it.size == origin.size && it.bounds == origin.bounds
            } == true
        })
    }
    private val nativeDrag by lazy {
        WaylandNativeDrag(handler, checkNotNull(com.android.server.LocalServices.getService(
            com.android.server.wm.HostedNativeDrag::class.java)), object : WaylandNativeDrag.Host {
            override fun source(token: Long) = outgoingDrag.source(token)
            override fun dropping(token: Long) = outgoingDrag.dropping(token)
            override fun point(task: TaskGeometry.Task, x: Float, y: Float) =
                windows[task.id]?.takeIf { it.task == task }?.seat?.surfacePoint(x, y)
            override fun accept(token: Long, accepted: Boolean) { remote.acceptNativeDrag(token, accepted) }
            override fun finish(token: Long, consumed: Boolean) { remote.finishNativeDrag(token, consumed) }
        })
    }
    val textInput = WaylandInputMethod(handler, object : WaylandInputMethod.Host {
        override fun task(window: android.os.IBinder, uid: Int, pid: Int) =
            taskForEditor(window, uid, pid)?.let { windows[it]?.task }
        override fun publish(task: TaskGeometry.Task, generation: Long, applied: Long, enabled: Boolean,
            context: WaylandTextContext.Wire?, hint: Int, purpose: Int, external: Boolean, rectangle: IntArray): Boolean {
            if (windows[task.id]?.task != task) return false
            return remote.updateTextInput(task.id, task.generation, generation, applied, enabled,
                context?.text, context?.cursor ?: 0, context?.anchor ?: 0, hint, purpose, external, rectangle)
        }
        override fun cursor(task: TaskGeometry.Task, points: FloatArray): IntArray {
            val window = windows[task.id]?.takeIf { it.task == task } ?: return intArrayOf()
            val bounds = window.bounds ?: return intArrayOf()
            // CursorAnchorInfo's matrix maps to Android's synthetic display.
            // The compositor expects the task surface's logical coordinates.
            val x = (minOf(points[0], points[2]) - bounds.left) * window.size.width / bounds.width
            val y = (minOf(points[1], points[3]) - bounds.top) * window.size.height / bounds.height
            val right = (maxOf(points[0], points[2]) - bounds.left) * window.size.width / bounds.width
            val bottom = (maxOf(points[1], points[3]) - bounds.top) * window.size.height / bounds.height
            if (listOf(x, y, right, bottom).any { !it.isFinite() || it < -16384 || it > 32768 }) return intArrayOf()
            return intArrayOf(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(),
                maxOf(1, kotlin.math.ceil(right).toInt() - kotlin.math.floor(x).toInt()),
                maxOf(1, kotlin.math.ceil(bottom).toInt() - kotlin.math.floor(y).toInt()))
        }
    })
    @Volatile private var queuedTestInput = 0L
    fun toggleFullscreen(token: android.os.IBinder) {
        check(Looper.myLooper() === handler.looper)
        val window = taskForToken(token)?.let(windows::get) ?: return
        check(remote.requestFullscreen(window.task.id, window.task.generation, 2))
    }
    fun connect() {
        check(Looper.myLooper() === handler.looper)
        outgoingDrag // Register the authorized WMS source hook before opening tasks.
        val state = remote.connectSeat(callback)
        val characterCount = WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT
        check(state.size == 8 + characterCount + WaylandKeyboardMap.SCAN_COUNT && state[0] == 2)
        check(state[1] > 0 && state[2] and 15 == state[2] && state[3] >= 0 && state[4] >= 0 && state[5] >= 0)
        // Seed clients synchronously before organizer registration can create an
        // APK window. Later global callbacks retain the same seat chronology.
        input.keyboardLayout(state[3], state.copyOfRange(8, 8 + characterCount),
            state.copyOfRange(8 + characterCount, state.size))
        input.repeatConfiguration(state[4], state[5])
        input.modifiers(state[6], state[7])
        input.seatCapabilities(state[2])
        textInput.availability(remote.textInputAvailable())
        dev.artzig.system.HostedClipboardMonitor.current()?.connect(
            object : dev.artzig.system.HostedClipboardMonitor.Publisher {
                override fun publish(revision: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?) = remote.setClipboard(revision, text, html, uris)
            }, remote.clipboardAvailable())
    }
    private val capture = WaylandPointerCapture(object : WaylandPointerCapture.Host {
        override fun task(token: android.os.IBinder) = taskForToken(token)?.let { windows[it]?.task }
        override fun request(task: TaskGeometry.Task, enabled: Boolean, sequence: Int): Boolean {
            if (windows[task.id]?.task != task) return false
            return remote.setPointerCapture(task.id, task.generation, enabled, sequence)
        }
        override fun mode(task: TaskGeometry.Task, enabled: Boolean) {
            windows[task.id]?.takeIf { it.task == task }?.seat?.captureChanged(enabled)
        }
        // Stock InputReader applies Android's capture request itself; the
        // compositor's lock only decides whether relative motion arrives. When
        // the compositor refuses or revokes the lock for the request Android
        // holds, release capture through the stock dispatcher, as the window
        // would (InputManagerService.requestPointerCapture).
        override fun acknowledge(token: android.os.IBinder?, sequence: Int) =
            androidCapture.acknowledged(token, sequence)
        override fun unsupported(task: TaskGeometry.Task) {
            android.util.Log.w("WaylandSeat", "Pointer capture unavailable for task ${task.id}",
                dev.artzig.ArtzigTestDiagnostics.unsupported("WaylandSeat", "setPointerCapture",
                    "Compositor pointer constraints or relative pointer capability unavailable"))
        }
    })
    private val androidCapture = AndroidPointerCapture { token ->
        android.hardware.input.IInputManager.Stub.asInterface(
            android.os.ServiceManager.getService(android.content.Context.INPUT_SERVICE))
            .requestPointerCapture(token, false)
    }
    fun pointerCapture(token: android.os.IBinder?, sequence: Int) {
        check(Looper.myLooper() === handler.looper)
        androidCapture.requested(token, sequence)
        capture.request(token, sequence)
    }
    // Binder serializes oneway calls per target object. One callback for the
    // whole seat preserves leave/enter/key chronology across different tasks.
    private val callback = object : IWaylandTaskEvents.Stub() {
        private val queuedDrags = java.util.concurrent.atomic.AtomicInteger()
        private val queuedDragPayloads = java.util.concurrent.atomic.AtomicInteger()
        private val dragOverflow = java.util.concurrent.atomic.AtomicBoolean()
        private fun dragEvent(payload: Boolean = false, action: () -> Unit) {
            if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
                throw SecurityException("Only the session presenter may publish native drags")
            val count = queuedDrags.incrementAndGet()
            val payloads = if (payload) queuedDragPayloads.incrementAndGet() else 0
            if (count > 128 || payloads > 8) {
                queuedDrags.decrementAndGet()
                if (payload) queuedDragPayloads.decrementAndGet()
                if (dragOverflow.compareAndSet(false, true)) check(handler.post {
                    nativeDrag.cancelCurrent()
                    outgoingDrag.cancelCurrent()
                    dev.artzig.ArtzigTestDiagnostics.reportIssue("runtime_exception", "WaylandDrag", "callbacks", "Native drag callback capacity exceeded")
                    dragOverflow.set(false)
                })
                return
            }
            if (!handler.post {
                try { action() } finally {
                    queuedDrags.decrementAndGet()
                    if (payload) queuedDragPayloads.decrementAndGet()
                }
            }) {
                queuedDrags.decrementAndGet()
                if (payload) queuedDragPayloads.decrementAndGet()
                error("Native drag handler has stopped")
            }
        }
        override fun nativeDragEntered(taskId: Int, generation: Long, token: Long, sourceToken: Long, x: Float, y: Float) = dragEvent {
            val window = windows[taskId]?.takeIf { it.task.generation == generation }
            if (window == null) remote.finishNativeDrag(token, false)
            else nativeDrag.entered(token, sourceToken, window.task, x, y)
        }
        override fun nativeDragSourceEvent(sourceToken: Long, kind: Int, value: Boolean) = dragEvent {
            outgoingDrag.sourceEvent(sourceToken, kind, value)
        }
        override fun nativeDragMotion(token: Long, x: Float, y: Float) = dragEvent { nativeDrag.motion(token, x, y) }
        override fun nativeDragPayload(token: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?) {
            require(token > 0 && (text?.size ?: 0) <= 256 * 1024 && (html?.size ?: 0) <= 256 * 1024 && (uris?.size ?: 0) <= 256 * 1024)
            dragEvent(true) { nativeDrag.payload(token, text, html, uris) }
        }
        override fun nativeDragDropped(token: Long) = dragEvent { nativeDrag.dropped(token) }
        override fun nativeDragEnded(token: Long, consumed: Boolean) = dragEvent { nativeDrag.ended(token) }
        override fun nativeDragIssue(detail: String) = dragEvent {
            dev.artzig.ArtzigTestDiagnostics.reportIssue("runtime_exception", "WaylandDrag", "transfer", detail)
        }
        private val queuedClipboard = java.util.concurrent.atomic.AtomicInteger()
        private val clipboardOverflow = java.util.concurrent.atomic.AtomicBoolean()
        override fun clipboardAvailability(available: Boolean) = deliverSeat {
            dev.artzig.system.HostedClipboardMonitor.current()?.availability(available)
        }
        override fun clipboardChanged(revision: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?) {
            if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
                throw SecurityException("Only the session presenter may publish clipboard data")
            require(revision >= 0 && (text?.size ?: 0) <= 256 * 1024 && (html?.size ?: 0) <= 256 * 1024 && (uris?.size ?: 0) <= 256 * 1024)
            if (queuedClipboard.incrementAndGet() > 8) {
                queuedClipboard.decrementAndGet()
                if (clipboardOverflow.compareAndSet(false, true)) check(handler.post {
                    dev.artzig.system.HostedClipboardMonitor.issue("Clipboard callback capacity exceeded")
                    clipboardOverflow.set(false)
                })
                return
            }
            if (!handler.post {
                try { dev.artzig.system.HostedClipboardMonitor.current()?.changed(revision, text, html, uris) }
                finally { queuedClipboard.decrementAndGet() }
            }) {
                queuedClipboard.decrementAndGet()
                error("Clipboard owner handler has stopped")
            }
        }
        override fun clipboardIssue(detail: String) = deliverSeat {
            dev.artzig.system.HostedClipboardMonitor.nativeIssue(detail)
        }
        private fun deliverSeat(action: () -> Unit) {
            if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
                throw SecurityException("Only the session presenter may publish seat state")
            check(handler.post { action() })
        }
        private fun deliver(id: Int, generation: Long, action: (Window) -> Unit) {
            if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
                throw SecurityException("Only the session presenter may deliver task events")
            check(handler.post {
                val window = windows[id]
                if (window != null && window.task.generation == generation) action(window)
            })
        }
        override fun configure(taskId: Int, generation: Long, width: Int, height: Int,
            bufferWidth: Int, bufferHeight: Int, configurationGeneration: Long, serial: Long, scale120: Int) =
            deliver(taskId, generation) {
                // createWindow returns the latest snapshot after its initial
                // roundtrips. Older callbacks queued during creation are covered.
                if (configurationGeneration > it.size.configurationGeneration) {
                    it.size = WaylandTaskShell.Size(width, height, bufferWidth, bufferHeight,
                        configurationGeneration, serial, scale120)
                    it.events.configure(it.size)
                }
            }
        override fun suspended(taskId: Int, generation: Long, suspended: Boolean) =
            deliver(taskId, generation) { it.events.suspended(suspended) }
        override fun fullscreen(taskId: Int, generation: Long, fullscreen: Boolean) =
            deliver(taskId, generation) { it.events.fullscreen(fullscreen) }
        override fun closeRequested(taskId: Int, generation: Long) =
            deliver(taskId, generation) { it.events.closeRequested() }
        override fun keyboardFocus(taskId: Int, generation: Long, gained: Boolean, heldScanCodes: IntArray) =
            deliver(taskId, generation) { it.seat.keyboardFocus(gained, heldScanCodes) }
        override fun pointer(taskId: Int, generation: Long, kind: Int, button: Int, pressed: Boolean, x: Float, y: Float) =
            deliver(taskId, generation) { it.seat.pointer(kind, button, pressed, x, y) }
        override fun scroll(taskId: Int, generation: Long, x: Float, y: Float, horizontal: Float, vertical: Float) =
            deliver(taskId, generation) { it.seat.scroll(x, y, horizontal, vertical) }
        override fun key(taskId: Int, generation: Long, scanCode: Int, pressed: Boolean, repeatable: Boolean) =
            deliver(taskId, generation) { it.seat.key(scanCode, pressed, repeatable) }
        override fun touch(taskId: Int, generation: Long, action: Int, id: Int, x: Float, y: Float,
            major: Float, minor: Float, orientation: Float) =
            deliver(taskId, generation) { it.seat.touch(action, id, x, y, major, minor, orientation) }
        override fun cancelTouch(taskId: Int, generation: Long) =
            deliver(taskId, generation) { it.seat.cancelTouches() }
        override fun repeatConfiguration(rate: Int, delayMillis: Int) =
            deliverSeat { input.repeatConfiguration(rate, delayMillis) }
        override fun pointerCaptureChanged(taskId: Int, generation: Long, enabled: Boolean, sequence: Int) =
            deliver(taskId, generation) { capture.changed(it.task, enabled, sequence) }
        override fun relativePointer(taskId: Int, generation: Long, dx: Float, dy: Float) =
            deliver(taskId, generation) { it.seat.relativePointer(dx, dy) }
        override fun testInputBarrier(taskId: Int, generation: Long, sequence: Long) =
            deliver(taskId, generation) {
                check(sequence > queuedTestInput) { "Test input sequence rewound" }
                queuedTestInput = sequence
            }
        override fun seatCapabilities(sourceGeneration: Int, capabilities: Int) =
            deliverSeat { input.seatCapabilities(capabilities) }
        override fun tabletDevice(deviceId: Int, sourceGeneration: Int, toolType: Int,
            capabilities: Int, present: Boolean) = deliverSeat {
                val device = WaylandTabletInput.Device(deviceId, sourceGeneration, toolType, capabilities)
                input.tabletDevice(device, present)
            }
        override fun tablet(taskId: Int, generation: Long, deviceId: Int, deviceGeneration: Int,
            action: Int, button: Int, pressed: Boolean, axes: FloatArray) = deliver(taskId, generation) {
                it.seat.tablet(WaylandTabletInput.Sample(deviceId, deviceGeneration, action, button, pressed, axes))
            }
        override fun cancelTablet(taskId: Int, generation: Long, deviceId: Int) =
            deliver(taskId, generation) { it.seat.cancelTablet(deviceId) }
        override fun modifiers(metaState: Int, persistentMetaState: Int) =
            deliverSeat { input.modifiers(metaState, persistentMetaState) }
        override fun keyboardLayout(layoutGeneration: Int, characters: IntArray, symbols: IntArray) =
            deliverSeat { input.keyboardLayout(layoutGeneration, characters, symbols) }
        override fun textInputAvailability(available: Boolean) = deliverSeat { textInput.availability(available) }
        override fun textInputFocus(taskId: Int, generation: Long, focused: Boolean) =
            deliver(taskId, generation) { textInput.focus(it.task, focused) }
        override fun textInputEdit(taskId: Int, generation: Long, editorGeneration: Long, sequence: Long, fields: Int,
            preedit: String, cursorBegin: Int, cursorEnd: Int, committed: String, beforeBytes: Int, afterBytes: Int) =
            deliver(taskId, generation) { textInput.edit(it.task, WaylandInputMethod.Edit(editorGeneration, sequence,
                fields, preedit, cursorBegin, cursorEnd, committed, beforeBytes, afterBytes)) }
    }

    override fun open(task: TaskGeometry.Task, appId: String, title: String,
        size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): WaylandTaskShell.Window {
        check(Looper.myLooper() === handler.looper)
        val configuration = LongArray(7)
        check(windows[task.id] == null) { "Presenter task already exists" }
        val window = Window(task, events)
        windows[task.id] = window
        try {
            window.surface = remote.createWindow(task.id, task.generation, size.width, size.height,
                appId, title, callback, configuration)
            window.size = WaylandTaskShell.Size(configuration[0].toInt(), configuration[1].toInt(),
                configuration[2].toInt(), configuration[3].toInt(), configuration[4], configuration[5], configuration[6].toInt())
            return window
        } catch (failure: Throwable) {
            try { window.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private inner class Window(val task: TaskGeometry.Task,
        val events: WaylandTaskShell.WindowEvents) : StockTaskOrganizer.SurfaceWindow {
        override lateinit var surface: Surface
        override lateinit var size: WaylandTaskShell.Size
        val seat = input.open(task)
        private var closed = false
        private var outputGeneration = 0L
        var bounds: TaskGeometry.Bounds? = null
        override fun prepare(size: WaylandTaskShell.Size): Surface {
            check(!closed && outputGeneration < Long.MAX_VALUE)
            val replacement = remote.prepareOutput(task.id, task.generation, ++outputGeneration,
                size.configurationGeneration, size.bufferWidth, size.bufferHeight)
            val previous = surface
            surface = replacement
            previous.release()
            return replacement
        }
        override fun metadata(appId: String, title: String) {
            if (!closed) remote.setMetadata(task.id, task.generation, appId, title)
        }
        override fun leaveFullscreen() {
            if (!closed) check(remote.requestFullscreen(task.id, task.generation, 0))
        }
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!closed) {
                this.bounds = bounds
                remote.configurationCommitted(task.id, task.generation, bounds.left, bounds.top,
                    bounds.width, bounds.height, size.width, size.height,
                    outputGeneration, size.configurationGeneration)
                seat.committed(bounds, size)
            }
        }
        override fun rejected(reason: TaskGeometry.Rejection) {
            android.util.Log.w("WaylandTaskShell", "Task ${task.id}/${task.generation} geometry rejected: $reason")
        }
        override fun close() {
            if (closed) return
            closed = true
            outgoingDrag.removed(task)
            nativeDrag.removed(task)
            textInput.removed(task)
            capture.closed(task)
            if (windows[task.id] === this) windows.remove(task.id)
            try { seat.close() }
            finally {
                try { remote.destroyWindow(task.id, task.generation) }
                finally { if (::surface.isInitialized) surface.release() }
            }
        }
    }
}
