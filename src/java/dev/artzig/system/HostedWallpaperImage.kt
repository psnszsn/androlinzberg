package dev.artzig.system

import android.graphics.Rect
import android.util.SparseArray
import java.io.File

/**
 * Image/crop state used by AOSP WallpaperCropper. WallpaperData also owns Android
 * wallpaper-service connections and display engines, which the hosted session
 * does not provide. The cropper retains its stock geometry, decoding and cleanup;
 * the host owns the input/output files and their directory.
 */
class HostedWallpaperImage(
    @JvmField val userId: Int,
    @JvmField var mWhich: Int,
    private val original: File,
    private val cropped: File,
) {
    @JvmField val cropHint = Rect()
    @JvmField var mCropHints = SparseArray<Rect>()
    @JvmField var mSampleSize = 1f

    fun getWallpaperFile(): File = original
    fun getCropFile(): File = cropped
    fun getWallpaperDirectory(): File = checkNotNull(original.absoluteFile.parentFile)
}
