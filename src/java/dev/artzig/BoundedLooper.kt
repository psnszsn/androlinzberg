package dev.artzig

import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

/**
 * Bounded dispatch for host bootstrap and session supervision. The pinned
 * Looper.loopOnce owns queue polling, barriers, tracing, work-source identity,
 * exception handling and recycling. Looper.loop cannot return to the native
 * session owner without permanently quitting the application's main queue.
 */
object BoundedLooper {
    private val loopOnce = Looper::class.java.getDeclaredMethod(
        "loopOnce", Looper::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    private val thresholdOverride = Looper::class.java.getDeclaredMethod("getThresholdOverride")
        .apply { isAccessible = true }
    private val inLoop = Looper::class.java.getDeclaredField("mInLoop").apply { isAccessible = true }

    private fun dispatch(deadline: Long, done: () -> Boolean): Int {
        val looper = checkNotNull(Looper.myLooper()) { "Host dispatch requires a prepared looper" }
        if (done()) return 0
        val handler = Handler.createAsync(looper)
        var expired = false
        val wake = Runnable { expired = true }
        if (!handler.postAtTime(wake, deadline)) return 0
        val previouslyInLoop = inLoop.getBoolean(looper)
        val callingIdentity = Binder.clearCallingIdentity()
        val processIdentity = Binder.clearCallingIdentity()
        var delivered = 0
        try {
            inLoop.setBoolean(looper, true)
            val threshold = thresholdOverride.invoke(null) as Int
            while (!expired && !done()) {
                if (!(loopOnce.invoke(null, looper, processIdentity, threshold) as Boolean)) break
                if (!expired) delivered++
                // Front-of-queue producers can starve even an asynchronous
                // wake message. Yield between dispatches at the host deadline.
                if (SystemClock.uptimeMillis() >= deadline) break
            }
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        } finally {
            handler.removeCallbacks(wake)
            inLoop.setBoolean(looper, previouslyInLoop)
            Binder.restoreCallingIdentity(callingIdentity)
        }
        return delivered
    }

    /** Run real Android dispatch while retaining a deadline for native session work. */
    fun runSlice(durationMillis: Long = 5): Int {
        require(durationMillis >= 0)
        return dispatch(SystemClock.uptimeMillis() + durationMillis) { false }
    }

    /** Bootstrap callbacks may arrive through Binder; inspect completion between slices. */
    fun await(label: String, timeoutMillis: Long = 3000, done: () -> Boolean) {
        require(timeoutMillis >= 0)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!done() && SystemClock.uptimeMillis() < deadline) {
            dispatch(minOf(deadline, SystemClock.uptimeMillis() + 5), done)
        }
        check(done()) { "Timed out waiting for $label" }
    }

    /** Drain runnable work, including messages enqueued by dispatched callbacks. */
    fun drain(): Int {
        var total = 0
        do {
            // A future wake lets stock next() enter its native poll/idle path.
            val delivered = runSlice(1)
            total += delivered
        } while (delivered != 0)
        return total
    }
}
