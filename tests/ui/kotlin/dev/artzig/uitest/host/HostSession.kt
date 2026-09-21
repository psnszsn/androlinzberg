package dev.artzig.uitest.host

import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit

/** What host-path tests use from the desktop the session runs on. */
object HostSession {
    /** stdout and stderr of every session process, including the native services. */
    private val log = File(checkNotNull(System.getenv("AZK_TEST_SESSION_LOG")) { "AZK_TEST_SESSION_LOG is not set" })

    fun awaitLog(fragment: String, timeoutMillis: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (fragment !in log.readText()) {
            check(SystemClock.uptimeMillis() < deadline) { "the session log never contained: $fragment" }
            SystemClock.sleep(100)
        }
    }

    /**
     * Runs a desktop tool (wl-copy, wl-paste, ...) beside the session, on its
     * compositor. argv never passes through a shell. Returns its stdout.
     */
    fun run(vararg argv: String, input: ByteArray? = null, timeoutMillis: Long = 10_000): ByteArray {
        val process = ProcessBuilder(*argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            process.outputStream.use { stream -> if (input != null) stream.write(input) }
            val output = process.inputStream.readBytes()
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "${argv.first()} timed out" }
            check(process.exitValue() == 0) { "${argv.joinToString(" ")} exited ${process.exitValue()}" }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
