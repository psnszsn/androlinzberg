package com.android.server.wallpaper

import android.app.ActivityThread
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.SparseArray
import android.view.Display
import android.view.WindowManager
import com.android.server.wm.WindowManagerInternal
import dev.artzig.system.HostedWallpaperImage
import java.nio.file.Files

/** Stock crop geometry, file processing, and failed-image cleanup at the host record boundary. */
object StockWallpaperCropSmoke {
    fun run() {
        val info = WallpaperDefaultDisplayInfo()
        val imageSize = Point(200, 100)
        val screenSize = Point(100, 100)
        val imageBounds = Rect(0, 0, 200, 100)
        check(WallpaperCropper.noParallax(imageBounds, screenSize, imageSize, false) == Rect(0, 0, 100, 100))
        check(WallpaperCropper.noParallax(imageBounds, screenSize, imageSize, true) == Rect(100, 0, 200, 100))
        check(imageBounds == Rect(0, 0, 200, 100))
        for (rtl in listOf(false, true)) {
            val crop = WallpaperCropper.getCrop(screenSize, info, imageSize, SparseArray(), rtl)
            check(imageBounds.contains(crop) && !crop.isEmpty)
        }
        val hints = SparseArray<Rect>().apply {
            put(WallpaperManager.ORIENTATION_PORTRAIT, Rect(20, 10, 80, 90))
            put(WallpaperManager.ORIENTATION_LANDSCAPE, Rect(40, 30, 170, 80))
        }
        check(WallpaperCropper.getTotalCrop(hints) == Rect(20, 10, 170, 90))

        val context: Context = ActivityThread.currentActivityThread().systemContext
        val windows = object : WindowManagerInternal() {
            override fun isUidFocused(uid: Int): Boolean = error("Wallpaper crop must not query focus")
            override fun getTopFocusedDisplayId(): Int = Display.DEFAULT_DISPLAY
            override fun isHomeSupportedOnDisplay(displayId: Int): Boolean = displayId == Display.DEFAULT_DISPLAY
        }
        val helper = WallpaperDisplayHelper(context.getSystemService(DisplayManager::class.java),
            context.getSystemService(WindowManager::class.java), windows, context.resources)
        helper.getDisplayDataOrCreate(Display.DEFAULT_DISPLAY).apply { mWidth = 8; mHeight = 8 }
        val cropper = WallpaperCropper(helper)
        check(GLHelper.getMaxTextureSize() in 1 until Int.MAX_VALUE) { "Wallpaper GL limit query fell back" }
        val directory = Files.createTempDirectory("stock-wallpaper-").toFile()
        val original = directory.resolve("original.png")
        val output = directory.resolve("cropped.png")
        val record = HostedWallpaperImage(0, WallpaperManager.FLAG_SYSTEM, original, output)
        try {
            record.cropHint.set(20, 10, 180, 90)
            record.mSampleSize = 2f
            record.mCropHints.put(WallpaperManager.ORIENTATION_PORTRAIT, Rect(40, 30, 100, 70))
            val relative = cropper.getRelativeCropHints(record)
            check(relative.valueAt(0) == Rect(10, 10, 40, 30))
            check(WallpaperCropper.getOriginalCropHints(record, listOf(relative.valueAt(0))) == listOf(Rect(40, 30, 100, 70)))
            record.cropHint.set(4, 0, 12, 8)
            record.mCropHints.clear()
            record.mSampleSize = 1f
            val source = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(0xffe02010.toInt())
                for (y in 0 until 8) for (x in 4 until 12) source.setPixel(x, y, 0xff255395.toInt())
                original.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { source.recycle() }
            cropper.generateCrop(record)
            check(original.isFile && output.isFile)
            val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                check(decoded.width == 8 && decoded.height == 8) {
                    "Wallpaper crop was ${decoded.width}x${decoded.height}, expected 8x8"
                }
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                check(pixels.isNotEmpty() && pixels.all { it == 0xff255395.toInt() })
            } finally { decoded.recycle() }
            // A failed replacement must clear stale output and all crop bookkeeping.
            original.writeText("not an image")
            record.mSampleSize = 3f
            record.cropHint.set(1, 1, 4, 4)
            record.mCropHints.put(WallpaperManager.ORIENTATION_PORTRAIT, Rect(1, 1, 4, 4))
            cropper.generateCrop(record)
            check(!output.exists() && record.cropHint.isEmpty && record.mCropHints.size() == 0)
            check(record.mSampleSize == 1f)
        } finally { directory.deleteRecursively() }
    }
}
