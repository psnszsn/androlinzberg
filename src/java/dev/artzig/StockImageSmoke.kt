package dev.artzig

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.DataSpace
import android.hardware.SyncFence
import android.media.Image

/** A producer fixture exercises Image's stock base contract without a camera/codec backend. */
private class FixtureImage : Image() {
    init { mIsImageValid = true }
    override fun getFormat() = ImageFormat.PRIVATE
    override fun getWidth() = 80
    override fun getHeight() = 40
    override fun getTimestamp() = 123L
    override fun getTransform() = 0
    override fun getScalingMode() = 0
    override fun getPlanes(): Array<Image.Plane> = emptyArray()
    override fun close() { mIsImageValid = false }
}

internal object StockImageSmoke {
    fun run() {
        val image = FixtureImage()
        val fence = SyncFence.createEmpty()
        try {
            check(image.cropRect == Rect(0, 0, 80, 40))
            val crop = Rect(-5, 6, 100, 50)
            image.cropRect = crop
            check(crop == Rect(-5, 6, 100, 50) && image.cropRect == Rect(0, 6, 80, 40))
            crop.setEmpty()
            val returned = image.cropRect
            returned.setEmpty()
            check(image.cropRect == Rect(0, 6, 80, 40))
            image.cropRect = Rect(90, 50, 100, 60)
            check(image.cropRect.isEmpty)
            image.cropRect = null
            check(image.cropRect == Rect(0, 0, 80, 40))
            check(image.dataSpace == DataSpace.DATASPACE_UNKNOWN)
            image.dataSpace = DataSpace.DATASPACE_SRGB
            check(image.dataSpace == DataSpace.DATASPACE_SRGB)
            check(image.hardwareBuffer == null && !image.isAttachable && image.planeCount == -1)
            image.setTimestamp(456L)
            check(image.timestamp == 123L) // The base setter is deliberately a no-op.
            image.setFence(fence)
            image.fence.use { check(!it.isValid) }
            image.close()
            val closedOperations: List<() -> Any?> = listOf(
                { image.cropRect }, { image.cropRect = Rect() }, { image.dataSpace },
                { image.dataSpace = DataSpace.DATASPACE_UNKNOWN }, { image.hardwareBuffer },
                { image.isAttachable }, { image.setTimestamp(0) }, { image.setFence(fence) },
            )
            for (operation in closedOperations) {
                val failure = runCatching(operation).exceptionOrNull()
                check(failure is IllegalStateException && failure.message == "Image is already closed")
            }
            // These two base defaults do not consult validity in the pinned implementation.
            check(image.planeCount == -1)
            image.fence.use { check(!it.isValid) }
        } finally { image.close(); fence.close() }
        formats()
        System.err.println("stock-image: crop copies/clipping, dataspace, closed-image contracts and native public/HAL format mappings passed")
    }

    private fun formats() {
        val utils = Class.forName("android.media.PublicFormatUtils")
        val halFormat = utils.getDeclaredMethod("getHalFormat", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val halDataspace = utils.getDeclaredMethod("getHalDataspace", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val publicFormat = utils.getDeclaredMethod("getPublicFormat", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        val mappings = listOf(
            Triple(ImageFormat.JPEG, 0x21, DataSpace.DATASPACE_JFIF),
            Triple(ImageFormat.DEPTH_POINT_CLOUD, 0x21, DataSpace.DATASPACE_DEPTH),
            Triple(ImageFormat.DEPTH_JPEG, 0x21, DataSpace.DATASPACE_DYNAMIC_DEPTH),
            Triple(ImageFormat.HEIC, 0x21, DataSpace.DATASPACE_HEIF),
            Triple(ImageFormat.HEIC_ULTRAHDR, 0x21, DataSpace.DATASPACE_HEIF_ULTRAHDR),
            Triple(ImageFormat.JPEG_R, 0x21, DataSpace.DATASPACE_JPEG_R),
            Triple(ImageFormat.DEPTH16, 0x20363159, DataSpace.DATASPACE_DEPTH),
            Triple(ImageFormat.RAW_DEPTH, 0x20, DataSpace.DATASPACE_DEPTH),
            Triple(ImageFormat.RAW_DEPTH10, 0x25, DataSpace.DATASPACE_DEPTH),
            Triple(ImageFormat.RAW_SENSOR, 0x20, 1 /* Dataspace.ARBITRARY */),
            Triple(ImageFormat.YUV_420_888, 0x23, DataSpace.DATASPACE_JFIF),
            Triple(PixelFormat.RGBA_8888, 1, DataSpace.DATASPACE_UNKNOWN),
        )
        for ((public, hal, dataspace) in mappings) {
            check(halFormat.invoke(null, public) == hal) { "HAL format for $public" }
            check(halDataspace.invoke(null, public) == dataspace) { "dataspace for $public" }
            check(publicFormat.invoke(null, hal, dataspace) == public) { "round trip for $public" }
        }
        check(publicFormat.invoke(null, 0x21, 0) == ImageFormat.JPEG)
        check(publicFormat.invoke(null, 5 /* BGRA_8888 */, 0) == ImageFormat.UNKNOWN)
        check(publicFormat.invoke(null, 0x7fffffff, 0) == ImageFormat.UNKNOWN)
        check(halFormat.invoke(null, 0x7fffffff) == 0x7fffffff)
        check(halDataspace.invoke(null, 0x7fffffff) == DataSpace.DATASPACE_UNKNOWN)
    }
}
