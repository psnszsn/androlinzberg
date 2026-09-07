package dev.artzig

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.os.ServiceManager
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Stock client contracts when the host has no media.player metadata service. */
internal object StockMediaMetadataRetrieverSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Any?): T {
        var error = runCatching(action).exceptionOrNull()
        if (error is InvocationTargetException) error = error.cause
        check(error is T) { "Expected ${T::class.java.name}, got $error" }
        return error
    }

    fun run() {
        check(ServiceManager.checkService("media.player") == null)
        val retriever = MediaMetadataRetriever()
        val klass = MediaMetadataRetriever::class.java
        val params = MediaMetadataRetriever.BitmapParams()
        check(params.preferredConfig == Bitmap.Config.ARGB_8888)
        check(params.actualConfig == Bitmap.Config.ARGB_8888)
        val setPreferred = MediaMetadataRetriever.BitmapParams::class.java.getMethod("setPreferredConfig", Bitmap.Config::class.java)
        rejects<IllegalArgumentException> { setPreferred.invoke(params, null) }
        params.preferredConfig = Bitmap.Config.RGB_565
        check(params.actualConfig == Bitmap.Config.ARGB_8888)
        check(retriever.getFrameAtTime() == null)
        check(retriever.getFrameAtTime(42, MediaMetadataRetriever.OPTION_CLOSEST, params) == null)
        check(params.actualConfig == Bitmap.Config.ARGB_8888) // Failed single-frame reads do not mutate it.
        check(retriever.embeddedPicture == null)
        for (key in -1..43) check(retriever.extractMetadata(key) == null)
        rejects<IllegalArgumentException> { retriever.getFrameAtTime(0, -1) }
        rejects<IllegalArgumentException> { retriever.getFrameAtTime(0, 4) }
        rejects<IllegalArgumentException> { retriever.getScaledFrameAtTime(0, 0, 0, 20) }
        rejects<IllegalArgumentException> { retriever.getScaledFrameAtTime(0, 0, 20, -1) }
        rejects<IllegalStateException> { retriever.getFramesAtIndex(0, 1) }
        rejects<IllegalStateException> { retriever.getPrimaryImage() }
        rejects<IllegalArgumentException> { retriever.setDataSource(null as String?) }
        rejects<IllegalArgumentException> { retriever.setDataSource("mem://123", emptyMap()) }
        rejects<IllegalStateException> { retriever.setDataSource("https://example.invalid/video", mapOf("X-Test" to "value")) }
        rejects<NullPointerException> { retriever.setDataSource("https://example.invalid/video", null) }

        val fdSetter = klass.getDeclaredMethod("_setDataSource", FileDescriptor::class.java,
            java.lang.Long.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
        rejects<IllegalArgumentException> { fdSetter.invoke(retriever, null, 0L, 1L) }
        rejects<IllegalArgumentException> { fdSetter.invoke(retriever, FileDescriptor(), 0L, 1L) }
        FileInputStream("/dev/null").use { input ->
            rejects<IllegalArgumentException> { fdSetter.invoke(retriever, input.fd, -1L, 1L) }
            rejects<IllegalArgumentException> { fdSetter.invoke(retriever, input.fd, 0L, -1L) }
            rejects<IllegalStateException> { fdSetter.invoke(retriever, input.fd, 0L, 1L) }
            check(input.fd.valid() && input.read() == -1) // Borrowed descriptor remains usable.
        }
        var sourceCalls = 0
        val source = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int { sourceCalls++; return -1 }
            override fun getSize(): Long { sourceCalls++; return 0 }
            override fun close() { sourceCalls++ }
        }
        rejects<IllegalStateException> { retriever.setDataSource(source) }
        rejects<IllegalArgumentException> { retriever.setDataSource(null as MediaDataSource?) }
        check(sourceCalls == 0)
        val uriSetter = klass.getDeclaredMethod("_setDataSource", IBinder::class.java,
            String::class.java, Array<String>::class.java, Array<String>::class.java).apply { isAccessible = true }
        rejects<IllegalArgumentException> { uriSetter.invoke(retriever, null, null, null, null) }
        rejects<IllegalArgumentException> { uriSetter.invoke(retriever, null, "file:/unused", arrayOf("X"), null) }
        rejects<IllegalArgumentException> { uriSetter.invoke(retriever, null, "file:/unused", arrayOf("X"), emptyArray<String>()) }
        rejects<IllegalStateException> { uriSetter.invoke(retriever, null, "file:/unused", null, null) }
        val frameList = klass.getDeclaredMethod("_getFrameAtIndex", Integer.TYPE, Integer.TYPE,
            MediaMetadataRetriever.BitmapParams::class.java).apply { isAccessible = true }
        check(rejects<IllegalStateException> { frameList.invoke(retriever, 0, 1, params) }.message == "No frames from retriever")
        check(params.actualConfig == Bitmap.Config.RGB_565)
        params.preferredConfig = Bitmap.Config.RGBA_F16
        rejects<IllegalStateException> { frameList.invoke(retriever, 0, 1, params) }
        check(params.actualConfig == Bitmap.Config.ARGB_8888)
        val genre = klass.getDeclaredMethod("convertGenreTag", String::class.java).apply { isAccessible = true }
        check(genre.invoke(retriever, "17") == "Rock")
        check(genre.invoke(retriever, "(RX)(CR)Custom") == "Remix, Cover, Custom")
        check(genre.invoke(retriever, "99999") == null)
        check(genre.invoke(retriever, "(invalid)") == null)
        repeat(2) { retriever.release(); retriever.close() }
        check(sourceCalls == 0)
        var checked = 0
        for (method in klass.declaredMethods) {
            if (!Modifier.isNative(method.modifiers) || method.name in setOf("native_init", "native_setup", "release", "native_finalize")) continue
            method.isAccessible = true
            val arguments = method.parameterTypes.map {
                when (it) { Integer.TYPE -> (-1 as Any); java.lang.Long.TYPE -> -1L; else -> null }
            }.toTypedArray()
            val failure = rejects<IllegalStateException> { method.invoke(retriever, *arguments) }
            check(failure.message == "No retriever available") { "${method.name}: ${failure.message}" }
            checked++
        }
        check(checked == 9)
        val finalize = klass.getDeclaredMethod("native_finalize").apply { isAccessible = true }
        repeat(2) { finalize.invoke(retriever) }
        MediaMetadataRetriever().use { check(it.extractMetadata(0) == null) }
        System.err.println("stock-metadata-retriever: PASS validation, absent-service failures, borrowed sources, genre parsing and release")
    }
}
