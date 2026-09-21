package dev.artzig.testing

import android.os.Binder
import android.os.Process
import android.os.ServiceManager
import dev.artzig.ArtzigTestDiagnostics

/** Publishes this process's runtime journal to an inspected session. UI
 * observation and input belong to the stock accessibility service. */
object ProcessInspection {
    const val SERVICE = "dev.artzig.testing.processes"
    val enabled: Boolean get() = System.getenv("AZK_TEST_INSPECTION") == "1"
    private var registered = false

    /** Observe attachment without queueing behind provider/Application startup.
     * ActivityThread publishes its instance through a volatile field; AMS then
     * confirms attachment independently of finishAttachApplication. UI work still
     * belongs to the original main Looper, and the registry gates it on readiness. */
    fun observeActivityMainStartup() {
        if (!enabled || Process.isIsolated()) return
        Thread({
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val thread = android.app.ActivityThread.currentActivityThread()
                if (thread != null && android.app.ActivityManager.getService().runningAppProcesses
                        ?.any { it.pid == Process.myPid() && it.uid == Process.myUid() } == true) {
                    register(thread.applicationThread)
                    return@Thread
                }
                Thread.sleep(5)
            }
            error("ActivityThread.main did not attach for process inspection")
        }, "process-inspection-startup").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun register(application: android.app.IApplicationThread) {
        if (!enabled || registered) return
        val endpoint = object : IProcessInspection.Stub() {
            override fun runtimeDiagnostics(afterLog: Long, afterIssue: Long): String {
                enforceSystem()
                return ArtzigTestDiagnostics.snapshot(afterLog, afterIssue)
            }
        }
        val registry = IInspectionRegistry.Stub.asInterface(checkNotNull(ServiceManager.checkService(SERVICE)) {
            "The session has no process inspection registry"
        })
        check(registry.registerProcess(application, endpoint) > 0)
        registered = true
    }

    private fun enforceSystem() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("Process inspection requires the system controller")
    }

}
