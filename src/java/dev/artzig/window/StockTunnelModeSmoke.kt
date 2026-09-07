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
        check(runCatching { TunnelModeEnabledListener.register(listener) }.exceptionOrNull() is RuntimeException)
        check(runCatching { listener.destroy() }.exceptionOrNull() is RuntimeException)
        // Failed remote removal still consumes local ownership. These must be no-ops.
        listener.destroy()
        TunnelModeEnabledListener.register(listener)
        TunnelModeEnabledListener.unregister(listener)
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
}
