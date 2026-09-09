package dev.artzig.window

import android.view.TunnelModeEnabledListener
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object StockTunnelModeSmoke {
    fun denied() {
        val listener = object : TunnelModeEnabledListener(java.util.concurrent.Executor { it.run() }) {
            override fun onTunnelModeEnabledChanged(enabled: Boolean) { error("Denied listener was invoked") }
        }
        // Stock Java leaves ownership intact when unregister throws. Every retry
        // must still reach the service and report its permission failure.
        repeat(2) {
            check(runCatching { TunnelModeEnabledListener.register(listener) }.exceptionOrNull() is RuntimeException)
            check(runCatching { TunnelModeEnabledListener.unregister(listener) }.exceptionOrNull() is RuntimeException)
            check(runCatching { listener.destroy() }.exceptionOrNull() is RuntimeException)
        }
    }

    fun granted() {
        val executor = Executors.newSingleThreadExecutor()
        val reports = LinkedBlockingQueue<Pair<Boolean, Thread>>()
        val listener = object : TunnelModeEnabledListener(executor) {
            override fun onTunnelModeEnabledChanged(enabled: Boolean) {
                reports.add(enabled to Thread.currentThread())
            }
        }
        try {
            repeat(2) {
                TunnelModeEnabledListener.register(listener)
                val report = checkNotNull(reports.poll(5, TimeUnit.SECONDS)) { "No initial tunnel-mode callback" }
                check(!report.first && report.second !== Thread.currentThread())
                TunnelModeEnabledListener.unregister(listener)
            }
            val destroyedInCallback = java.util.concurrent.CountDownLatch(1)
            val reentrant = object : TunnelModeEnabledListener(java.util.concurrent.Executor { it.run() }) {
                override fun onTunnelModeEnabledChanged(enabled: Boolean) {
                    destroy()
                    destroyedInCallback.countDown()
                }
            }
            try {
                TunnelModeEnabledListener.register(reentrant)
                check(destroyedInCallback.await(5, TimeUnit.SECONDS)) { "Callback destruction deadlocked" }
                reentrant.destroy()
                TunnelModeEnabledListener.register(reentrant)
                TunnelModeEnabledListener.unregister(reentrant)
            } finally { reentrant.destroy() }
            val callers = Executors.newFixedThreadPool(3)
            try {
                val operations = (0..2).map { operation -> callers.submit {
                    repeat(12) {
                        when (operation) {
                            0 -> TunnelModeEnabledListener.register(listener)
                            1 -> TunnelModeEnabledListener.unregister(listener)
                            else -> listener.destroy()
                        }
                    }
                } }
                operations.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally { callers.shutdownNow() }
        } finally {
            listener.destroy()
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** Observe actual JNI listener ownership, including a failed Java finalizer. */
    fun ownership(live: () -> Long, authorized: Boolean) {
        val baseline = live()
        repeat(8) {
            if (authorized) granted() else denied()
        }
        awaitRelease(baseline, live)
        println("TunnelModeEnabledListener: stock retries, concurrent lifetime and GC cleanup passed; authorized=$authorized")
    }

    fun disconnect(live: () -> Long, stopCompositor: () -> Unit) {
        val baseline = live()
        retainUntilDisconnect(stopCompositor)
        awaitRelease(baseline, live)
        println("TunnelModeEnabledListener: compositor death preserves errors and releases native ownership after GC")
    }

    private fun retainUntilDisconnect(stopCompositor: () -> Unit) {
        val ready = java.util.concurrent.CountDownLatch(1)
        val listener = object : TunnelModeEnabledListener(java.util.concurrent.Executor { it.run() }) {
            override fun onTunnelModeEnabledChanged(enabled: Boolean) { ready.countDown() }
        }
        TunnelModeEnabledListener.register(listener)
        check(ready.await(5, TimeUnit.SECONDS)) { "No pre-disconnect listener callback" }
        stopCompositor()
        repeat(2) {
            check(runCatching { listener.destroy() }.exceptionOrNull() is RuntimeException)
        }
    }

    private fun awaitRelease(baseline: Long, live: () -> Long) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        do {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        } while (live() != baseline && android.os.SystemClock.uptimeMillis() < deadline)
        check(live() == baseline) { "Tunnel listener ownership leaked: ${live()} != $baseline" }
    }

}
