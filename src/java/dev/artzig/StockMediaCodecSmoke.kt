package dev.artzig

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

internal object StockMediaCodecSmoke {
    fun run() {
        check(runCatching { MediaCodec.createByCodecName("dev.artzig.missing.codec") }.exceptionOrNull() is IllegalArgumentException)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2), null, null, 0)
            check(runCatching { codec.dequeueInputBuffer(0) }.exceptionOrNull() is IllegalStateException)
            // Native reset/stop clear asynchronous mode, even though the Java callback remains.
            val callback = object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = Unit
            }
            codec.setCallback(callback)
            codec.reset()
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2), null, null, 0)
            codec.start()
            val cached = codec.inputBuffers
            val index = codec.dequeueInputBuffer(1_000_000)
            check(index >= 0)
            val first = checkNotNull(codec.getInputBuffer(index))
            check(runCatching { cached[index].get(0) }.exceptionOrNull() is IllegalStateException)
            val second = checkNotNull(codec.getInputBuffer(index))
            check(runCatching { first.get(0) }.exceptionOrNull() is IllegalStateException)
            check(!second.isReadOnly && second.order() == ByteOrder.nativeOrder())
            // MPEG1 Layer III, 128kbps/44.1kHz stereo. Zero side/main data encodes silence.
            val frame = ByteArray(417).also {
                byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it)
            }
            second.put(frame)
            codec.queueInputBuffer(index, 0, frame.size, 12345L, 0)
            check(runCatching { second.get(0) }.exceptionOrNull() is IllegalStateException)
            val info = MediaCodec.BufferInfo()
            var output = MediaCodec.INFO_TRY_AGAIN_LATER
            val deadline = System.nanoTime() + 2_000_000_000L
            while (output < 0 && System.nanoTime() < deadline) {
                output = codec.dequeueOutputBuffer(info, 1000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                    check(codec.outputFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW)
            }
            check(output >= 0 && info.size > 0) { "No decoded PCM output: $output" }
            val bytes = checkNotNull(codec.getOutputBuffer(output))
            check(bytes.isReadOnly && bytes.order() == ByteOrder.nativeOrder())
            check(bytes.remaining() == info.size && info.presentationTimeUs == 12345L)
            while (bytes.hasRemaining()) check(bytes.get() == 0.toByte())
            codec.releaseOutputBuffer(output, false)
            check(runCatching { bytes.get(0) }.exceptionOrNull() is IllegalStateException)
            codec.flush()
            check(codec.dequeueInputBuffer(1_000_000) >= 0)
            codec.stop()
            check(runCatching { codec.inputFormat }.exceptionOrNull() is IllegalStateException)
            codec.setCallback(callback)
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2), null, null, 0)
            codec.start()
            codec.stop()
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2), null, null, 0)
            codec.start()
            check(codec.dequeueInputBuffer(1_000_000) >= 0)
            codec.stop()
        } finally { codec.release() }
        codec.release()
        println("stock-mediacodec: PASS decoder lifecycle, independent JNI views, read-only PCM output and invalidation")
    }
}
