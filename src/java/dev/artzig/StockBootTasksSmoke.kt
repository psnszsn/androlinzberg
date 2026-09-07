package dev.artzig

import com.android.server.HostedBootTasks
import com.android.server.SystemServerInitThreadPool
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object StockBootTasksSmoke {
    private fun shutdown() {
        val method = SystemServerInitThreadPool::class.java.getDeclaredMethod("shutdown")
        method.isAccessible = true
        try { method.invoke(null) } catch (failure: InvocationTargetException) { throw failure.cause!! }
    }

    fun run() {
        check(runCatching { SystemServerInitThreadPool.submit({}, "before start") }.exceptionOrNull() is IllegalStateException)
        val pool = HostedBootTasks.start()
        check(runCatching { HostedBootTasks.start() }.exceptionOrNull() is IllegalStateException)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val shutdownFailure = AtomicReference<Throwable?>()
        val future = SystemServerInitThreadPool.submit({ started.countDown(); release.await() }, "pending boot task")
        check(started.await(5, TimeUnit.SECONDS))
        try {
            val pending = StringWriter()
            pool.dump(PrintWriter(pending), emptyArray())
            check("pending boot task" in pending.toString())
            val stacks = StringWriter()
            HostedBootTasks.writeThreadDump(PrintWriter(stacks))
            check(Thread.currentThread().name in stacks.toString())
            check("system-server-init-thread" in stacks.toString())
            val stopping = Thread {
                try { shutdown() } catch (failure: Throwable) { shutdownFailure.set(failure) }
                finally { finished.countDown() }
            }
            stopping.start()
            check(!finished.await(100, TimeUnit.MILLISECONDS)) { "Shutdown abandoned pending work" }
            release.countDown()
            check(finished.await(5, TimeUnit.SECONDS))
            check(shutdownFailure.get() == null) { shutdownFailure.get()!! }
            future.get(5, TimeUnit.SECONDS)
            stopping.join()
            check(runCatching { SystemServerInitThreadPool.submit({}, "after shutdown") }.exceptionOrNull() is IllegalStateException)
            val stopped = StringWriter()
            pool.dump(PrintWriter(stopped), emptyArray())
            check("has instance: false" in stopped.toString() && "no pending tasks" in stopped.toString())
            check(HostedBootTasks.start() !== pool)
            shutdown()
        } finally {
            release.countDown()
        }
        System.err.println("stock-boot-tasks: PASS startup, pending work, diagnostics and shutdown ordering")
    }
}
