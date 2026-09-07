package dev.artzig.window

import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Binder transports a stock Surface; the session's Zig presenter owns the
 * consumer queue and all Wayland objects on its protocol thread. */
internal class RemoteTaskPresenter(private val remote: IWaylandTaskPresenter,
    private val handler: Handler, private val input: WaylandTaskInput) : WaylandTaskShell.Presenter {
    override fun open(task: TaskGeometry.Task, appId: String, title: String,
        size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): WaylandTaskShell.Window {
        check(Looper.myLooper() === handler.looper)
        val configuration = IntArray(2)
        val window = Window(task, events)
        try {
            window.surface = remote.createWindow(task.id, task.generation, size.width, size.height,
                appId, title, window.callback, configuration)
            window.size = WaylandTaskShell.Size(configuration[0], configuration[1])
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
        private val seat = input.open(task)
        private var closed = false
        val callback = object : IWaylandTaskEvents.Stub() {
            private fun deliver(action: () -> Unit) {
                if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
                    throw SecurityException("Only the session presenter may deliver task events")
                check(handler.post { if (!closed) action() })
            }
            override fun configure(width: Int, height: Int) {
                deliver { events.configure(WaylandTaskShell.Size(width, height)) }
            }
            override fun closeRequested() {
                deliver { events.closeRequested() }
            }
            override fun keyboardFocus(gained: Boolean) = deliver { seat.keyboardFocus(gained) }
            override fun pointer(kind: Int, button: Int, pressed: Boolean, x: Float, y: Float) =
                deliver { seat.pointer(kind, button, pressed, x, y) }
            override fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) =
                deliver { seat.scroll(x, y, horizontal, vertical) }
            override fun key(scanCode: Int, pressed: Boolean) = deliver { seat.key(scanCode, pressed) }
            override fun touch(action: Int, id: Int, x: Float, y: Float, major: Float, minor: Float, orientation: Float) =
                deliver { seat.touch(action, id, x, y, major, minor, orientation) }
            override fun cancelTouch() = deliver { input.cancelTouch() }
            override fun repeatConfiguration(rate: Int, delayMillis: Int) =
                deliver { input.repeatConfiguration(rate, delayMillis) }
        }
        override fun metadata(appId: String, title: String) {
            if (!closed) remote.setMetadata(task.id, task.generation, appId, title)
        }
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!closed) {
                remote.configurationCommitted(task.id, task.generation, bounds.left, bounds.top,
                    bounds.width, bounds.height, size.width, size.height)
                seat.committed(bounds, size)
            }
        }
        override fun rejected(reason: TaskGeometry.Rejection) {
            android.util.Log.w("WaylandTaskShell", "Task ${task.id}/${task.generation} geometry rejected: $reason")
        }
        override fun close() {
            if (closed) return
            closed = true
            try { seat.close() }
            finally {
                try { remote.destroyWindow(task.id, task.generation) }
                finally { if (::surface.isInitialized) surface.release() }
            }
        }
    }
}
