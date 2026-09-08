package dev.artzig

import android.hardware.tv.input.V1_0.Constants as TvInputConstants
import android.media.MediaMetrics
import android.os.Parcel
import java.nio.ByteBuffer

/** Stock metrics encoding and its original native service-absence result. */
internal object StockMediaMetricsSmoke {
    private external fun nativeBundle(): android.os.PersistableBundle

    fun run() {
        val native = nativeBundle()
        check(native.getInt("gain") == -2 && native.getString("client") == "native focus")
        check(native.getLongArray("times")!!.contentEquals(longArrayOf(17, 1L shl 40)))
        check(native.getPersistableBundle("nested")!!.getBoolean("active"))
        val item = MediaMetrics.Item("audio.focus.test", 123, 456, 789L, 1)
            .putInt("gain", 1)
            .putLong("duration", Long.MAX_VALUE)
            .putDouble("ratio", 1.25)
            .putString("client", "café " + "x".repeat(3000))
        val encoded = item.toBundle()
        check(encoded.getString(MediaMetrics.Item.BUNDLE_KEY) == "audio.focus.test")
        check(encoded.getInt(MediaMetrics.Item.BUNDLE_PID) == 123)
        check(encoded.getInt(MediaMetrics.Item.BUNDLE_UID) == 456)
        check(encoded.getLong(MediaMetrics.Item.BUNDLE_TIMESTAMP) == 789L)
        check(encoded.getInt(MediaMetrics.Item.BUNDLE_PROPERTY_COUNT) == 4)
        check(encoded.getInt("gain") == 1 && encoded.getLong("duration") == Long.MAX_VALUE)
        check(encoded.getDouble("ratio") == 1.25)
        check(encoded.getString("client") == "café " + "x".repeat(3000))
        // Recording must expose the unavailable service, rather than claim success.
        check(!item.record())
        check(item.toBundle().getString("client") == encoded.getString("client"))
        item.clear()
        val cleared = item.toBundle()
        check(cleared.getInt(MediaMetrics.Item.BUNDLE_PROPERTY_COUNT) == 0)
        check(cleared.getLong(MediaMetrics.Item.BUNDLE_TIMESTAMP) == 0L)
        check(cleared.getInt(MediaMetrics.Item.BUNDLE_PID) == 123)
        check(cleared.getInt(MediaMetrics.Item.BUNDLE_UID) == 456)
        val submit = MediaMetrics::class.java.getDeclaredMethod(
            "native_submit_bytebuffer", ByteBuffer::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        check(submit.invoke(null, ByteBuffer.allocate(8), 8) == -22)
        check(submit.invoke(null, ByteBuffer.allocateDirect(8), -1) == -22)
        check(runCatching { MediaMetrics.Item("x".repeat(65535)) }.exceptionOrNull() is IllegalArgumentException)
        // Structured CAS transport preserves the complete subsample sizes.
        val sample = android.hardware.cas.SubSample().apply {
            numBytesOfClearData = 11
            numBytesOfEncryptedData = 8192
        }
        val parcel = Parcel.obtain()
        try {
            sample.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = android.hardware.cas.SubSample.CREATOR.createFromParcel(parcel)
            check(copy.numBytesOfClearData == 11 && copy.numBytesOfEncryptedData == 8192)
        } finally { parcel.recycle() }
        check(TvInputConstants.TV_INPUT_TYPE_HDMI == 9)
        println("stock-media-metrics: PASS typed encoding, growth, native errors and CAS transport")
    }
}
