package dev.artzig

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Container discovery and packet delivery through the original extractor plugins. */
internal object StockMediaContainerSmoke {
    val fixtures = mapOf(
        "thumbnail.mp4" to "video/avc",
        "thumbnail.webm" to "video/x-vnd.on2.vp9",
        // AOSP's FLAC extractor decodes blocks and exposes PCM tracks.
        "tone.flac" to "audio/raw",
        "tone.ogg" to "audio/vorbis",
        "tone.opus" to "audio/opus",
        "tone.ts" to "audio/mp4a-latm",
        "tone.mpg" to "audio/mpeg-L2",
        "silence.amr" to "audio/3gpp",
    )

    fun run() {
        val directory = File(checkNotNull(System.getenv("AZK_TEST_THUMBNAIL"))).parentFile
        for ((name, mime) in fixtures) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(directory, name).absolutePath)
                check(extractor.trackCount == 1) { "$name: ${extractor.trackCount} tracks" }
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == mime) { "$name: $format" }
                extractor.selectTrack(0)
                val packet = ByteBuffer.allocate(1024 * 1024)
                var packets = 0
                var bytes = 0
                var nonzero = false
                do {
                    packet.clear()
                    val size = extractor.readSampleData(packet, 0)
                    if (size < 0) break
                    check(size > 0 && extractor.sampleTrackIndex == 0) { "$name: invalid packet" }
                    bytes += size
                    if (name == "tone.flac") {
                        for (index in 0 until size) nonzero = nonzero || packet[index] != 0.toByte()
                    }
                    check(++packets < 1000) { "$name: extractor did not reach EOF" }
                } while (extractor.advance())
                check(packets > 0) { "$name: no packets" }
                check(!extractor.advance()) { "$name: advance after EOF" }
                if (name == "tone.flac") check(bytes == 24_000 && nonzero) {
                    "$name: expected 12000 non-silent mono PCM16 samples, got $bytes bytes"
                }
            } finally { extractor.release() }
        }
        println("stock-media-containers: PASS ${fixtures.size} original container extractors and packet delivery")
    }
}
