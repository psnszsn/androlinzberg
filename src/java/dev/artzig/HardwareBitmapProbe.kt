package dev.artzig

import android.graphics.Bitmap
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.util.Log
import dev.artzig.HostedRenderer

/** Regression probe for the host's GBM-backed Bitmap/HardwareBuffer contract. */
public final class HardwareBitmapProbe {
    private constructor () {}

    companion object {
        private val TAG: String = "HardwareBitmapProbe"
        private val ROOT: RenderNode = RenderNode("hardware-bitmap-probe")
        private var presenter: HostedRenderer? = null
        private var sChecked: Boolean = false
        private var sFrames: Int = 0

        @JvmStatic public fun needsFrame(): Boolean = sFrames < 2

        @JvmStatic public fun wantsFullscreen(): Boolean {
            val mode: String? = System.getenv("AZK_PROBE_FULLSCREEN")
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
            canvas.drawColor(if (sChecked) 0xff207040.toInt() else 0xff20242b.toInt())
            ROOT.endRecording()
            val renderer = presenter ?: run {
                if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
                val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
                    logicalWidth, logicalHeight, scale120, 0)
                HostedRenderer.getInstance(context).also { presenter = it }
            }
            renderer.render(ROOT, bufferWidth, bufferHeight)

            if (sChecked) {
                if (sFrames == 2) Log.i(TAG, "PASS: desktop frame submitted after snapshot renderer teardown")
                return
            }
            StockRendererSmoke.run()
            verifyGraphicBufferPixels()
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

        private fun verifyGraphicBufferPixels() {
            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
            val graphic = checkNotNull(android.graphics.GraphicBuffer.create(8, 8, 1, usage.toInt()))
            var imported: HardwareBuffer? = null
            try {
                val canvas = checkNotNull(graphic.lockCanvas())
                canvas.drawColor(android.graphics.Color.RED)
                graphic.unlockCanvasAndPost(canvas)
                val clipped = checkNotNull(graphic.lockCanvas(android.graphics.Rect(2, 2, 6, 6)))
                clipped.drawColor(android.graphics.Color.BLUE)
                graphic.unlockCanvasAndPost(clipped)
                HardwareBuffer.createFromGraphicBuffer(graphic).use { owner ->
                    val parcel = android.os.Parcel.obtain()
                    try {
                        owner.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        imported = HardwareBuffer.CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                }
                graphic.destroy()
                val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(checkNotNull(imported),
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)))
                try {
                    val pixels = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    try {
                        for ((point, expected) in listOf(
                            (0 to 0) to android.graphics.Color.RED,
                            (3 to 3) to android.graphics.Color.BLUE,
                            (7 to 7) to android.graphics.Color.RED)) {
                            val actual = pixels.getPixel(point.first, point.second)
                            check(actual == expected) {
                                "GraphicBuffer pixel $point: ${Integer.toHexString(actual)}, expected ${Integer.toHexString(expected)}"
                            }
                        }
                    } finally { pixels.recycle() }
                } finally { bitmap.recycle() }
                Log.i(TAG, "PASS: stock GraphicBuffer Canvas clipping and parcelled DMA-BUF pixel readback")
            } finally {
                imported?.close()
                graphic.destroy()
            }
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
