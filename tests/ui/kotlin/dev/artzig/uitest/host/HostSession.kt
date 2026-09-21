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

    /** Where this test's artifacts are retained. */
    val artifacts: File get() = log.absoluteFile.parentFile

    /**
     * Runs a desktop tool (wl-copy, wl-paste, ...) beside the session, on its
     * compositor. argv never passes through a shell. Returns its stdout, or
     * nothing with [capture] off: a tool that leaves a daemon behind (wl-copy
     * serving its selection) would hold a captured stream open.
     */
    fun run(vararg argv: String, capture: Boolean = true, timeoutMillis: Long = 10_000): ByteArray {
        val nowhere = File("/dev/null")
        val builder = ProcessBuilder(*argv).redirectInput(ProcessBuilder.Redirect.from(nowhere))
        if (capture) {
            builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        } else {
            // The daemon would also hold this runner's own stderr open.
            builder.redirectOutput(ProcessBuilder.Redirect.to(nowhere)).redirectError(ProcessBuilder.Redirect.to(nowhere))
        }
        val process = builder.start()
        try {
            val output = if (capture) process.inputStream.readBytes() else ByteArray(0)
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "${argv.first()} timed out" }
            check(process.exitValue() == 0) { "${argv.joinToString(" ")} exited ${process.exitValue()}" }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
