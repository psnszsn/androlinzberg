package dev.artzig

import android.media.MediaFormat
import java.nio.ByteBuffer

/** Format ownership at the existing codec boundary.
 * Source: MediaCodec.getInputFormat/getOutputFormat and libstagefright MediaCodec's
 * kWhatGetInputFormat/kWhatGetOutputFormat handling. The hosted decoder keeps compressed
 * input metadata separately from decoded output and exposes snapshots only while configured
 * or executing. This does not change codec selection or the native decoding implementation. */
class HostedCodecFormats {
    private var input: MediaFormat? = null
    private var output: MediaFormat? = null

    @Synchronized fun configured(inputFormat: MediaFormat, outputFormat: MediaFormat) {
        input = snapshot(inputFormat)
        output = snapshot(outputFormat)
    }

    @Synchronized fun outputChanged(format: MediaFormat) {
        check(input != null) { "Codec is not configured" }
        output = snapshot(format)
    }

    @Synchronized fun clear() { input = null; output = null }
    @Synchronized fun inputFormat(): MediaFormat = snapshot(checkNotNull(input) { "Codec is not configured" })
    @Synchronized fun outputFormat(): MediaFormat = snapshot(checkNotNull(output) { "Codec is not configured" })

    private fun snapshot(format: MediaFormat): MediaFormat = MediaFormat(format).apply {
        // Native getFormat creates independent ByteBuffers when converting AMessage to Java.
        for (key in format.keys) {
            if (format.getValueTypeForKey(key) == MediaFormat.TYPE_BYTE_BUFFER) {
                val source = format.getByteBuffer(key)?.duplicate() ?: continue
                val bytes = ByteArray(source.remaining())
                source.get(bytes)
                setByteBuffer(key, ByteBuffer.wrap(bytes))
            }
        }
    }
}
