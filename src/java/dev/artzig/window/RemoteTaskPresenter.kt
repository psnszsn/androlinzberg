package dev.artzig.window

import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Binder transports a stock Surface; the session's Zig presenter owns the
 * consumer queue and all Wayland objects on its protocol thread. */
internal class RemoteTaskPresenter(private val remote: IWaylandTaskPresenter,
    private val handler: Handler, private val input: WaylandTaskInput,
    private val taskForToken: (android.os.IBinder) -> Int?,
    private val publishCapabilities: (Int, Int) -> Unit,
    acknowledgeCapture: (android.os.IBinder?, Int) -> Unit) : WaylandTaskShell.Presenter {
    private val windows = mutableMapOf<Int, Window>()
    @Volatile private var queuedTestInput = 0L
    fun testInputQueued(sequence: Long): Boolean = sequence > 0 && queuedTestInput >= sequence
    fun connect() {
        check(Looper.myLooper() === handler.looper)
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
        publishCapabilities(state[1], state[2])
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
        override fun acknowledge(token: android.os.IBinder?, sequence: Int) = acknowledgeCapture(token, sequence)
        override fun unsupported(task: TaskGeometry.Task) {
            android.util.Log.w("WaylandSeat", "Pointer capture unavailable for task ${task.id}",
                dev.artzig.ArtzigTestDiagnostics.unsupported("WaylandSeat", "setPointerCapture",
                    "Compositor pointer constraints or relative pointer capability unavailable"))
        }
    })
    fun pointerIcon(token: android.os.IBinder, type: Int): Boolean {
        check(Looper.myLooper() === handler.looper)
        val window = taskForToken(token)?.let(windows::get) ?: return false
        return remote.setPointerIcon(window.task.id, window.task.generation, type)
    }
    fun cursorImage(token: android.os.IBinder, image: WaylandCursor.Image): Boolean {
        check(Looper.myLooper() === handler.looper)
        val window = taskForToken(token)?.let(windows::get) ?: return false
        return remote.setCursorImage(window.task.id, window.task.generation, image.width, image.height,
            image.hotspotX, image.hotspotY, image.frameMillis, image.pixels)
    }
    fun pointerCapture(token: android.os.IBinder?, sequence: Int) {
        check(Looper.myLooper() === handler.looper)
        capture.request(token, sequence)
    }
    // Binder serializes oneway calls per target object. One callback for the
    // whole seat preserves leave/enter/key chronology across different tasks.
    private val callback = object : IWaylandTaskEvents.Stub() {
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
            bufferWidth: Int, bufferHeight: Int, configurationGeneration: Long, serial: Long) =
            deliver(taskId, generation) {
                // createWindow returns the latest snapshot after its initial
                // roundtrips. Older callbacks queued during creation are covered.
                if (configurationGeneration > it.size.configurationGeneration) {
                    it.size = WaylandTaskShell.Size(width, height, bufferWidth, bufferHeight,
                        configurationGeneration, serial)
                    it.events.configure(it.size)
                }
            }
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
            deliverSeat { publishCapabilities(sourceGeneration, capabilities) }
        override fun modifiers(metaState: Int, persistentMetaState: Int) =
            deliverSeat { input.modifiers(metaState, persistentMetaState) }
        override fun keyboardLayout(layoutGeneration: Int, characters: IntArray, symbols: IntArray) =
            deliverSeat { input.keyboardLayout(layoutGeneration, characters, symbols) }
    }

    override fun open(task: TaskGeometry.Task, appId: String, title: String,
        size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): WaylandTaskShell.Window {
        check(Looper.myLooper() === handler.looper)
        val configuration = LongArray(6)
        check(windows[task.id] == null) { "Presenter task already exists" }
        val window = Window(task, events)
        windows[task.id] = window
        try {
            window.surface = remote.createWindow(task.id, task.generation, size.width, size.height,
                appId, title, callback, configuration)
            window.size = WaylandTaskShell.Size(configuration[0].toInt(), configuration[1].toInt(),
                configuration[2].toInt(), configuration[3].toInt(), configuration[4], configuration[5])
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
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!closed) {
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
