package dev.artzig

import android.media.MediaCrypto
import android.media.MediaCryptoException
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Public stock client behavior on a host with no installed DRM plugin. */
internal object StockMediaCryptoSmoke {
    private fun failure(action: () -> Any?): Throwable? =
        runCatching(action).exceptionOrNull().let {
            if (it is InvocationTargetException) it.cause else it
        }

    fun run() {
        val constructor = MediaCrypto::class.java.getConstructor(UUID::class.java, ByteArray::class.java)
        val schemes = listOf(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
            UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
            UUID(0, 0), UUID(-1, -1),
        )
        for (scheme in schemes) {
            check(!MediaCrypto.isCryptoSchemeSupported(scheme))
            for (session in listOf(null, byteArrayOf(), byteArrayOf(1, 2, 3))) {
                val failure = failure { constructor.newInstance(scheme, session) }
                check(failure is MediaCryptoException &&
                    failure.message == "Failed to instantiate crypto object: NO_INIT") { failure.toString() }
            }
        }
        val publicQuery = MediaCrypto::class.java.getMethod("isCryptoSchemeSupported", UUID::class.java)
        check(failure { publicQuery.invoke(null, null) } is NullPointerException)
        check(failure { constructor.newInstance(null, byteArrayOf()) } is NullPointerException)

        // Allocate the same zero-context state left by a failed constructor, to exercise
        // release and finalization deterministically without depending on GC scheduling.
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val crypto = unsafe.allocateInstance(MediaCrypto::class.java) as MediaCrypto
        repeat(2) { crypto.release() }
        check(failure { crypto.requiresSecureDecoderComponent("video/avc") } is IllegalArgumentException)
        check(failure { crypto.setMediaDrmSession(byteArrayOf(1)) } is IllegalArgumentException)
        val secureDecoder = MediaCrypto::class.java.getMethod("requiresSecureDecoderComponent", String::class.java)
        val setSession = MediaCrypto::class.java.getMethod("setMediaDrmSession", ByteArray::class.java)
        check(failure { secureDecoder.invoke(crypto, null) } is IllegalArgumentException)
        check(failure { setSession.invoke(crypto, null) } is IllegalArgumentException)
        val finalize = MediaCrypto::class.java.getDeclaredMethod("finalize").apply { isAccessible = true }
        repeat(2) { finalize.invoke(crypto) }
        val query = MediaCrypto::class.java.getDeclaredMethod("isCryptoSchemeSupportedNative",
            ByteArray::class.java).apply { isAccessible = true }
        val setup = MediaCrypto::class.java.getDeclaredMethod("native_setup",
            ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        for (size in listOf(0, 15, 17)) {
            val queryFailure = runCatching { query.invoke(null, ByteArray(size)) }.exceptionOrNull()
            check(queryFailure is InvocationTargetException && queryFailure.cause is IllegalArgumentException)
            val setupFailure = runCatching { setup.invoke(crypto, ByteArray(size), null) }.exceptionOrNull()
            check(setupFailure is InvocationTargetException && setupFailure.cause is IllegalArgumentException)
        }
        crypto.release()
        System.err.println("stock-media-crypto: capability queries, constructor failures, UUID validation and failed-object cleanup passed")
    }
}
