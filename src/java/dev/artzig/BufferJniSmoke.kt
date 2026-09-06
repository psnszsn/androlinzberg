package dev.artzig

import android.graphics.GraphicBuffer
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import java.io.File
import java.time.Duration
import com.android.window.flags.Flags as WindowFlags

/** Public stock buffer contracts, including pixels across independent native/Parcel owners. */
object BufferJniSmoke {
    @JvmStatic private external fun liveBuffers(): Long
    @JvmStatic private external fun pixel(buffer: HardwareBuffer, x: Int, y: Int): Int
    @JvmStatic private external fun nativeCopy(buffer: HardwareBuffer): HardwareBuffer
    @JvmStatic private external fun gpuWrite(buffer: HardwareBuffer): Int

    private inline fun <reified T : Throwable> fails(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return
        }
        error("Expected ${T::class.java.name}")
    }

    @JvmStatic fun main(args: Array<String>) {
        System.load(File(args.single()).absolutePath)
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
        HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 640, 480, 120, 0)
        val baseline = liveBuffers()
        imageQueues()
        val usage = HardwareBuffer.USAGE_CPU_READ_OFTEN or
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        check(HardwareBuffer.isSupported(16, 12, HardwareBuffer.RGBA_8888, 1, usage))
        check(!HardwareBuffer.isSupported(16, 12, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_PROTECTED_CONTENT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE))
        fails<IllegalArgumentException> { HardwareBuffer.create(0, 12, 1, 1, usage) }
        val original = HardwareBuffer.create(16, 12, HardwareBuffer.RGBA_8888, 1, usage)
        val buffers = mutableListOf(original)
        val graphic = checkNotNull(GraphicBuffer.createFromHardwareBuffer(original))
        try {
            val identity = original.id
            check(identity != 0L && original.width == 16 && original.height == 12)
            check(original.layers == 1 && original.format == HardwareBuffer.RGBA_8888)
            check(original.describeContents() == Parcelable.CONTENTS_FILE_DESCRIPTOR)
            val copy = nativeCopy(original).also { buffers.add(it) }
            check(copy.id == identity)
            val fromGraphic = HardwareBuffer.createFromGraphicBuffer(graphic).also { buffers.add(it) }
            check(fromGraphic.id == identity)
            val canvas = checkNotNull(graphic.lockCanvas(Rect(0, 0, 16, 12)))
            canvas.drawColor(0xff336699.toInt())
            graphic.unlockCanvasAndPost(canvas)
            val drawn = pixel(copy, 4, 3)
            check(drawn == 0xff336699.toInt()) { "Canvas pixel: ${drawn.toUInt().toString(16)}" }
            val gpuFence = SyncFence.adopt(gpuWrite(copy))
            val sharedFence = SyncFence(gpuFence)
            val duplicate = SyncFence.create(gpuFence.fdDup)
            val fenceParcel = Parcel.obtain()
            val restoredFence: SyncFence
            try {
                gpuFence.writeToParcel(fenceParcel, 0)
                fenceParcel.setDataPosition(0)
                restoredFence = SyncFence.CREATOR.createFromParcel(fenceParcel)
                check(fenceParcel.dataAvail() == 0)
            } finally { fenceParcel.recycle() }
            gpuFence.close()
            try {
                for (fence in listOf(sharedFence, duplicate, restoredFence)) {
                    check(fence.isValid)
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (!fence.await(Duration.ZERO) && System.nanoTime() < deadline) Thread.sleep(1)
                    check(fence.await(Duration.ZERO) && fence.signalTime >= 0)
                }
                check(pixel(copy, 4, 3) == 0xff19b36b.toInt())
            } finally {
                sharedFence.close()
                duplicate.close()
                restoredFence.close()
            }
            val parcel = Parcel.obtain()
            val restored: HardwareBuffer
            try {
                parcel.writeInt(0x1234)
                original.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                check(!original.isClosed)
                parcel.writeInt(0x5678)
                parcel.setDataPosition(0)
                check(parcel.readInt() == 0x1234)
                restored = HardwareBuffer.CREATOR.createFromParcel(parcel).also { buffers.add(it) }
                check(parcel.readInt() == 0x5678 && parcel.dataAvail() == 0)
            } finally { parcel.recycle() }
            check(restored.id == identity && restored.width == 16 && restored.height == 12)
            original.close()
            copy.close()
            fromGraphic.close()
            graphic.destroy()
            check(pixel(restored, 7, 8) == 0xff19b36b.toInt())
            check(original.isClosed && graphic.isDestroyed && graphic.lockCanvas() == null)
            fails<IllegalStateException> { original.width }
            val invalid = Parcel.obtain()
            try {
                fails<IllegalStateException> { original.writeToParcel(invalid, 0) }
                fails<IllegalStateException> { graphic.writeToParcel(invalid, 0) }
                fails<BadParcelableException> { HardwareBuffer.CREATOR.createFromParcel(invalid) }
            } finally { invalid.recycle() }
        } finally {
            graphic.destroy()
            buffers.forEach { it.close() }
        }
        check(liveBuffers() == baseline) { "Leaked graphics allocations" }
        snapshots(usage)
        fences()
        println("BufferJNI: stock ownership, GraphicBuffer Canvas pixels, Parcel, NDK conversions, fences and teardown passed")
    }

    private fun imageQueues() {
        val baseline = liveBuffers()
        val first = android.media.ImageReader.newInstance(32, 24, android.graphics.PixelFormat.RGBA_8888, 2)
        val second = android.media.ImageReader.newInstance(32, 24, android.graphics.PixelFormat.RGBA_8888, 2)
        val writer = android.media.ImageWriter.newInstance(first.surface, 2, android.graphics.PixelFormat.RGBA_8888)
        val transfer = android.media.ImageWriter.newInstance(second.surface, 2, android.graphics.PixelFormat.RGBA_8888)
        fun produce(timestamp: Long, rgba: Int) {
            val input = writer.dequeueInputImage()
            try {
                check(input.width == 32 && input.height == 24 && input.planes.size == 1)
                val plane = input.planes.single()
                for (y in 0 until input.height) for (x in 0 until input.width) {
                    plane.buffer.putInt(y * plane.rowStride + x * plane.pixelStride, rgba)
                }
                input.timestamp = timestamp
                writer.queueInputImage(input)
                fails<IllegalStateException> { input.timestamp }
            } finally { input.close() }
        }
        try {
            produce(100, 0xff808080.toInt())
            val image = checkNotNull(first.acquireNextImage())
            check(image.timestamp == 100L && image.planes.single().buffer.getInt(0) == 0xff808080.toInt())
            val bufferId = checkNotNull(image.hardwareBuffer).use { it.id }
            transfer.queueInputImage(image)
            fails<IllegalStateException> { image.timestamp }
            checkNotNull(second.acquireNextImage()).use { received ->
                check(received.timestamp == 100L && received.planes.single().buffer.getInt(0) == 0xff808080.toInt())
                checkNotNull(received.hardwareBuffer).use { check(it.id == bufferId) }
            }
            produce(200, 0xff112233.toInt())
            val held = checkNotNull(first.acquireNextImage())
            produce(300, 0xff445566.toInt())
            val heldSecond = checkNotNull(first.acquireNextImage())
            try { fails<IllegalStateException> { first.acquireNextImage() } }
            finally { held.close(); heldSecond.close() }
            produce(400, 0xff778899.toInt())
            produce(500, 0xffaabbcc.toInt())
            checkNotNull(first.acquireLatestImage()).use {
                check(it.timestamp == 500L && it.planes.single().buffer.getInt(0) == 0xffaabbcc.toInt())
            }
            check(first.acquireNextImage() == null)
        } finally {
            transfer.close(); writer.close(); second.close(); first.close()
        }
        val usage = HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        HardwareBuffer.create(32, 24, HardwareBuffer.RGBA_8888, 1, usage).use { buffer ->
            val graphic = checkNotNull(GraphicBuffer.createFromHardwareBuffer(buffer))
            try {
                val canvas = checkNotNull(graphic.lockCanvas(Rect(0, 0, 32, 24)))
                canvas.drawColor(0xff808080.toInt())
                graphic.unlockCanvasAndPost(canvas)
                val luma = com.android.internal.policy.TransitionAnimation.getBorderLuma(buffer,
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                check(kotlin.math.abs(luma - 128f / 255) < 0.005f) { "Transition border luma: $luma" }
            } finally { graphic.destroy() }
        }
        check(liveBuffers() == baseline) { "Image queues retained native buffer owners" }
        println("Image queues: writable planes, timestamps, transfer, acquire limits, latest image and transition luminance passed")
    }

    private fun snapshots(usage: Long) {
        val baseline = liveBuffers()
        val buffer = HardwareBuffer.create(16, 12, HardwareBuffer.RGBA_8888, 1, usage)
        val size = android.graphics.Point(160, 120)
        val insets = Rect(1, 2, 3, 4)
        val snapshot = android.window.TaskSnapshot(73L, 123L,
            android.content.ComponentName("test", "test.Activity"), buffer,
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
            2, android.view.Surface.ROTATION_90, size, insets, Rect(5, 6, 7, 8),
            true, true, android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM,
            0, true, true, android.content.res.Configuration.UI_MODE_NIGHT_YES)
        size.x = 1
        insets.left = 99
        check(snapshot.taskSize.x == 160 && snapshot.contentInsets.left == 1)
        snapshot.addReference(android.window.TaskSnapshot.REFERENCE_CACHE)
        snapshot.addReference(android.window.TaskSnapshot.REFERENCE_WRITE_TO_PARCEL)
        val parcel = Parcel.obtain()
        var restored: android.window.TaskSnapshot? = null
        try {
            snapshot.writeToParcel(parcel, 0)
            check(!buffer.isClosed)
            parcel.writeInt(0x1234)
            parcel.setDataPosition(0)
            val copy = android.window.TaskSnapshot.CREATOR.createFromParcel(parcel)
            restored = copy
            check(parcel.readInt() == 0x1234 && parcel.dataAvail() == 0)
            check(copy.id == 73L && copy.topActivityComponent == snapshot.topActivityComponent)
            check(copy.taskSize == snapshot.taskSize && copy.contentInsets == snapshot.contentInsets &&
                copy.letterboxInsets == snapshot.letterboxInsets)
            check(copy.rotation == android.view.Surface.ROTATION_90 && copy.orientation == 2 &&
                copy.isLowResolution && copy.isRealSnapshot && copy.isTranslucent && copy.hasImeSurface())
            check(copy.windowingMode == snapshot.windowingMode && copy.uiMode == snapshot.uiMode)
            val imported = checkNotNull(copy.hardwareBuffer)
            check(imported.id == buffer.id)
            snapshot.removeReference(android.window.TaskSnapshot.REFERENCE_CACHE)
            check(buffer.isClosed == WindowFlags.releaseSnapshotAggressively())
            buffer.close()
            check(!imported.isClosed && imported.width == 16)
            val local = object : android.app.TaskStackListener() {}.apply { setIsLocal() }
            local.onTaskSnapshotChanged(73, copy)
            check(!imported.isClosed)
            object : android.app.TaskStackListener() {}.onTaskSnapshotChanged(73, copy)
            check(imported.isClosed)
        } finally {
            parcel.recycle()
            restored?.hardwareBuffer?.close()
            buffer.close()
        }
        check(liveBuffers() == baseline) { "Leaked task snapshot buffer" }
        println("TaskSnapshot: stock parcel fields, buffer references and local/remote listener ownership passed")
    }

    private fun fences() {
        // Pinned SyncFence distinguishes an empty pointer from a libui NO_FENCE.
        SyncFence.createEmpty().use { empty ->
            check(!empty.isValid && empty.signalTime == SyncFence.SIGNAL_TIME_INVALID)
            check(!empty.await(Duration.ZERO))
            fails<IllegalStateException> { empty.fdDup }
        }
        SyncFence.adopt(-1).use { none ->
            check(!none.isValid && none.await(Duration.ZERO))
            SyncFence(none).use { copy ->
                none.close()
                check(copy.awaitForever())
                val parcel = Parcel.obtain()
                try {
                    copy.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    SyncFence.CREATOR.createFromParcel(parcel).use { restored ->
                        check(!restored.isValid && restored.signalTime == SyncFence.SIGNAL_TIME_INVALID)
                        check(parcel.dataAvail() == 0)
                    }
                } finally { parcel.recycle() }
            }
        }
    }
}
