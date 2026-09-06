package com.android.server.wm

import android.os.IBinder
import android.os.RemoteException
import android.window.ITaskFpsCallback
import android.view.SurfaceControl
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real SF/JNI registration; the callback exposes only observable Binder lifetime operations. */
object StockTaskFpsSmoke {
    private class Callback(val deadAtLink: Boolean = false) : ITaskFpsCallback.Stub() {
        var links = 0
        var unlinks = 0
        var death: IBinder.DeathRecipient? = null
        val reports = LinkedBlockingQueue<Pair<Float, Thread>>()
        override fun onFpsReported(fps: Float) { reports.add(fps to Thread.currentThread()) }
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            links++
            if (deadAtLink) throw RemoteException("FPS fixture client died before link")
            check(death == null)
            death = recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            check(death === recipient)
            unlinks++
            death = null
            return true
        }
    }

    /** The anonymous module-test process has no READ_FRAME_BUFFER grant. */
    fun denied() {
        val controller = TaskFpsCallbackController()
        val callback = Callback()
        repeat(4) {
            val failure = runCatching { controller.registerListener(410, callback) }.exceptionOrNull()
            check(failure is RuntimeException && failure.message == "Couldn't addFpsListener")
            controller.unregisterListener(callback)
        }
        check(callback.links == 0 && callback.unlinks == 0)
    }

    /** The signed fixture requests READ_FRAME_BUFFER and ACCESS_SURFACE_FLINGER for its test scene. */
    fun granted() {
        val controller = TaskFpsCallbackController()
        val callback = Callback()
        controller.registerListener(410, callback)
        controller.registerListener(410, callback)
        check(callback.links == 1)
        // Before WMS boot, the fixture owns display activation and the first buffer
        // latch. Color-only transactions leave stock SF in its bootloader stage.
        val display = com.android.server.display.DisplayControl.getPhysicalDisplayToken(
            com.android.server.display.DisplayControl.getPhysicalDisplayIds().single())
        SurfaceControl.setDisplayPowerMode(display, SurfaceControl.POWER_MODE_NORMAL)
        val layer = SurfaceControl.Builder().setName("fps-task-fixture").setBufferSize(8, 8)
            .setMetadata(SurfaceControl.METADATA_TASK_ID, 410).build()
        try {
            android.hardware.HardwareBuffer.create(8, 8, android.hardware.HardwareBuffer.RGBA_8888, 1,
                android.hardware.HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                    android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    android.hardware.HardwareBuffer.USAGE_COMPOSER_OVERLAY).use { buffer ->
                val graphic = checkNotNull(android.graphics.GraphicBuffer.createFromHardwareBuffer(buffer))
                try {
                    val canvas = checkNotNull(graphic.lockCanvas(android.graphics.Rect(0, 0, 8, 8)))
                    canvas.drawColor(android.graphics.Color.BLUE)
                    graphic.unlockCanvasAndPost(canvas)
                    SurfaceControl.Transaction().use {
                        it.setLayerStack(layer, 0).setCrop(layer, android.graphics.Rect(0, 0, 8, 8))
                            .setBuffer(layer, buffer).show(layer).apply()
                    }
                } finally { graphic.destroy() }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var report: Pair<Float, Thread>? = null
            var alpha = 1f
            while (report == null && System.nanoTime() < deadline) {
                alpha = if (alpha == 1f) .9f else 1f
                SurfaceControl.Transaction().use { it.setAlpha(layer, alpha).apply() }
                report = callback.reports.poll(100, TimeUnit.MILLISECONDS)
            }
            val observed = checkNotNull(report) { "No FPS callback through native Binder/JNI" }
            check(observed.first.isFinite() && observed.first >= 0f)
            check(observed.second !== Thread.currentThread()) { "FPS callback must arrive on Binder dispatch" }
        } finally {
            SurfaceControl.Transaction().use { it.remove(layer).apply() }
            layer.release()
            controller.unregisterListener(callback)
        }
        controller.unregisterListener(callback)
        check(callback.unlinks == 1)

        controller.registerListener(410, callback)
        val recipient = checkNotNull(callback.death)
        recipient.binderDied()
        recipient.binderDied()
        check(callback.unlinks == 2 && callback.death == null)

        val alreadyDead = Callback(deadAtLink = true)
        repeat(2) { controller.registerListener(410, alreadyDead) }
        controller.unregisterListener(alreadyDead)
        check(alreadyDead.links == 2 && alreadyDead.unlinks == 0)

        val workers = Executors.newFixedThreadPool(2)
        try {
            val work = (0 until 2).map {
                workers.submit {
                    repeat(8) {
                        controller.registerListener(410, callback)
                        controller.unregisterListener(callback)
                    }
                }
            }
            work.forEach { it.get(10, TimeUnit.SECONDS) }
            controller.unregisterListener(callback)
            check(callback.links == callback.unlinks && callback.death == null)
        } finally {
            workers.shutdownNow()
        }
    }
}
