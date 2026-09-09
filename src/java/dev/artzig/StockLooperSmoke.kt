package dev.artzig

import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.os.ThreadLocalWorkSource
import java.util.concurrent.atomic.AtomicReference

/** Observable pinned Looper behavior, including its native idle/poll path. */
object StockLooperSmoke {
    fun run() {
        BoundedLooper.drain()
        val looper = checkNotNull(Looper.myLooper())
        val handler = Handler(looper)
        val due = SystemClock.uptimeMillis() + 35
        var deliveredAt = 0L
        handler.postAtTime({ deliveredAt = SystemClock.uptimeMillis() }, due)
        BoundedLooper.await("delayed message") { deliveredAt != 0L }
        check(deliveredAt >= due) { "Delayed work ran before its deadline" }

        var deliveries = 0
        val producer = Thread({ repeat(128) { handler.post { deliveries++ } } }, "Looper-producer")
        producer.start()
        producer.join()
        BoundedLooper.await("cross-thread messages") { deliveries == 128 }
        check(deliveries == 128)
        var priorityDeliveries = 0
        val priorityFlood = object : Runnable {
            override fun run() {
                priorityDeliveries++
                if (priorityDeliveries < 10000) handler.postAtFrontOfQueue(this)
            }
        }
        handler.postAtFrontOfQueue(priorityFlood)
        try {
            BoundedLooper.runSlice(0)
            check(priorityDeliveries == 1) { "Front-of-queue work starved the host deadline" }
        } finally { handler.removeCallbacks(priorityFlood) }
        dispatchContracts(looper)
        idleAndQuit()
    }

    private fun dispatchContracts(looper: Looper) {
        val observed = mutableListOf<String>()
        val expected = IllegalStateException("Looper callback fixture")
        val originalWorkSource = ThreadLocalWorkSource.getUid()
        val originalCaller = Binder.getCallingUid() to Binder.getCallingPid()
        val handler = object : Handler(looper) {
            override fun handleMessage(message: Message) {
                check(ThreadLocalWorkSource.getUid() == 321)
                observed += "callback:${message.what}"
                if (message.what == 43) throw expected
            }
        }
        Looper.setObserver(object : Looper.Observer {
            override fun messageDispatchStarting(): Any = Thread.currentThread()
            override fun messageDispatched(token: Any?, message: Message) {
                if (message.target === handler) {
                    check(token === Thread.currentThread())
                    observed += "completed:${message.what}"
                }
            }
            override fun dispatchingThrewException(token: Any?, message: Message, exception: Exception) {
                if (message.target === handler) {
                    check(token === Thread.currentThread() && exception === expected) { "Unexpected dispatch failure: $exception" }
                    observed += "failed:${message.what}"
                }
            }
        })
        fun send(what: Int) {
            // Handler.enqueueMessage captures the sending thread's attribution.
            val token = ThreadLocalWorkSource.setUid(321)
            try { check(handler.sendEmptyMessage(what)) }
            finally { ThreadLocalWorkSource.restore(token) }
        }
        try {
            send(42)
            BoundedLooper.drain()
            check(observed == listOf("callback:42", "completed:42")) { observed.toString() }
            send(43)
            check(runCatching { BoundedLooper.drain() }.exceptionOrNull() === expected)
            check(observed.takeLast(2) == listOf("callback:43", "failed:43"))
            check(ThreadLocalWorkSource.getUid() == originalWorkSource)
            check((Binder.getCallingUid() to Binder.getCallingPid()) == originalCaller)
            send(44)
            BoundedLooper.drain()
            check(observed.takeLast(2) == listOf("callback:44", "completed:44"))
        } finally { Looper.setObserver(null) }
    }

    private fun idleAndQuit() {
        val failure = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                Looper.prepare()
                val looper = checkNotNull(Looper.myLooper())
                val handler = Handler(looper)
                val observed = mutableListOf<String>()
                val firstIdle = java.util.concurrent.CountDownLatch(1)
                looper.queue.addIdleHandler {
                    observed += "idle"
                    if (observed.size == 1) firstIdle.countDown()
                    else handler.post { observed += "quit"; looper.quit() }
                    true
                }
                val producer = Thread({
                    firstIdle.await()
                    handler.post { observed += "message" }
                }, "Looper-idle-wake")
                producer.start()
                Looper.loop()
                producer.join()
                check(observed == listOf("idle", "message", "idle", "quit")) { observed.toString() }
                check(!handler.post { error("Work ran after quit") })
            } catch (error: Throwable) { failure.set(error) }
        }, "Looper-idle-quit")
        worker.start()
        worker.join(5000)
        check(!worker.isAlive) { "Looper did not quit" }
        failure.get()?.let { throw it }
    }
}
