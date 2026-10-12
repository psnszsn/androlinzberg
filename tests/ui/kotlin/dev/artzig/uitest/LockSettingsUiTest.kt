package dev.artzig.uitest

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.ServiceManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-screen credential lifecycle as `adb shell cmd lock_settings` drives
 * it: LockSettingsService enrolls the PIN with gatekeeper, protects the user's
 * synthetic password with keystore2 keys, and verifies it again.
 */
class LockSettingsUiTest {
    private data class Result(val code: Int, val output: String)

    /** `cmd lock_settings <args>`: a Binder shell command from the shell uid. */
    private fun lockSettings(vararg args: String): Result {
        val binder = ServiceManager.getService("lock_settings")
        val pipe = ParcelFileDescriptor.createPipe()
        val input = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY)
        val code = CompletableFuture<Int>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code.complete(resultCode) }
        }
        try {
            binder.shellCommand(input.fileDescriptor, pipe[1].fileDescriptor, pipe[1].fileDescriptor, args, null, receiver)
        } finally {
            input.close()
            pipe[1].close()
        }
        val output = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.readBytes().toString(Charsets.UTF_8) }
        return Result(code.get(30, TimeUnit.SECONDS), output)
    }

    private val keyguard: KeyguardManager
        get() = UiTest.instrumentation.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @Test
    fun pinIsEnrolledVerifiedAndCleared() {
        assertFalse("no credential before enrollment", keyguard.isDeviceSecure)
        val enrolled = lockSettings("set-pin", "1234")
        assertEquals("set-pin: ${enrolled.output}", 0, enrolled.code)
        assertTrue("PIN set", keyguard.isDeviceSecure)

        val verified = lockSettings("verify", "--old", "1234")
        assertEquals("verify: ${verified.output}", 0, verified.code)
        val rejected = lockSettings("verify", "--old", "4321")
        assertNotEquals("wrong PIN rejected: ${rejected.output}", 0, rejected.code)

        val cleared = lockSettings("clear", "--old", "1234")
        assertEquals("clear: ${cleared.output}", 0, cleared.code)
        assertFalse("credential removed", keyguard.isDeviceSecure)
    }
}
