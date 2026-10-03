package dev.artzig.uitest.host

import android.os.SystemClock
import org.json.JSONObject
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
     * Starts the launcher from a shell that has none of the session owner's
     * Android environment, joins the session and runs `testClass` there.
     * Returns its output.
     */
    fun joinFromForeignShell(testClass: String): String {
        val directory = checkNotNull(System.getenv("AZK_TEST_SESSION_DIR")) { "AZK_TEST_SESSION_DIR is not set" }
        val storage = JSONObject(File(directory, "session.json").readText()).getJSONObject("storage")
        val owned = setOf("ANDROID_DATA", "ARTZIG_DATA_DIR", "ARTZIG_RUNTIME_CACHE_DIR", "EXTERNAL_STORAGE", "DOWNLOAD_CACHE", "AZK_PROP_AREA")
        return String(run(
            checkNotNull(System.getenv("AZK_TEST_LAUNCHER")), "--join", directory, "--javatest",
            File(directory, "android-data").path, storage.getString("application_data"),
            timeoutMillis = 30_000, mergeError = true,
        ) { variables ->
            variables.keys.removeAll { it.startsWith("AZK_BINDER_") || it.startsWith("AZK_TEST_") || it in owned }
            variables["AZK_TEST_CLASS"] = testClass
        })
    }

    /**
     * `androlinzberg --join <session> <apk>` from a foreign shell: a second
     * launcher joining the running session installs the session's APK again
     * and starts it (the host's `adb install` + `am start`). Returns its output.
     */
    fun launchFromForeignShell(): String {
        val directory = checkNotNull(System.getenv("AZK_TEST_SESSION_DIR")) { "AZK_TEST_SESSION_DIR is not set" }
        val apk = checkNotNull(System.getenv("AZK_TEST_SESSION_APK")) { "AZK_TEST_SESSION_APK is not set" }
        val owned = setOf("ANDROID_DATA", "ARTZIG_DATA_DIR", "ARTZIG_RUNTIME_CACHE_DIR", "EXTERNAL_STORAGE", "DOWNLOAD_CACHE", "AZK_PROP_AREA")
        return String(run(checkNotNull(System.getenv("AZK_TEST_LAUNCHER")), "--join", directory, apk,
            timeoutMillis = 60_000, mergeError = true) { variables ->
            variables.keys.removeAll { it.startsWith("AZK_BINDER_") || it.startsWith("AZK_TEST_") || it in owned }
        })
    }

    /** Where this test's artifacts are retained. */
    val artifacts: File get() = log.absoluteFile.parentFile

    /**
     * The repository's native Wayland peer (tests/native/wayland_surface_server.zig)
     * in one of its drag-and-drop roles, as a second client of the compositor.
     */
    fun peer(vararg arguments: String): ByteArray =
        run(checkNotNull(System.getenv("AZK_TEST_SEAT")) { "AZK_TEST_SEAT is not set" }, *arguments, timeoutMillis = 60_000)

    /** A binary PPM, as grim and the native peer write compositor captures. */
    class Capture(bytes: ByteArray) {
        val width: Int
        val height: Int
        private val pixels: ByteArray

        init {
            var offset = 0
            val header = List(3) {
                val end = (offset until bytes.size).first { bytes[it] == '\n'.code.toByte() }
                String(bytes, offset, end - offset).also { offset = end + 1 }
            }
            check(header[0] == "P6" && header[2] == "255") { "not a PPM capture: $header" }
            val (columns, rows) = header[1].split(' ').map(String::toInt)
            width = columns
            height = rows
            pixels = bytes.copyOfRange(offset, bytes.size)
        }

        /** Red, green, blue. */
        fun pixel(x: Int, y: Int) = List(3) { pixels[(y * width + x) * 3 + it].toInt() and 0xff }
    }

    /** What the compositor currently shows. */
    fun capture() = Capture(run("grim", "-t", "ppm", "-"))

    /**
     * Runs a desktop tool (wl-copy, wl-paste, ...) beside the session, on its
     * compositor. argv never passes through a shell. Returns its stdout, or
     * nothing with [capture] off: a tool that leaves a daemon behind (wl-copy
     * serving its selection) would hold a captured stream open.
     */
    fun run(
        vararg argv: String,
        capture: Boolean = true,
        timeoutMillis: Long = 10_000,
        mergeError: Boolean = false,
        environment: (MutableMap<String, String>) -> Unit = {},
    ): ByteArray {
        val nowhere = File("/dev/null")
        val builder = ProcessBuilder(*argv).redirectInput(ProcessBuilder.Redirect.from(nowhere))
        // Host tools start in the invoking host directory, not the VM's "/".
        System.getenv("AZK_HOST_CWD")?.let { builder.directory(File(it)) }
        environment(builder.environment())
        if (capture) {
            if (mergeError) builder.redirectErrorStream(true) else builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        } else {
            // The daemon would also hold this runner's own stderr open.
            builder.redirectOutput(ProcessBuilder.Redirect.to(nowhere)).redirectError(ProcessBuilder.Redirect.to(nowhere))
        }
        val process = builder.start()
        try {
            val output = if (capture) process.inputStream.readBytes() else ByteArray(0)
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "${argv.first()} timed out" }
            check(process.exitValue() == 0) {
                "${argv.joinToString(" ")} exited ${process.exitValue()}" +
                    if (output.isEmpty()) "" else ":\n" + String(output).takeLast(4000)
            }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
