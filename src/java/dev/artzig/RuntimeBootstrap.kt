package dev.artzig

import com.android.internal.os.RuntimeInit

/**
 * AndroidRuntime.start / AppRuntime.onStarted for a launcher-owned ART process.
 * The host creates ART directly, so the native finish callback dispatches the
 * configured Java entry here. Stock RuntimeInit owns common initialization;
 * its reflective entry wrapper preserves target-main exception behavior.
 */
object RuntimeBootstrap {
    private data class Entry(val name: String, val arguments: Array<String>, val thread: Thread)
    private val lock = Any()
    private var entry: Entry? = null
    private val invoker = MainInvoker()

    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.isNotEmpty() && arguments[0].isNotEmpty()) { "Missing Java entry class" }
        synchronized(lock) {
            check(entry == null) { "Java entry is already running" }
            entry = Entry(arguments[0], arguments.copyOfRange(1, arguments.size), Thread.currentThread())
        }
        try {
            // app_process's non-zygote tool path keeps the existing standard
            // streams. The host logger owns their native descriptor lifetime.
            RuntimeInit.main(emptyArray())
        } finally {
            synchronized(lock) { entry = null }
        }
    }

    /** Called by RuntimeInit.nativeFinishInit after commonInit completes. */
    @JvmStatic fun onStarted() {
        val current = synchronized(lock) { checkNotNull(entry) { "No configured Java entry" } }
        check(current.thread === Thread.currentThread()) { "Java entry must run on its startup thread" }
        try {
            invoker.invoke(current.name, current.arguments)
        } catch (failure: Throwable) {
            // AndroidRuntime leaves the pending main exception for ART's
            // Thread::Destroy/HandleUncaughtExceptions on detach. This host
            // releases ART with the process, so dispatch before crossing JNI.
            // Thread owns the pre-handler and app/default handler ordering.
            current.thread.dispatchUncaughtException(failure)
        }
    }

    private class MainInvoker : RuntimeInit() {
        fun invoke(name: String, arguments: Array<String>) {
            findStaticMain(name, arguments, RuntimeBootstrap::class.java.classLoader).run()
        }
    }
}
