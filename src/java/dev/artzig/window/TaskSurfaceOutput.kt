package dev.artzig.window

import android.graphics.Rect
import android.os.IBinder
import android.view.Surface
import android.view.SurfaceControl
import com.android.server.display.DisplayControl

/**
 * Private SF composition of an organized task into a host-owned producer queue.
 * This is never registered with DisplayManager: applications continue to see the
 * one Android display. Pinned SurfaceControl mirror/transaction and DisplayControl
 * own the Android objects; the presentation adapter owns the supplied Surface.
 * Calls belong to the shell's serial executor.
 */
internal class TaskSurfaceOutput(
    name: String,
    leash: SurfaceControl,
    private val surface: Surface,
    private val layerStack: Int,
) : AutoCloseable {
    companion object {
        private var nextLayerStack = 1
        @Synchronized fun allocateLayerStack(): Int {
            check(nextLayerStack < Int.MAX_VALUE) { "Private layer stack IDs exhausted" }
            return nextLayerStack++
        }
    }
    private var display: IBinder? = null
    private var root: SurfaceControl? = null
    private var mirror: SurfaceControl? = null

    init {
        require(layerStack > 0) { "Private outputs must not use the Android display's layer stack" }
        require(leash.isValid && surface.isValid)
        try {
            // A nonsecure output makes SF black out secure descendants. The
            // Wayland connection cannot satisfy Android protected presentation.
            display = checkNotNull(DisplayControl.createVirtualDisplay(name, false, false, "", 0f))
            root = SurfaceControl.Builder().setName("$name mirror root").setContainerLayer().build()
            mirror = SurfaceControl.mirrorSurface(leash)
            val mirrorSurface = checkNotNull(mirror)
            val rootSurface = checkNotNull(root)
            check(mirrorSurface.isValid)
            SurfaceControl.Transaction().use { transaction ->
                transaction.setDisplayLayerStack(display, layerStack)
                    .setLayerStack(rootSurface, layerStack)
                    .reparent(mirrorSurface, rootSurface)
                    .apply()
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /**
     * Add output geometry to the task's synchronized Android transaction. The
     * task stays in synthetic display space; only its private mirror is offset.
     * The shell must retain the old allocation until this transaction commits.
     * The destination viewport also defines the presentation input transform.
     */
    fun configure(
        transaction: SurfaceControl.Transaction,
        bounds: TaskGeometry.Bounds,
        outputWidth: Int,
        outputHeight: Int,
        viewport: Rect = Rect(0, 0, outputWidth, outputHeight),
        surface: Surface = this.surface,
    ) {
        val token = checkNotNull(display) { "Task output is closed" }
        val mirrorSurface = checkNotNull(mirror)
        val rootSurface = checkNotNull(root)
        require(outputWidth in 1..16384 && outputHeight in 1..16384)
        require(bounds.right <= 16384 && bounds.bottom <= 16384)
        require(!viewport.isEmpty && viewport.left >= 0 && viewport.top >= 0 &&
            viewport.right <= outputWidth && viewport.bottom <= outputHeight)
        val content = Rect(0, 0, bounds.width, bounds.height)
        // SurfaceFlinger.processDisplayChanged disconnects the previous
        // producer and recreates its private display when this binder changes.
        // Bind the producer only with the synchronized bounds/projection; an
        // early bind could enqueue a frame from the preceding configuration.
        transaction.setDisplaySurface(token, surface)
            .setPosition(mirrorSurface, -bounds.left.toFloat(), -bounds.top.toFloat())
            .setWindowCrop(rootSurface, content.width(), content.height())
            .show(rootSurface).show(mirrorSurface)
            .setDisplaySize(token, outputWidth, outputHeight)
            .setDisplayProjection(token, Surface.ROTATION_0, content, viewport)
    }

    override fun close() {
        if (display == null && root == null && mirror == null) return
        val token = display
        display = null
        val oldRoot = root
        root = null
        val oldMirror = mirror
        mirror = null
        try {
            SurfaceControl.Transaction().use { transaction ->
                if (token != null) transaction.setDisplaySurface(token, null)
                if (oldMirror?.isValid == true) transaction.remove(oldMirror)
                if (oldRoot?.isValid == true) transaction.remove(oldRoot)
                if (token != null || oldMirror != null || oldRoot != null) transaction.apply()
            }
        } finally {
            try { if (token != null) DisplayControl.destroyVirtualDisplay(token) }
            finally { oldMirror?.release(); oldRoot?.release() }
        }
    }
}
