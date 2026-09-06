package dev.artzig

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * HardwareRenderer.nCreateHardwareBitmap follows AOSP's RenderNode-to-hardware-buffer
 * snapshot. The hosted pipeline already draws directly to a HardwareBufferRenderer,
 * so it does not need Android's ImageReader/BufferQueue presentation path.
 */
object HostedHardwareSnapshot {
    /** JNI supplies a Java owner for its retained native RenderNode reference. */
    @JvmStatic
    fun create(node: RenderNode, width: Int, height: Int): Bitmap? {
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, usage).use { buffer ->
            HardwareBufferRenderer(buffer).use { renderer ->
                renderer.setContentRoot(node)
                renderer.setLightSourceAlpha(0f, 0f)
                renderer.setLightSourceGeometry(0f, 0f, 0f, 0f)
                val result = CompletableFuture<HardwareBufferRenderer.RenderResult>()
                renderer.obtainRenderRequest().draw(Executor { it.run() }) { result.complete(it) }
                val rendered = result.get()
                rendered.fence.use { fence ->
                    if (rendered.status != HardwareBufferRenderer.RenderResult.SUCCESS || !fence.awaitForever()) return null
                }
                return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }
    }
}
