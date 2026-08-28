package dev.artzig

import android.graphics.Bitmap
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.ThreadedRenderer

/** Regression probe for the host's GBM-backed Bitmap/HardwareBuffer contract. */
public final class HardwareBitmapProbe {
    private constructor () {}

    companion object {
        private val TAG: String = "HardwareBitmapProbe"
        private val ROOT: RenderNode = RenderNode("hardware-bitmap-probe")
        private var sChecked: Boolean = false
        private var sFrames: Int = 0

        @JvmStatic public fun wantsFullscreen(): Boolean {
            val mode: String = System.getenv("AZK_PROBE_FULLSCREEN")
            return "1".equals(mode) || ("toggle".equals(mode) && sFrames == 1)
        }

        @JvmStatic public fun drawHardware(
            logicalWidth: Int,
            logicalHeight: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            scale120: Int,
        ) {
            sFrames++
            ROOT.setPosition(0, 0, bufferWidth, bufferHeight)
            val canvas: RecordingCanvas = ROOT.beginRecording(bufferWidth, bufferHeight)
            canvas.drawColor(0xff20242b.toInt())
            ROOT.endRecording()
            ThreadedRenderer.getInstance().render(ROOT, bufferWidth, bufferHeight)

            if (sChecked) return
            val usage: Long =
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            if (!HardwareBuffer.isSupported(8, 8, HardwareBuffer.RGBA_8888, 1, usage)) {
                throw AssertionError("GBM RGBA render target is unsupported")
            }
            val allocated: HardwareBuffer =
                HardwareBuffer.create(8, 8, HardwareBuffer.RGBA_8888, 1, usage)
            if (allocated.getWidth() != 8 || allocated.getHeight() != 8 || allocated.getId() == 0.toLong()) {
                throw AssertionError("invalid allocated HardwareBuffer")
            }
            allocated.close()
            if (!allocated.isClosed()) throw AssertionError("HardwareBuffer close failed")

            val raster: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val hardware: Bitmap? = raster.copy(Bitmap.Config.HARDWARE, false)
            if (hardware == null) throw AssertionError("hardware bitmap allocation failed")
            val buffer: HardwareBuffer? = hardware.getHardwareBuffer()
            if (buffer == null) throw AssertionError("hardware bitmap returned a null buffer")
            if (buffer.getFormat() != HardwareBuffer.RGBA_8888) {
                throw AssertionError("unexpected buffer format " + buffer.getFormat())
            }
            if (buffer.getWidth() != 16 || buffer.getHeight() != 16 ||
                (buffer.getUsage() and usage) != usage || buffer.getId() == 0.toLong()
            ) {
                throw AssertionError("hardware bitmap did not expose its GBM allocation")
            }
            buffer.close()
            sChecked = true
            Log.i(TAG, "PASS: Config.HARDWARE -> GBM/dma-buf HardwareBuffer.RGBA_8888")
        }

        @JvmStatic public fun motion(
            action: Int,
            x: Float,
            y: Float,
        ) {}

        @JvmStatic public fun scroll(
            x: Float,
            y: Float,
            horizontal: Float,
            vertical: Float,
        ) {}

        @JvmStatic public fun back() {}

        @JvmStatic public fun key(
            action: Int,
            keyCode: Int,
            unicode: Int,
        ) {}

        @JvmStatic public fun hover(
            x: Float,
            y: Float,
        ): Int = 1000
    }
}
