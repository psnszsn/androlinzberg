package dev.artzig

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Full-chain test: mp3 file -> MediaCodec (ffmpeg) -> AudioTrack (PipeWire). */
public final class CodecProbe {
    companion object {
        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            var path: String? = System.getenv("AZK_TEST_MP3")
            if (path == null) path = "/tmp/azk-test.mp3"
            var data: ByteArray
            FileInputStream(path).use { `in`: FileInputStream ->
                data = `in`.readAllBytes()
            }
            System.out.println("mp3 bytes=" + data.size)

            val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            codec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2),
                null,
                null,
                0,
            )
            codec.start()

            val info: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
            var track: AudioTrack? = null
            var totalSamples: Long = 0
            var off: Int = 0
            var inputDone: Boolean = false
            var outputDone: Boolean = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx: Int = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val frame: Int = nextFrameLength(data, off)
                        if (frame <= 0 || off + frame > data.size) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val `in`: ByteBuffer = checkNotNull(codec.getInputBuffer(idx))
                            `in`.put(data, off, frame)
                            codec.queueInputBuffer(idx, 0, frame, off.toLong(), 0)
                            off += frame
                        }
                    }
                }
                val idx: Int = codec.dequeueOutputBuffer(info, 10000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f: MediaFormat = codec.getOutputFormat()
                    val rate: Int = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch: Int = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    System.out.println("output format: " + rate + " Hz, " + ch + " ch")
                    track =
                        AudioTrack
                            .Builder()
                            .setAudioFormat(
                                AudioFormat
                                    .Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(
                                        if (ch == 1) {
                                            AudioFormat.CHANNEL_OUT_MONO
                                        } else {
                                            AudioFormat.CHANNEL_OUT_STEREO
                                        },
                                    ).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build(),
                            ).setBufferSizeInBytes(256 * 1024)
                            .build()
                    track.play()
                } else if (idx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (info.size > 0 && track != null) {
                        val out: ByteBuffer = checkNotNull(codec.getOutputBuffer(idx))
                        totalSamples += info.size / 4
                        while (out.hasRemaining()) {
                            val n: Int = track.write(out, out.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            if (n <= 0) Thread.sleep(10)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
            System.out.println(
                "decoded samples=" + totalSamples + " (" +
                    (totalSamples / 44100.0) + " s) " + (if (totalSamples > 40000) "PASS" else "FAIL"),
            )
            // let the tail drain
            Thread.sleep(400)
            if (track != null) track.release()
            codec.release()
            System.out.println("codec probe done")
        }

/** Minimal MPEG-1 Layer III frame-length parser. */
        @JvmStatic private fun nextFrameLength(
            d: ByteArray,
            off: Int,
        ): Int {
            var cursor = off
            while (cursor + 4 < d.size &&
                !(((d[cursor]).toInt() and 0xFF) == 0xFF && ((d[cursor + 1]).toInt() and 0xE0) == 0xE0)
            ) {
                cursor++
            }
            if (cursor + 4 >= d.size) return -1
            val bitrateIdx: Int = ((d[cursor + 2]).toInt() shr 4) and 0xF
            val srIdx: Int = ((d[cursor + 2]).toInt() shr 2) and 0x3
            val padding: Int = ((d[cursor + 2]).toInt() shr 1) and 0x1
            val bitrates: IntArray = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            val rates: IntArray = intArrayOf(44100, 48000, 32000, 0)
            if (bitrates[bitrateIdx] == 0 || rates[srIdx] == 0) return -1
            return 144 * bitrates[bitrateIdx] * 1000 / rates[srIdx] + padding
        }
    }
}
