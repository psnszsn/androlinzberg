package dev.artzig

import android.app.ActivityThread
import android.app.SystemServiceRegistry
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise registry behavior through Context and the stock WifiManager constructor. */
internal object StockServiceRegistrySmoke {
    private fun context(onApplicationInfo: () -> Unit): Context {
        val base: Context = ActivityThread.currentActivityThread().systemContext
        val fresh = base.createConfigurationContext(Configuration(base.resources.configuration))
        val outer = object : ContextWrapper(fresh) {
            override fun getApplicationInfo(): ApplicationInfo {
                onApplicationInfo()
                return super.getApplicationInfo()
            }
        }
        // The same outer-context attachment used by Activity; no registry state is modified.
        Class.forName("android.app.ContextImpl")
            .getDeclaredMethod("setOuterContext", Context::class.java)
            .apply { isAccessible = true }.invoke(fresh, outer)
        return fresh
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "service construction timed out" }
    }

    private fun join(thread: Thread) {
        thread.join(5_000)
        check(!thread.isAlive) { "service lookup did not finish" }
    }

    private fun concurrentConstruction() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val constructions = AtomicInteger()
        val context = context {
            constructions.incrementAndGet()
            entered.countDown()
            await(release)
        }
        val creatorResult = AtomicReference<WifiManager>()
        val waiterResult = AtomicReference<WifiManager>()
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicReference<Boolean>()
        val creator = Thread({
            try { creatorResult.set(context.getSystemService(WifiManager::class.java)) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "service-constructor").apply { isDaemon = true }
        val waiter = Thread({
            try {
                waiterResult.set(context.getSystemService(WifiManager::class.java))
                interrupted.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "service-waiter").apply { isDaemon = true }
        try {
            creator.start()
            await(entered)
            waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiter.state != Thread.State.WAITING &&
                waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            check(waiter.state == Thread.State.WAITING ||
                waiter.state == Thread.State.TIMED_WAITING) { "second lookup did not wait" }
            check(constructions.get() == 1) { "manager constructor ran concurrently" }
            waiter.interrupt()
        } finally {
            release.countDown()
            join(creator)
            if (waiter.state != Thread.State.NEW) join(waiter)
        }
        failure.get()?.let { throw AssertionError("service construction failed", it) }
        check(creatorResult.get() != null && creatorResult.get() === waiterResult.get())
        check(interrupted.get() == true) { "lookup lost the waiting thread's interrupt" }
        check(constructions.get() == 1)
        check(context.getSystemService(Context.WIFI_SERVICE) === creatorResult.get())
        val another = context {}
        check(another.getSystemService(WifiManager::class.java) !== creatorResult.get())
    }

    private fun failedConstructionCanRetry() {
        val calls = AtomicInteger()
        val context = context {
            if (calls.incrementAndGet() == 1) throw IllegalArgumentException("fixture constructor")
        }
        try {
            context.getSystemService(WifiManager::class.java)
            error("constructor failure was swallowed")
        } catch (expected: IllegalArgumentException) {
            check(expected.message == "fixture constructor")
        }
        val result = AtomicReference<WifiManager>()
        val failure = AtomicReference<Throwable>()
        val retry = Thread({
            try { result.set(context.getSystemService(WifiManager::class.java)) }
            catch (error: Throwable) { failure.set(error) }
        }, "service-retry").apply { isDaemon = true }
        retry.start()
        join(retry)
        failure.get()?.let { throw AssertionError("service retry failed", it) }
        check(result.get() != null && calls.get() == 2)
    }

    fun run() {
        check(SystemServiceRegistry.getSystemServiceWithNoContext("dev.artzig.unknown") == null)
        try {
            SystemServiceRegistry.getSystemServiceWithNoContext(Context.WIFI_SERVICE)
            error("context-dependent manager accepted a context-free lookup")
        } catch (expected: IllegalArgumentException) {
            check(expected.message == "Manager cannot be fetched without a context: wifi")
        }
        val attempts = listOf<() -> Unit>(
            {
                SystemServiceRegistry.registerForeverStaticService("dev.artzig.late", Any::class.java,
                    SystemServiceRegistry.StaticServiceProducerWithBinder { Any() })
            },
            {
                SystemServiceRegistry.registerStaticService("dev.artzig.late", Any::class.java,
                    SystemServiceRegistry.StaticServiceProducerWithoutBinder { Any() })
            },
            {
                SystemServiceRegistry.registerContextAwareService("dev.artzig.late", Any::class.java,
                    SystemServiceRegistry.ContextAwareServiceProducerWithoutBinder { Any() })
            },
        )
        for (attempt in attempts) {
            try { attempt(); error("late service registration accepted") }
            catch (expected: IllegalStateException) {
                check(expected.message.orEmpty().contains("can only be called during class initialization"))
            }
        }
        concurrentConstruction()
        failedConstructionCanRetry()
        println("stock service construction, retry and interrupt contracts: PASS")
    }
}
