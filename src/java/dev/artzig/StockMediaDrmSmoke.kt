package dev.artzig

import android.app.ActivityThread
import android.media.DeniedByServerException
import android.media.MediaDrm
import android.media.MediaDrmException
import android.media.NotProvisionedException
import android.media.ResourceBusyException
import android.media.UnsupportedSchemeException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.UUID

/** Stock factory-absence, validation ordering and failed-object lifetime contracts. */
internal object StockMediaDrmSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        val thrown = runCatching(block).exceptionOrNull()
        val failure = if (thrown is InvocationTargetException) thrown.cause else thrown
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
        return failure
    }

    fun run() {
        check(MediaDrm.getSupportedCryptoSchemes().isEmpty())
        val schemes = listOf(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
            UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
            UUID(0, 0), UUID(-1, -1)
        )
        check(ActivityThread.currentOpPackageName() != null)
        for (scheme in schemes) {
            val query = rejects<IllegalArgumentException> { MediaDrm.isCryptoSchemeSupported(scheme) }
            check(query.message == "Failed to query crypto scheme support")
            rejects<IllegalArgumentException> { MediaDrm.isCryptoSchemeSupported(scheme, "video/mp4") }
            rejects<IllegalArgumentException> {
                MediaDrm.isCryptoSchemeSupported(scheme, "video/mp4", MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO)
            }
            val create = rejects<UnsupportedSchemeException> { MediaDrm(scheme) }
            check(create.message!!.startsWith("Failed to instantiate drm object: NO_INIT\n"))
            check(create.vendorError == 0 && create.oemError == 0 && create.errorContext == 0)
        }
        val publicQuery = MediaDrm::class.java.getMethod("isCryptoSchemeSupported", UUID::class.java)
        val constructor = MediaDrm::class.java.getConstructor(UUID::class.java)
        rejects<NullPointerException> { publicQuery.invoke(null, null) }
        rejects<NullPointerException> { constructor.newInstance(null) }

        // A failed native constructor leaves no JDrm. Allocate that same state to
        // test the native contract deterministically, without waiting for finalization.
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val failed = unsafe.allocateInstance(MediaDrm::class.java) as MediaDrm
        // Unsafe skips Java field initializers as well as native setup. Reconstruct the
        // constructor's cleanup fields so the stock finalizer can release this failed object.
        for ((name, value) in listOf(
            "mCloseGuard" to dalvik.system.CloseGuard.get(),
            "mClosed" to java.util.concurrent.atomic.AtomicBoolean(),
            "mPlaybackComponentMap" to java.util.concurrent.ConcurrentHashMap<Any, Any>(),
        )) MediaDrm::class.java.getDeclaredField(name).apply { isAccessible = true }.set(failed, value)
        repeat(2) { failed.native_release() }
        val setup = MediaDrm::class.java.getDeclaredMethod("native_setup",
            Any::class.java, ByteArray::class.java, String::class.java).apply { isAccessible = true }
        val query = MediaDrm::class.java.getDeclaredMethod("isCryptoSchemeSupportedNative",
            ByteArray::class.java, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        check(rejects<IllegalArgumentException> { setup.invoke(failed, null, null, null) }.message == "uuid is null")
        rejects<IllegalArgumentException> { query.invoke(null, null, null, 0) }
        for (size in listOf(0, 15, 17)) {
            check(rejects<IllegalArgumentException> {
                setup.invoke(failed, null, ByteArray(size), null)
            }.message == "invalid UUID size, expected 16 bytes")
            check(rejects<IllegalArgumentException> {
                query.invoke(null, ByteArray(size), null, 0)
            }.message == "invalid UUID size, expected 16 bytes")
        }
        check(rejects<IllegalArgumentException> {
            setup.invoke(failed, null, ByteArray(16), null)
        }.message == "application package name cannot be null")
        rejects<UnsupportedSchemeException> { setup.invoke(failed, null, ByteArray(16), "android") }
        val initialization = setOf("native_init", "native_release", "native_setup",
            "getSupportedCryptoSchemesNative", "isCryptoSchemeSupportedNative")
        var checked = 0
        for (method in MediaDrm::class.java.declaredMethods) {
            if (!Modifier.isNative(method.modifiers) || method.name in initialization) continue
            method.isAccessible = true
            val args = method.parameterTypes.map {
                when (it) {
                    MediaDrm::class.java -> failed
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }.toTypedArray()
            val failure = rejects<IllegalStateException> {
                method.invoke(if (Modifier.isStatic(method.modifiers)) null else failed, *args)
            }
            check(failure.message == "MediaDrm obj is null") { method.name }
            checked++
        }
        check(checked == 38)
        repeat(2) { failed.native_release() }
        for (error in listOf(
            MediaDrmException("test", 3, 5, 7),
            DeniedByServerException("test", 3, 5, 7),
            NotProvisionedException("test", 3, 5, 7),
            ResourceBusyException("test", 3, 5, 7)
        )) {
            check(error.message == "test" && error.vendorError == 3 && error.oemError == 5 && error.errorContext == 7)
        }
        println("stock MediaDrm factory absence, validation and failed-object contracts: PASS")
    }
}
