package dev.artzig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.view.ThreadedRenderer

/** Public RenderNode snapshot pixels, ownership and failure contracts at the stock API. */
internal object StockRendererSmoke {
    fun run() {
        val node = RenderNode("stock-renderer-snapshot")
        node.setPosition(0, 0, 8, 8)
        fun record(color: Int) {
            val canvas = node.beginRecording(8, 8)
            try {
                canvas.drawColor(color)
                canvas.clipRect(2, 2, 6, 6)
                canvas.drawColor(Color.BLUE)
            } finally { node.endRecording() }
        }
        fun verify(background: Int) {
            val snapshot = checkNotNull(ThreadedRenderer.createHardwareBitmap(node, 8, 8))
            try {
                check(snapshot.config == Bitmap.Config.HARDWARE && snapshot.width == 8 && snapshot.height == 8)
                val pixels = checkNotNull(snapshot.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    for ((point, expected) in listOf((0 to 0) to background,
                        (3 to 3) to Color.BLUE, (7 to 7) to background)) {
                        val actual = pixels.getPixel(point.first, point.second)
                        check(actual == expected) {
                            "snapshot pixel $point: ${Integer.toHexString(actual)}, expected ${Integer.toHexString(expected)}"
                        }
                    }
                } finally { pixels.recycle() }
            } finally { snapshot.recycle() }
        }
        record(Color.RED)
        verify(Color.RED)
        // Snapshotting borrows the caller's RenderNode; recording it again remains valid.
        record(Color.GREEN)
        verify(Color.GREEN)
        check(HardwareRenderer.createHardwareBitmap(node, 0, 8) == null)
        check(HardwareRenderer.createHardwareBitmap(node, 8, -1) == null)
        println("stock-renderer: hardware snapshots preserve pixels, dimensions and RenderNode ownership")
    }
}
