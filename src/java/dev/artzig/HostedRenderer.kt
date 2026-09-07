package dev.artzig

import android.content.Context
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.view.ThreadedRenderer
import java.util.concurrent.Executor

/** Desktop presentation over a stock renderer; the existing native target owns EGL/Wayland. */
class HostedRenderer private constructor(context: Context) {
    @JvmField val renderer: ThreadedRenderer = ThreadedRenderer.create(context, true, "artzig-host-hwui")
    private var width = 0
    private var height = 0
    private val scaledRoot = RenderNode("artzig-scaled-root")
    private data class FrameCallbacks(val root: android.view.ViewRootImpl,
        val handler: android.os.Handler, val callbacks: List<Runnable>)
    private val pendingFrameCallbacks = ArrayList<FrameCallbacks>()

    /** AOSP VRI captures each root's callbacks before drawing. Desktop roots
     * share one renderer, so retain all their batches for the composite frame. */
    @Synchronized
    fun queueFrameCommitCallbacks(root: android.view.ViewRootImpl, handler: android.os.Handler,
        callbacks: List<Runnable>) {
        if (callbacks.isNotEmpty()) pendingFrameCallbacks += FrameCallbacks(root, handler, callbacks.toList())
    }

    @Synchronized
    fun discardFrameCommitCallbacks(root: android.view.ViewRootImpl) {
        pendingFrameCallbacks.removeAll { it.root === root }
    }

    init {
        renderer.setLightSourceGeometry(0f, 0f, 600f, 800f)
        renderer.setLightSourceAlpha(0.039f, 0.19f)
    }

    @Synchronized
    fun render(content: RenderNode?, logicalWidth: Int, logicalHeight: Int, bufferWidth: Int, bufferHeight: Int,
        vararg roots: android.view.ViewRootImpl) {
        require(logicalWidth > 0 && logicalHeight > 0) { "invalid logical target ${logicalWidth}x$logicalHeight" }
        require(bufferWidth > 0 && bufferHeight > 0) { "invalid Wayland buffer ${bufferWidth}x$bufferHeight" }
        var target = content
        if (content != null && (bufferWidth != logicalWidth || bufferHeight != logicalHeight)) {
            scaledRoot.setPosition(0, 0, bufferWidth, bufferHeight)
            val canvas = scaledRoot.beginRecording(bufferWidth, bufferHeight)
            try {
                canvas.scale(bufferWidth.toFloat() / logicalWidth, bufferHeight.toFloat() / logicalHeight)
                canvas.drawRenderNode(content)
            } finally { scaledRoot.endRecording() }
            target = scaledRoot
        }
        render(target, bufferWidth, bufferHeight, *roots)
    }

    @Synchronized
    fun render(content: RenderNode?, bufferWidth: Int, bufferHeight: Int,
        vararg roots: android.view.ViewRootImpl) {
        require(bufferWidth > 0 && bufferHeight > 0) { "invalid render target ${bufferWidth}x$bufferHeight" }
        if (width != bufferWidth || height != bufferHeight) {
            width = bufferWidth
            height = bufferHeight
            nConfigureHostSurface(width, height)
        }
        renderer.setContentRoot(content)
        renderer.forceDrawNextFrame()
        // A traversal can precede presentation or belong to a background root.
        // Only consume callbacks for roots actually included in this composite.
        val callbacks = if (content == null) emptyList() else pendingFrameCallbacks.filter { batch ->
            roots.any { it === batch.root }
        }
        pendingFrameCallbacks.removeAll(callbacks.toSet())
        val result = renderer.createRenderRequest()
            .setVsyncTime(System.nanoTime())
            .setFrameCommitCallback(DIRECT_EXECUTOR) {
                // Pinned ViewRootImpl.addFrameCommitCallbackIfNeeded posts the
                // captured batch back to its UI Handler, including failed draws.
                for (batch in callbacks) batch.handler.postAtFrontOfQueue {
                    for (callback in batch.callbacks) callback.run()
                }
                nNotifyHostFrameCommitted()
            }
            .setWaitForPresent(false)
            .syncAndDraw()
        if (result and HardwareRenderer.SYNC_FRAME_DROPPED != 0) nNotifyHostFrameCommitted()
        check(result and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND == 0) {
            "HWUI lost the host render surface: $result"
        }
    }

    @Synchronized
    private fun destroy() {
        pendingFrameCallbacks.clear()
        nDestroyHostSurface()
        renderer.destroy()
    }

    private external fun nConfigureHostSurface(width: Int, height: Int)
    private external fun nDestroyHostSurface()

    companion object {
        private var instance: HostedRenderer? = null
        private val DIRECT_EXECUTOR = Executor { it.run() }

        @JvmStatic @Synchronized
        @JvmOverloads
        fun getInstance(context: Context? = null): HostedRenderer {
            return instance ?: HostedRenderer(context
                ?: android.app.ActivityThread.currentActivityThread()?.systemContext
                ?: HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 1, 1, 120, 0))
                .also { instance = it }
        }

        @JvmStatic @Synchronized
        fun forgetRootCallbacks(root: android.view.ViewRootImpl) {
            instance?.discardFrameCommitCallbacks(root)
        }

        @JvmStatic @Synchronized
        fun destroyInstance() {
            instance?.destroy()
            instance = null
        }

        @JvmStatic private external fun nNotifyHostFrameCommitted()
    }
}
