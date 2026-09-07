package dev.artzig

import android.hardware.HardwareBuffer

/** Public hardware bitmap readback and the stock lazy AHardwareBuffer image generator. */
internal object StockReadbackSmoke {
    @JvmStatic external fun pixel(buffer: HardwareBuffer): Int

    fun verifyGeometry() {
        val colors = intArrayOf(android.graphics.Color.RED, android.graphics.Color.GREEN,
            android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
        for ((width, height) in listOf(32 to 24, 24 to 40)) {
            val source = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(source)
                val paint = android.graphics.Paint()
                for (quadrant in 0..3) {
                    paint.color = colors[quadrant]
                    val left = (quadrant % 2) * width / 2f
                    val top = (quadrant / 2) * height / 2f
                    canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
                }
                val hardware = checkNotNull(source.copy(android.graphics.Bitmap.Config.HARDWARE, false))
                try {
                    val copy = checkNotNull(hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                    try {
                        for (quadrant in 0..3) {
                            val x = width / 4 + (quadrant % 2) * width / 2
                            val y = height / 4 + (quadrant / 2) * height / 2
                            check(copy.getPixel(x, y) == colors[quadrant]) {
                                "Hardware bitmap geometry changed at $x,$y in ${width}x$height"
                            }
                        }
                    } finally { copy.recycle() }
                } finally { hardware.recycle() }
            } finally { source.recycle() }
        }
        println("Stock hardware bitmap: landscape and portrait corner geometry preserved")
    }
}
