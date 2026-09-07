package dev.artzig.window

import android.os.Binder
import android.os.Process
import android.view.InputWindowHandle
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.WindowInfosListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object StockWindowInfosSmoke {
    fun granted() {
        val updates = LinkedBlockingQueue<Pair<Array<InputWindowHandle>, Array<WindowInfosListener.DisplayInfo>>>()
        val listener = object : WindowInfosListener() {
            override fun onWindowInfosChanged(windows: Array<InputWindowHandle>, displays: Array<DisplayInfo>) {
                updates.add(windows to displays)
            }
        }
        val root = SurfaceControl.Builder().setName("window-infos-root").setContainerLayer().build()
        val color = SurfaceControl.Builder().setName("window-infos-color").setColorLayer().setParent(root).build()
        val token = Binder()
        val info = InputWindowHandle(null, 0).apply {
            this.token = token
            name = "window-infos-contract"
            ownerPid = Process.myPid(); ownerUid = Process.myUid()
            layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION
            dispatchingTimeoutMillis = 5000
            frame.set(0, 0, 64, 64); touchableRegion.set(frame); scaleFactor = 1f
        }
        fun awaitPosition(x: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "No composed window information at x=$x" }
                val update = checkNotNull(updates.poll(remaining, TimeUnit.NANOSECONDS))
                val window = update.first.firstOrNull { it.name == info.name && it.frame.left == x } ?: continue
                check(window.token == token && window.ownerUid == Process.myUid() && window.ownerPid == Process.myPid())
                check(window.displayId == 0 && window.frame.top == 80 && window.frame.width() == 64)
                val display = checkNotNull(update.second.firstOrNull { it.mDisplayId == 0 })
                check(display.mLogicalSize.width > 0 && display.mLogicalSize.height > 0)
                val point = floatArrayOf(x.toFloat(), 80f)
                display.mTransform.mapPoints(point)
                check(point.all { it.isFinite() })
                return
            }
        }
        try {
            val cached = listener.register()
            check(cached.first != null && cached.second != null)
            SurfaceControl.Transaction().use { tx ->
                tx.setPosition(root, 100f, 80f).setWindowCrop(root, 64, 64)
                    .setLayer(root, 200).setInputWindowInfo(root, info).show(root)
                    .setColor(color, floatArrayOf(0f, 1f, 0f)).setWindowCrop(color, 64, 64).show(color).apply()
            }
            awaitPosition(100)
            SurfaceControl.Transaction().use { it.setPosition(root, 180f, 80f).apply() }
            awaitPosition(180)
        } finally {
            listener.unregister()
            listener.unregister()
            SurfaceControl.Transaction().use { it.remove(color).remove(root).apply() }
            color.release(); root.release()
        }
    }
}
