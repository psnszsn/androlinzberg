package dev.artzig.window

import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Binder transports a stock Surface; the session's Zig presenter owns the
 * consumer queue and all Wayland objects on its protocol thread. */
internal class RemoteTaskPresenter(private val remote: IWaylandTaskPresenter,
    private val handler: Handler) : WaylandTaskShell.Presenter {
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
        private var closed = false
        val callback = object : IWaylandTaskEvents.Stub() {
            override fun configure(width: Int, height: Int) {
                check(handler.post { if (!closed) events.configure(WaylandTaskShell.Size(width, height)) })
            }
            override fun closeRequested() {
                check(handler.post { if (!closed) events.closeRequested() })
            }
        }
        override fun metadata(appId: String, title: String) {
            if (!closed) remote.setMetadata(task.id, task.generation, appId, title)
        }
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!closed) remote.configurationCommitted(task.id, task.generation, bounds.left, bounds.top,
                bounds.width, bounds.height, size.width, size.height)
        }
        override fun rejected(reason: TaskGeometry.Rejection) {
            android.util.Log.w("WaylandTaskShell", "Task ${task.id}/${task.generation} geometry rejected: $reason")
        }
        override fun close() {
            if (closed) return
            closed = true
            try { remote.destroyWindow(task.id, task.generation) }
            finally { if (::surface.isInitialized) surface.release() }
        }
    }
}
