package com.android.server

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.DropBoxManager
import android.os.Environment
import android.os.FileUtils
import android.os.ServiceManager
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Slog
import com.android.server.am.DropboxRateLimiter
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Host boundary for pinned Watchdog and ActivityManagerService.addErrorToDropBox.
 * The session has its own process graph and DropBox service; it has no Android
 * AMS, HIDL manager, storage daemon or authority to reset the host kernel.
 * Stock Watchdog retains monitoring, timeout, debugger and controller decisions.
 */
class HostedWatchdogEnvironment internal constructor(
    private val store: ReportStore,
    private val limiter: DropboxRateLimiter,
    private val mediaProcess: () -> String?,
) {
    internal interface ReportStore {
        fun enabled(tag: String): Boolean
        fun maxBytes(tag: String): Int
        fun write(tag: String, text: String)
    }

    constructor(context: Context) : this(
        object : ReportStore {
            private val dropbox get() = context.getSystemService(DropBoxManager::class.java)
            override fun enabled(tag: String) = dropbox?.isTagEnabled(tag) == true
            override fun maxBytes(tag: String) = Settings.Global.getInt(
                context.contentResolver, Settings.Global.MAX_ERROR_BYTES_PREFIX + tag, 192 * 1024,
            )
            override fun write(tag: String, text: String) { dropbox?.addText(tag, text) }
        },
        DropboxRateLimiter().also { it.init() },
        { context.packageManager.resolveContentProvider("media", 0)?.processName },
    ) {
        for (directory in listOf(Environment.getDataSystemDirectory(),
                Environment.getDataDirectory().resolve("anr"),
                Environment.getDataMiscDirectory().resolve("critical-events"))) {
            check(directory.isDirectory || directory.mkdirs()) {
                "Cannot create watchdog report directory $directory"
            }
        }
    }

    fun isMediaProcess(processName: String) = processName == mediaProcess()

    /** Called on Watchdog's bounded report thread without acquiring any AMS/WM lock. */
    fun report(eventType: String, report: String, stack: File?, errorId: UUID) {
        val tag = "system_server_$eventType"
        if (!store.enabled(tag)) return
        val rate = limiter.shouldRateLimit(eventType, "system_server")
        if (rate.shouldRateLimit()) return
        val text = buildString {
            append("SystemUptimeMs: ").append(SystemClock.uptimeMillis()).append('\n')
            append("Process: system_server\nErrorId: ").append(errorId).append('\n')
            append("Build: ").append(Build.FINGERPRINT).append('\n')
            val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
            if (pageSize != 4096L) append("PageSize: ").append(pageSize).append('\n')
            if (Debug.isDebuggerConnected()) append("Debugger: Connected\n")
            append(rate.createHeader()).append('\n').append(report)
            if (stack != null) {
                append("Data File: ").append(stack.absolutePath).append('\n')
                val available = store.maxBytes(tag) - length - DATA_FILE_FOOTER.length
                if (available > 0) {
                    try { append(FileUtils.readTextFile(stack, available, "\n\n[[TRUNCATED]]\n")) }
                    catch (error: IOException) { Slog.e(TAG, "Error reading $stack", error) }
                }
                append(DATA_FILE_FOOTER)
            }
        }
        store.write(tag, text)
    }

    companion object {
        private const val TAG = "HostedWatchdog"
        private const val DATA_FILE_FOOTER = "End Data File\n"

        /** Native services in this broker session, independent of Android /system/bin paths. */
        @JvmStatic fun nativePids(): List<Int> =
            (ServiceManager.getServiceDebugInfo() ?: emptyArray()).asSequence()
                .filter { it.name == "SurfaceFlinger" || it.name == "SurfaceFlingerAIDL" ||
                    it.name.startsWith("media.") }
                .map { it.debugPid }.filter { it > 0 }.distinct().toList()

        /** Read local process state instead of issuing global kernel SysRq commands. */
        @JvmStatic fun processDiagnostics(): String = buildString {
            for (path in listOf("/proc/self/status", "/proc/self/limits", "/proc/meminfo")) {
                append("----- Output from ").append(path).append(" -----\n")
                try { append(FileUtils.readTextFile(File(path), 16 * 1024, "\n[truncated]\n")) }
                catch (error: IOException) { append("Unavailable: ").append(error).append('\n') }
            }
        }

        @JvmStatic fun exitCrashLoop() {
            Slog.e(TAG, "Repeated watchdog failure; ending the hosted system process (exit 11)")
            Runtime.getRuntime().exit(11)
        }
    }
}
