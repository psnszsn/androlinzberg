package dev.artzig.window

import android.content.Context
import android.graphics.Bitmap
import android.view.PointerIcon

internal object WaylandCursorSmoke {
    fun run(context: Context) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0x80ff0000.toInt())
            val custom = checkNotNull(WaylandCursor.load(context, PointerIcon.create(bitmap, 1.25f, 0.6f)))
            check(custom.width == 2 && custom.height == 2 && custom.hotspotX == 1 && custom.hotspotY == 1)
            check(custom.frameMillis == 0 && custom.pixels.contentEquals(IntArray(4) { 0x80800000.toInt() }))
            check(!bitmap.isRecycled && bitmap.getPixel(0, 0) == 0x80ff0000.toInt())
            val hardware = checkNotNull(bitmap.copy(Bitmap.Config.HARDWARE, false))
            try {
                val copied = checkNotNull(WaylandCursor.load(context, PointerIcon.create(hardware, 1.25f, 0.6f)))
                check(copied.pixels.contentEquals(custom.pixels) && !hardware.isRecycled)
            } finally { hardware.recycle() }
        } finally { bitmap.recycle() }
        val shadowSource = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            for (y in 14..17) for (x in 14..17) shadowSource.setPixel(x, y, android.graphics.Color.WHITE)
            val icon = PointerIcon.create(shadowSource, 16f, 16f)
            val plain = checkNotNull(WaylandCursor.load(context, icon))
            icon.setDrawNativeDropShadow(true)
            val shaded = checkNotNull(WaylandCursor.load(context, icon))
            check(shaded.pixels.indices.any { plain.pixels[it] == 0 && shaded.pixels[it] ushr 24 > 0 }) {
                "Stock cursor shadow filter did not render"
            }
            check(shadowSource.getPixel(12, 12) == 0) { "Cursor rendering mutated the caller's bitmap" }
        } finally { shadowSource.recycle() }
        for (type in intArrayOf(PointerIcon.TYPE_DEFAULT, PointerIcon.TYPE_TEXT, PointerIcon.TYPE_WAIT)) {
            val icon = checkNotNull(WaylandCursor.load(context, PointerIcon.getSystemIcon(context, type)))
            check(icon.width > 0 && icon.height > 0 && icon.pixels.any { it != 0 })
        }
        val oversized = Bitmap.createBitmap(257, 1, Bitmap.Config.ARGB_8888)
        try {
            try {
                WaylandCursor.load(context, PointerIcon.create(oversized, 0f, 0f))
                error("Oversized cursor was accepted")
            } catch (_: IllegalArgumentException) { }
        } finally { oversized.recycle() }
        println("Wayland cursor: stock resources/shadow filter, animation frames, custom alpha/hotspot and size limits passed")
    }
}
