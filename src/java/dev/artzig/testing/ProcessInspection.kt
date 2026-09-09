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
    private val traceFrames = System.getenv("AZK_TEST_FRAME_DIAGNOSTICS") == "1"
    private var busySince = 0L
    private var dumpedBusy = false

    // Test instrumentation only. Observe the pinned root and looper's own dump
    // contracts on their owner thread when a traversal remains pending.
    private fun inspectPendingFrames(idle: Boolean) {
        if (!traceFrames) return
        val roots = android.view.HostedRootFrames.managedRoots()
        if (roots.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (idle) { busySince = 0L; dumpedBusy = false; return }
        if (busySince == 0L) busySince = now
        if (dumpedBusy || now - busySince < 2000L) return
        dumpedBusy = true
        val text = java.io.StringWriter()
        val writer = java.io.PrintWriter(text)
        for (root in roots) root.dump("", writer)
        Looper.getMainLooper().dump(android.util.Printer { writer.println(it) }, "")
        val choreographer = android.view.Choreographer.getInstance()
        val receiverField = android.view.Choreographer::class.java.getDeclaredField("mDisplayEventReceiver")
            .apply { isAccessible = true }
        val receiver = receiverField.get(choreographer)
        if (receiver != null) {
            val pointer = android.view.DisplayEventReceiver::class.java.getDeclaredField("mReceiverPtr")
                .apply { isAccessible = true }.getLong(receiver)
            writer.println("AZK display receiver pid=${Process.myPid()} ptr=0x${pointer.toString(16)}")
            for (name in arrayOf("mHavePendingVsync", "mTimestampNanos", "mFrame")) {
                val field = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }
                writer.println("receiver.$name=${field.get(receiver)}")
            }
        }
        writer.flush()
        System.err.println("AZK pending frame diagnostics at $now\n${text.toString().take(65536)}")
    }

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
                        inspectPendingFrames(getBoolean("idle"))
                        putString("accessibility", ArtzigAccessibilityBridge.diagnostics(afterRevision))
                        putString("runtime", ArtzigTestDiagnostics.snapshot(afterLog, afterIssue))
                    }
                }
            }

            override fun inputWindow(revision: Long, windowId: Long, expectedTarget: String?): android.os.IBinder? {
                enforceSystem()
                return onMain(handler) { ArtzigAccessibilityBridge.inputWindow(revision, windowId, expectedTarget) }
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
