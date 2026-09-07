package dev.artzig

import android.graphics.Rect
import android.hardware.ICameraService
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.impl.CameraMetadataNative
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.util.Rational
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stock camera2 Java marshaling and native metadata, independent of capture hardware. */
internal object StockCameraMetadataSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
        return failure
    }

    private fun valuesAndCopies() {
        val metadata = CameraMetadataNative()
        check(metadata.isEmpty && metadata.entryCount == 0)
        check(metadata.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == null)
        metadata.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 12_345_678L)
        metadata.set(CaptureRequest.SENSOR_SENSITIVITY, 320)
        metadata.set(CaptureRequest.LENS_FOCUS_DISTANCE, 1.25f)
        metadata.set(CaptureRequest.BLACK_LEVEL_LOCK, true)
        metadata.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val transform = ColorSpaceTransform(Array(9) { i ->
            if (i % 4 == 0) Rational(3, 2) else Rational(0, 1)
        })
        metadata.set(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1, transform)
        metadata.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(2, 4, 640, 480))
        check(metadata.entryCount == 7)
        check(metadata.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == 12_345_678L)
        check(metadata.get(CaptureRequest.SENSOR_SENSITIVITY) == 320)
        check(metadata.get(CaptureRequest.LENS_FOCUS_DISTANCE) == 1.25f)
        check(metadata.get(CaptureRequest.BLACK_LEVEL_LOCK) == true)
        check(metadata.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_ON)
        check(metadata.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1) == transform)
        check(metadata.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) == Rect(2, 4, 640, 480))

        val exposureTag = CameraMetadataNative.getTag("android.sensor.exposureTime")
        check(CameraMetadataNative.getNativeType(exposureTag, Long.MAX_VALUE) == CameraMetadataNative.TYPE_INT64)
        val raw = checkNotNull(metadata.readValues(exposureTag))
        check(ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).long == 12_345_678L)
        raw.fill(0)
        check(metadata.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == 12_345_678L)
        rejects<IllegalArgumentException> { metadata.writeValues(exposureTag, byteArrayOf(1)) }
        rejects<IllegalArgumentException> { CameraMetadataNative.getTag("dev.artzig.unknown") }
        check(metadata.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == 12_345_678L)

        val copy = CameraMetadataNative(metadata)
        metadata.set(CaptureRequest.SENSOR_SENSITIVITY, 800)
        check(copy.get(CaptureRequest.SENSOR_SENSITIVITY) == 320)
        val additions = CameraMetadataNative().apply {
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, 99L)
            set(CaptureRequest.JPEG_ORIENTATION, 90)
        }
        CameraMetadataNative.update(copy, additions)
        check(copy.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == 99L)
        check(copy.get(CaptureRequest.SENSOR_SENSITIVITY) == 320)
        check(copy.get(CaptureRequest.JPEG_ORIENTATION) == 90)
        val moved = CameraMetadataNative.move(copy)
        check(copy.isEmpty && moved.entryCount == 8)
        moved.swap(copy)
        check(moved.isEmpty && copy.entryCount == 8)
        copy.writeValues(exposureTag, null)
        check(copy.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == null && copy.entryCount == 7)

        val characteristics = CameraCharacteristics(metadata)
        check(metadata.isEmpty)
        check(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) == Rect(2, 4, 640, 480))
        check(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1) == transform)
    }

    private fun parceling() {
        val scoresTag = CameraMetadataNative.getTag("android.statistics.faceScores")
        for (size in listOf(13, 128 * 1024)) {
            val metadata = CameraMetadataNative()
            val bytes = ByteArray(size) { (it % 101).toByte() }
            metadata.writeValues(scoresTag, bytes)
            bytes.fill(0)
            val expected = ByteArray(size) { (it % 101).toByte() }
            check(metadata.readValues(scoresTag).contentEquals(expected))
            val parcel = Parcel.obtain()
            val copy = try {
                metadata.writeToParcel(parcel, 0)
                check(parcel.hasFileDescriptors() == (size > 16 * 1024))
                parcel.writeInt(42)
                parcel.setDataPosition(0)
                CameraMetadataNative.CREATOR.createFromParcel(parcel).also {
                    check(parcel.readInt() == 42 && parcel.dataAvail() == 0)
                }
            } finally { parcel.recycle() }
            metadata.writeValues(scoresTag, null)
            check(metadata.isEmpty && copy.readValues(scoresTag).contentEquals(expected))
            val malformed = Parcel.obtain()
            try {
                malformed.writeInt(1) // Less than the required metadata alignment.
                malformed.setDataPosition(0)
                rejects<IllegalStateException> { copy.readFromParcel(malformed) }
                check(copy.readValues(scoresTag).contentEquals(expected))
                malformed.setDataPosition(0)
                malformed.writeInt(0) // Stock null-metadata representation clears contents.
                malformed.setDataPosition(0)
                copy.readFromParcel(malformed)
                check(copy.isEmpty)
            } finally { malformed.recycle() }
        }
    }

    private class VendorService : Binder() {
        enum class Mode { DESCRIPTOR, CACHE, DISCONNECTED, CACHE_DISCONNECTED, DENIED }
        var mode = Mode.DESCRIPTOR
        val calls = ArrayList<String>()
        private fun transaction(name: String): Int = ICameraService.Stub::class.java
            .getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }.getInt(null)
        private val descriptorCall = transaction("getCameraVendorTagDescriptor")
        private val cacheCall = transaction("getCameraVendorTagCache")

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != descriptorCall && code != cacheCall) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("android.hardware.ICameraService")
            data.enforceNoDataAvail()
            val out = checkNotNull(reply)
            calls.add(if (code == descriptorCall) "descriptor" else "cache")
            val error = when {
                mode == Mode.DENIED -> ICameraService.ERROR_PERMISSION_DENIED
                mode == Mode.DISCONNECTED ||
                    (code == cacheCall && mode == Mode.CACHE_DISCONNECTED) -> ICameraService.ERROR_DISCONNECTED
                else -> 0
            }
            if (error != 0) {
                out.writeException(ServiceSpecificException(error, "fixture service"))
                return true
            }
            out.writeNoException()
            out.writeInt(1) // Non-null native Parcelable result.
            if (code == descriptorCall) {
                if (mode == Mode.DESCRIPTOR) writeDescriptor(out) else out.writeInt(0)
            } else {
                out.writeInt(1) // One vendor in the cache.
                out.writeLong(VENDOR_ID)
                out.writeInt(1)
                writeDescriptor(out)
            }
            return true
        }

        private fun writeDescriptor(out: Parcel) {
            // Verbatim VendorTagDescriptor::writeToParcel layout. The stock Java
            // parcelable is deliberately opaque; native camera clients parse this payload.
            out.writeInt(1)
            out.writeInt(VENDOR_TAG)
            out.writeInt(CameraMetadataNative.TYPE_INT32)
            out.writeString8("gain")
            out.writeInt(0)
            out.writeInt(1)
            out.writeString8("dev.artzig.camera")
        }
    }

    private fun vendorDiscovery() {
        @Suppress("UNCHECKED_CAST")
        val services = ServiceManager::class.java.getDeclaredField("sCache")
            .apply { isAccessible = true }.get(null) as MutableMap<String, IBinder>
        val previous = services.remove("media.camera")
        val service = VendorService()
        try {
            val missing = rejects<ServiceSpecificException> { CameraMetadataNative.setupGlobalVendorTagDescriptor() }
            check(missing.errorCode == ICameraService.ERROR_DISCONNECTED)
            services["media.camera"] = service
            CameraMetadataNative.setupGlobalVendorTagDescriptor()
            check(service.calls == listOf("descriptor"))
            check(CameraMetadataNative.getTag("dev.artzig.camera.gain") == VENDOR_TAG)
            check(CameraMetadataNative.getNativeType(VENDOR_TAG, Long.MAX_VALUE) == CameraMetadataNative.TYPE_INT32)
            val global = CameraMetadataNative()
            val key = CaptureRequest.Key("dev.artzig.camera.gain", Int::class.javaObjectType)
            // Pinned JNI resolves untagged local keys through the per-vendor cache,
            // while the legacy global descriptor supports numeric-tag access.
            rejects<IllegalArgumentException> { global.set(key, 37) }
            global.writeValues(VENDOR_TAG, ByteBuffer.allocate(4)
                .order(ByteOrder.nativeOrder()).putInt(37).array())
            check(ByteBuffer.wrap(global.readValues(VENDOR_TAG))
                .order(ByteOrder.nativeOrder()).int == 37)

            service.mode = VendorService.Mode.DENIED
            val denied = rejects<ServiceSpecificException> { CameraMetadataNative.setupGlobalVendorTagDescriptor() }
            check(denied.errorCode == ICameraService.ERROR_PERMISSION_DENIED)
            rejects<IllegalArgumentException> { CameraMetadataNative.getTag("dev.artzig.camera.gain") }

            service.mode = VendorService.Mode.CACHE
            service.calls.clear()
            CameraMetadataNative.setupGlobalVendorTagDescriptor()
            check(service.calls == listOf("descriptor", "cache"))
            check(CameraMetadataNative.getTag("dev.artzig.camera.gain", VENDOR_ID) == VENDOR_TAG)
            val cached = CameraMetadataNative().apply { setVendorId(VENDOR_ID) }
            val vendorKey = CaptureRequest.Key("dev.artzig.camera.gain", Int::class.javaObjectType, VENDOR_ID)
            cached.set(vendorKey, 73)
            check(cached.get(vendorKey) == 73)
            check(cached.getAllVendorKeys(CameraCharacteristics.Key::class.java)
                .map { it.name } == listOf("dev.artzig.camera.gain"))
        } finally {
            // Clear both stock global caches through the service's disconnected responses.
            try {
                services["media.camera"] = service
                service.mode = VendorService.Mode.CACHE_DISCONNECTED
                CameraMetadataNative.setupGlobalVendorTagDescriptor()
                service.mode = VendorService.Mode.DISCONNECTED
                CameraMetadataNative.setupGlobalVendorTagDescriptor()
            } finally {
                if (previous == null) services.remove("media.camera") else services["media.camera"] = previous
            }
        }
    }

    fun run() {
        valuesAndCopies()
        parceling()
        vendorDiscovery()
        println("stock camera metadata values, parceling and vendor Binder contracts: PASS")
    }

    private const val VENDOR_ID = 42L
    private const val VENDOR_TAG = -2147418112 // 0x80010000, in the vendor tag range.
}
