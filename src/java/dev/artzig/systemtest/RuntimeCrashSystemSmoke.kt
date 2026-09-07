package dev.artzig.systemtest

import android.app.ActivityManager
import android.app.ActivityThread
import android.app.ApplicationExitInfo
import dev.artzig.HostedServiceClient
import android.os.DropBoxManager
import android.os.Looper
import android.os.Process

/** Exercises the stock uncaught handler in a separate, deliberately crashing app process. */
internal object RuntimeCrashSystemSmoke {
    private const val PACKAGE = "dev.artzig.test.a"
    private const val CRASH_PROCESS = "$PACKAGE:runtime_crash"
    private const val MESSAGE = "stock-runtime-main-crash"

    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        dev.artzig.HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 640, 480, 120, 0)
        val verify = System.getenv("AZK_VERIFY_RUNTIME_CRASH") == "1"
        val context = HostedServiceClient.attachApplication(
            PACKAGE, if (verify) "$PACKAGE:runtime_verify" else CRASH_PROCESS)
        if (!verify) throw IllegalStateException(MESSAGE)

        val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
        var crash: ApplicationExitInfo? = null
        repeat(100) {
            if (crash == null) {
                crash = manager.getHistoricalProcessExitReasons(PACKAGE, 0, 0)
                    .firstOrNull { it.processName == CRASH_PROCESS }
                if (crash == null) Thread.sleep(20)
            }
        }
        check(crash?.reason == ApplicationExitInfo.REASON_CRASH) { "Missing crash exit reason: $crash" }
        check(crash?.description?.contains(MESSAGE) == true)
        check(crash?.realUid == Process.myUid())
        val dropBox = checkNotNull(context.getSystemService(DropBoxManager::class.java))
        var cursor = 0L
        var found = false
        while (true) {
            val entry = dropBox.getNextEntry("data_app_crash", cursor) ?: break
            entry.use {
                cursor = it.timeMillis
                val text = it.getText(32768) ?: ""
                if (text.contains("Process: $CRASH_PROCESS") && text.contains(MESSAGE)) found = true
            }
        }
        check(found) { "Uncaught main exception missing from DropBox" }
        System.err.println("system-test: stock uncaught main handler, crash report, and exit history passed")
    }
}
