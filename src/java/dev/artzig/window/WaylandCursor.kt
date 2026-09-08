package dev.artzig.window

import android.content.Context
import android.graphics.Bitmap
import android.view.PointerIcon
import kotlin.math.roundToInt

/** Stock PointerIcon resources and custom bitmaps become bounded, immutable
 * premultiplied ARGB frames for the session's Wayland cursor surface. */
internal object WaylandCursor {
    data class Image(val width: Int, val height: Int, val hotspotX: Int, val hotspotY: Int,
        val frameMillis: Int, val pixels: IntArray)

    fun load(context: Context, requested: PointerIcon): Image? {
        val icon = if (requested.type == PointerIcon.TYPE_CUSTOM) requested else
            PointerIcon.getLoadedSystemIcon(context,
                if (requested.type == PointerIcon.TYPE_NOT_SPECIFIED) PointerIcon.TYPE_DEFAULT else requested.type,
                false, 1f)
        if (icon.type == PointerIcon.TYPE_NULL) return null
        val metadata = IntArray(4)
        val frames = readFrames(icon, metadata)
        // The pinned vector wait cursor contains 88 frames. The total pixel
        // bound below still caps transport and retained storage at 512 KiB.
        require(frames.size in 1..128) { "Cursor frame count exceeds host limit" }
        val width = frames[0].width
        val height = frames[0].height
        require(width in 1..256 && height in 1..256 &&
            width.toLong() * height * frames.size <= 128 * 1024) { "Cursor pixels exceed host limit" }
        val x = Float.fromBits(metadata[0])
        val y = Float.fromBits(metadata[1])
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x <= width && y <= height)
        val duration = if (frames.size == 1) 0 else metadata[2]
        require(frames.size == 1 || duration in 1..60_000) { "Invalid cursor animation duration" }
        val pixels = IntArray(width * height * frames.size)
        val paint = if (metadata[3] == 0) null else android.graphics.Paint().apply {
            blendMode = android.graphics.BlendMode.SRC
            setShadow(nativeInstance)
        }
        frames.forEachIndexed { index, bitmap ->
            require(bitmap.width == width && bitmap.height == height) { "Cursor frames differ in size" }
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE)
                checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
            val shaded = if (paint == null) readable else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // Pinned SpriteIcon.draw clips its image-filter result to the
                // icon's buffer extent and uses source replacement blending.
                if (paint != null) android.graphics.Canvas(shaded).drawBitmap(readable, 0f, 0f, paint)
                shaded.getPixels(pixels, index * width * height, width, 0, 0, width, height)
            } finally {
                if (shaded !== readable) shaded.recycle()
                if (readable !== bitmap) readable.recycle()
            }
        }
        // Bitmap.getPixels returns straight-alpha sRGB; wl_shm ARGB8888 uses
        // native-endian premultiplied components. Each immutable frame is copied
        // once and can stay attached until the compositor releases its storage.
        pixels.indices.forEach { i ->
            val pixel = pixels[i]
            val alpha = pixel ushr 24
            fun component(shift: Int) = (((pixel ushr shift) and 255) * alpha + 127) / 255
            pixels[i] = (alpha shl 24) or (component(16) shl 16) or (component(8) shl 8) or component(0)
        }
        // Core Wayland hotspots have integer surface coordinates.
        return Image(width, height, x.roundToInt(), y.roundToInt(), duration, pixels)
    }

    @JvmStatic private external fun readFrames(icon: PointerIcon, metadata: IntArray): Array<Bitmap>
    @JvmStatic private external fun setShadow(paint: Long)
}
