package dev.artzig.uitest

import android.os.ServiceManager
import androidx.test.uiautomator.UiDevice
import dev.artzig.testing.ISessionDiagnostics
import dev.artzig.testing.RuntimeIssue
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/** What a test needs from the session it runs in. */
object UiTest {
    lateinit var instrumentation: HostInstrumentation
        private set
    val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    /** The session's runtime journal: system server plus every attached app. */
    val diagnostics: ISessionDiagnostics by lazy {
        ISessionDiagnostics.Stub.asInterface(ServiceManager.waitForService("dev.artzig.testing.diagnostics"))
    }
    private var issuesAllowed = false

    /** For a test that deliberately inventories a known framework gap. */
    fun allowRuntimeIssues() { issuesAllowed = true }

    private fun describe(issue: RuntimeIssue) =
        "${issue.kind} ${issue.owner}.${issue.member} [${issue.process}:${issue.pid}] ${issue.detail}"

    /** Runs one JUnit test method; returns the process exit status. */
    @JvmStatic
    fun main(args: Array<String>): Int {
        check(args.size == 2) { "usage: UiTestMain <test class> <method>" }
        instrumentation = HostInstrumentation()
        try {
            // Only what the test itself provokes counts: start after session boot.
            val before = diagnostics.read(Long.MAX_VALUE, Long.MAX_VALUE)
            // The session collects each process's bounded history when it is
            // read. Keep collecting while the test runs, so a burst of records
            // is journalled instead of being reported as lost at the end.
            val collector = kotlin.concurrent.thread(isDaemon = true, name = "journal-collector") {
                try {
                    while (true) {
                        Thread.sleep(250)
                        diagnostics.read(Long.MAX_VALUE, Long.MAX_VALUE)
                    }
                } catch (_: InterruptedException) {
                }
            }
            val result = JUnitCore().run(Request.method(Class.forName(args[0]), args[1]))
            collector.interrupt()
            collector.join()
            for (failure in result.failures) {
                println("UiTest: FAILED ${failure.testHeader}")
                println(failure.trace)
            }
            // A test passes only with the expected UI state and an empty issue journal.
            val issues = diagnostics.read(Long.MAX_VALUE, before.issueCursor).issues
            for (issue in issues) println("UiTest: ISSUE ${describe(issue)}")
            println("UiTest: ${args[0]}#${args[1]} run=${result.runCount} failed=${result.failureCount} ignored=${result.ignoreCount} issues=${issues.size}")
            val clean = issues.isEmpty() || issuesAllowed
            return if (result.wasSuccessful() && result.runCount == 1 && clean) 0 else 1
        } finally {
            instrumentation.release()
        }
    }
}
