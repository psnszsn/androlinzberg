package dev.artzig.systemtest

/** The native driver releases stdin only after ART has emitted the complete
 * SIGQUIT dump, so successful exit proves both stack output and VM survival. */
internal object RuntimeSignalSystemSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val ready = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread({ ready.countDown(); release.await() }, "stock-signal-regression")
        worker.start()
        try {
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            android.os.Process.sendSignal(android.os.Process.myPid(), android.os.Process.SIGNAL_QUIT)
            check(System.`in`.read() == 1) { "Native driver did not observe the complete SIGQUIT dump" }
        } finally { release.countDown(); worker.join() }
        System.err.println("system-test: SIGQUIT stack output and VM survival passed")
    }
}
