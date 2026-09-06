package dev.artzig.window

import android.graphics.ImageFormat
import android.util.Size
import android.view.Surface

/**
 * SurfaceUtils.getSurfaceSize/getSurfaceFormat/getSurfaceDataspace, backed by
 * its unchanged libgui query implementations. The stock Java class also depends
 * on camera stream validation; image queues only need the surface operations.
 */
object HostSurfaceInfo {
    @JvmStatic private external fun nativeDetectSurfaceType(surface: Surface): Int
    @JvmStatic private external fun nativeDetectSurfaceDataspace(surface: Surface): Int
    @JvmStatic private external fun nativeDetectSurfaceDimens(surface: Surface, dimensions: IntArray): Int
    @JvmStatic private external fun nativeDetectSurfaceUsageFlags(surface: Surface): Long
    @JvmStatic private external fun nativeGetSurfaceId(surface: Surface): Long

    @JvmStatic fun getSurfaceSize(surface: Surface): Size {
        val dimensions = IntArray(2)
        checkAbandoned(nativeDetectSurfaceDimens(surface, dimensions))
        return Size(dimensions[0], dimensions[1])
    }

    @JvmStatic fun getSurfaceFormat(surface: Surface): Int {
        val format = nativeDetectSurfaceType(surface)
        checkAbandoned(format)
        // Preserve SurfaceUtils' legacy RGBA_8888..BGRA_8888 -> PRIVATE mapping.
        return if (format in 1..5) ImageFormat.PRIVATE else format
    }

    @JvmStatic fun getSurfaceDataspace(surface: Surface): Int =
        nativeDetectSurfaceDataspace(surface).also(::checkAbandoned)

    private fun checkAbandoned(result: Int) {
        require(result != -22) { "Surface was abandoned" }
    }
}
