package dev.artzig.window

import android.app.ActivityTaskManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display

/** A real organized APK task with a memory-backed SF output in place of Wayland. */
internal object StockTaskShellSmoke {
    fun run(packageName: String) {
        val handler = Handler(Looper.getMainLooper())
        val windows = mutableListOf<Window>()
        val presenter = object : WaylandTaskShell.Presenter {
            override fun open(task: TaskGeometry.Task, appId: String, title: String,
                size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): WaylandTaskShell.Window {
                check(appId == packageName)
                return Window(task, events, handler).also { windows.add(it) }
            }
        }
        // This client exercises remote TaskOrganizer and SF against a plain
        // fixture. Production reads immersive window attributes inside WMS;
        // compositor fullscreen regressions exercise that local metadata path.
        val shell = WaylandTaskShell(StockTaskOrganizer(handler, immersive = { false }), presenter)
        try {
            shell.start()
            try {
                await("initial task output") { windows.size == 1 && windows.single().committed && windows.single().colored }
            } catch (failure: Throwable) {
                val prefix = "/tmp/wayland-stock-shell-${android.os.Process.myPid()}"
                for ((suffix, service, args) in listOf(
                        Triple("sf", "SurfaceFlinger", emptyArray<String>()),
                        Triple("frontend", "SurfaceFlinger", arrayOf("--frontend")),
                        Triple("wm", "window", arrayOf("-a")))) {
                    java.io.FileOutputStream("$prefix.$suffix.txt").use {
                        android.os.ServiceManager.getService(service).dump(it.fd, args)
                    }
                }
                throw IllegalStateException("Task outputs: ${windows.map { "id=${it.task.id} committed=${it.committed} colored=${it.colored} closed=${it.closed} frames=${it.frames} colors=${it.colors}" }}", failure)
            }
            val window = windows.single()
            val task = ActivityTaskManager.getService().getTasks(10, false, true, Display.DEFAULT_DISPLAY)
                .single { it.taskId == window.task.id }
            check(task.configuration.windowConfiguration.bounds.width() == 320 &&
                task.configuration.windowConfiguration.bounds.height() == 240) {
                "WMS did not apply the synchronized task bounds: ${task.configuration.windowConfiguration.bounds}"
            }
            window.events.closeRequested()
            await("Android task disappearance") { window.closed }
            check(ActivityTaskManager.getService().getTasks(10, false, true, Display.DEFAULT_DISPLAY)
                .none { it.taskId == window.task.id })
            println("Stock task shell: WCT bounds, SF private output pixels and Android task removal passed")
        } finally { shell.close() }
    }

    private class Window(val task: TaskGeometry.Task, val events: WaylandTaskShell.WindowEvents,
        handler: Handler) : StockTaskOrganizer.SurfaceWindow {
        override val size = WaylandTaskShell.Size(320, 240)
        private val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        override val surface = reader.surface
        var committed = false
        var colored = false
        var closed = false
        var frames = 0
        var colors = emptyList<String>()
        init {
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val plane = image.planes.single()
                    fun pixel(x: Int, y: Int): Int {
                        val offset = plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride
                        fun byte(index: Int) = plane.buffer.get(offset + index).toInt() and 255
                        return Color.argb(byte(3), byte(0), byte(1), byte(2))
                    }
                    frames++
                    val inset = minOf(image.width, image.height) / 16
                    colors = listOf(pixel(image.width / 2, image.height / 2), pixel(inset, inset),
                        pixel(image.width - inset - 1, inset), pixel(inset, image.height - inset - 1),
                        pixel(image.width - inset - 1, image.height - inset - 1)).map { it.toUInt().toString(16) }
                    colored = pixel(image.width / 2, image.height / 2) == Color.CYAN &&
                        pixel(inset, inset) == Color.RED && pixel(image.width - inset - 1, inset) == Color.GREEN &&
                        pixel(inset, image.height - inset - 1) == Color.BLUE &&
                        pixel(image.width - inset - 1, image.height - inset - 1) == Color.YELLOW
                }
            }, handler)
        }
        override fun metadata(appId: String, title: String) { }
        override fun immersive(enabled: Boolean) { check(!enabled) { "Unexpected immersive fixture request" } }
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            check(!closed && bounds.width == 320 && bounds.height == 240)
            committed = true
        }
        override fun rejected(reason: TaskGeometry.Rejection) { error("Task output rejected: $reason") }
        override fun close() { check(!closed); closed = true; reader.close(); surface.release() }
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        val queue = checkNotNull(Looper.myLooper()).queue
        while (!ready() && SystemClock.uptimeMillis() < deadline) {
            val message = queue.`pollDueMessage$artzig`()
            if (message == null) { SystemClock.sleep(1); continue }
            try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        check(ready()) { "Timed out waiting for $label" }
    }
}
