package dev.artzig

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ServiceManager
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Public metadata and thumbnail APIs through the original remote retriever. */
internal object StockMediaMetadataRetrieverSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Any?) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
    }

    fun run() {
        checkNotNull(ServiceManager.checkService("media.player"))
        val bytes = StockMediaPlayerSmoke.mp3()
        val file = Files.createTempFile("stock-retriever-", ".mp3").toFile()
        // An explicit FD slice must hide bytes before and after the media.
        file.writeBytes(ByteArray(17) + bytes + ByteArray(13))
        val retriever = MediaMetadataRetriever()
        fun audioMetadata() {
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "audio/mpeg")
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() in 1800..2300)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null)
            check(retriever.getFrameAtTime() == null)
        }
        try {
            rejects<IllegalArgumentException> { retriever.setDataSource(null as String?) }
            rejects<IllegalArgumentException> { retriever.getFrameAtTime(0, -1) }
            rejects<IllegalArgumentException> { retriever.getScaledFrameAtTime(0, 0, 0, 20) }
            FileInputStream(file).use { input ->
                retriever.setDataSource(input.fd, 17, bytes.size.toLong())
                audioMetadata()
                check(input.fd.valid())
            }
            val reads = AtomicInteger()
            val source = object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    reads.incrementAndGet()
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
                override fun close() = Unit
            }
            retriever.setDataSource(source)
            audioMetadata()
            check(reads.get() > 0) { "Remote retriever did not read the callback source" }

            retriever.setDataSource(checkNotNull(System.getenv("AZK_TEST_THUMBNAIL")))
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == "128")
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) == "96")
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) == "Stock thumbnail")
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, params))
            try {
                check(bitmap.width == 128 && bitmap.height == 96 && params.actualConfig == Bitmap.Config.ARGB_8888)
                val pixel = bitmap.getPixel(64, 48)
                check(Color.red(pixel) > 230 && Color.green(pixel) < 25 && Color.blue(pixel) < 25) {
                    "Unexpected thumbnail pixel: ${Integer.toHexString(pixel)}"
                }
            } finally { bitmap.recycle() }
            val scaled = checkNotNull(retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 48))
            try { check(scaled.width == 64 && scaled.height == 48) } finally { scaled.recycle() }
        } finally {
            retriever.release()
            retriever.close()
            check(file.delete())
        }
        rejects<IllegalStateException> { retriever.extractMetadata(0) }
        println("stock-metadata-retriever: PASS remote FD slices, callback reads, metadata, decoded/scaled thumbnails and release")
    }
}
