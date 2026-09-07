package dev.artzig.testing

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import dev.artzig.ArtzigAccessibilityBridge
import dev.artzig.ArtzigTestDiagnostics
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Main-thread inspection boundary for the existing private test controller.
 * It observes stock roots and delegates actions to their accessibility contracts. */
object ProcessInspection {
    const val SERVICE = "dev.artzig.testing.processes"
    val enabled: Boolean get() = System.getenv("AZK_TEST_SOCKET") != null ||
        System.getenv("AZK_TEST_INSPECTION") == "1"
    private var registered = false

    fun register(application: android.app.IApplicationThread) {
        if (!enabled || registered) return
        check(Looper.myLooper() == Looper.getMainLooper())
        val handler = Handler.createAsync(Looper.getMainLooper())
        val endpoint = object : IProcessInspection.Stub() {
            override fun runtimeDiagnostics(afterLog: Long, afterIssue: Long): String {
                enforceSystem()
                return ArtzigTestDiagnostics.snapshot(afterLog, afterIssue)
            }

            override fun observe(afterRevision: Long, afterLog: Long, afterIssue: Long): Bundle {
                enforceSystem()
                return onMain(handler) {
                    ArtzigAccessibilityBridge.processObservation().apply {
                        putString("accessibility", ArtzigAccessibilityBridge.diagnostics(afterRevision))
                        putString("runtime", ArtzigTestDiagnostics.snapshot(afterLog, afterIssue))
                    }
                }
            }

            override fun inputWindow(revision: Long, windowId: Long): android.os.IBinder? {
                enforceSystem()
                return onMain(handler) { ArtzigAccessibilityBridge.inputWindow(revision, windowId) }
            }

            override fun perform(revision: Long, windowId: Long, viewId: Int, virtualId: Int,
                action: Int, text: String?): Boolean {
                enforceSystem()
                return onMain(handler) {
                    ArtzigAccessibilityBridge.perform(revision, windowId, viewId, virtualId, action, text)
                }
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

    private fun <T> onMain(handler: Handler, block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val work = FutureTask(Callable { block() })
        check(handler.post(work)) { "Application main looper has exited" }
        try { return work.get(3, TimeUnit.SECONDS) }
        catch (failure: TimeoutException) {
            // A queued action must not start after its caller has timed out. If
            // already running, its outcome is unknown and must not be retried.
            work.cancel(false)
            handler.removeCallbacks(work)
            throw IllegalStateException("Application inspection timed out; an in-flight action may have completed", failure)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is RuntimeException) throw cause
            throw IllegalStateException("Application inspection failed", cause)
        } catch (failure: InterruptedException) {
            work.cancel(false)
            handler.removeCallbacks(work)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Application inspection interrupted", failure)
        }
    }
}
