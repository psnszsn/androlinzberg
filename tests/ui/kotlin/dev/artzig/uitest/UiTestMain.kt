package dev.artzig.uitest

import androidx.test.uiautomator.UiDevice
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/** What a test needs from the session it runs in. */
object UiTest {
    lateinit var instrumentation: HostInstrumentation
        private set
    val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    /** Runs one JUnit test method; returns the process exit status. */
    @JvmStatic
    fun main(args: Array<String>): Int {
        check(args.size == 2) { "usage: UiTestMain <test class> <method>" }
        instrumentation = HostInstrumentation()
        try {
            val result = JUnitCore().run(Request.method(Class.forName(args[0]), args[1]))
            for (failure in result.failures) {
                println("UiTest: FAILED ${failure.testHeader}")
                println(failure.trace)
            }
            println("UiTest: ${args[0]}#${args[1]} run=${result.runCount} failed=${result.failureCount} ignored=${result.ignoreCount}")
            return if (result.wasSuccessful() && result.runCount == 1) 0 else 1
        } finally {
            instrumentation.release()
        }
    }
}
