package dev.artzig

import android.app.SystemServiceRegistry
import android.content.Context
import android.os.PerformanceHintManager
import android.os.ServiceManager
import android.os.WorkDuration
import java.lang.reflect.InvocationTargetException

/** Stock validation and the documented absent native performance-hint service. */
internal object StockPerformanceHintSmoke {
    private fun failure(action: () -> Any?): Throwable? =
        runCatching(action).exceptionOrNull().let {
            if (it is InvocationTargetException) it.cause else it
        }

    fun run() {
        check(SystemServiceRegistry.getSystemServiceName(PerformanceHintManager::class.java) ==
            Context.PERFORMANCE_HINT_SERVICE)
        repeat(2) {
            val error = failure { PerformanceHintManager.create() }
            check(error is ServiceManager.ServiceNotFoundException &&
                error.message?.contains(Context.PERFORMANCE_HINT_SERVICE) == true) { error.toString() }
        }

        // Exercise Java argument checks without claiming a native manager was acquired.
        val constructor = PerformanceHintManager::class.java.getDeclaredConstructor(
            java.lang.Long.TYPE).apply { isAccessible = true }
        val unacquired = constructor.newInstance(0L)
        val create = PerformanceHintManager::class.java.getMethod("createHintSession",
            IntArray::class.java, java.lang.Long.TYPE)
        check(failure { create.invoke(unacquired, null, 1L) } is NullPointerException)
        check(failure { unacquired.createHintSession(intArrayOf(), 1) } is IllegalArgumentException)
        for (invalid in listOf(-1L, 0L)) {
            check(failure { unacquired.createHintSession(intArrayOf(1), invalid) } is IllegalArgumentException)
        }
        check(failure { unacquired.createHintSession(intArrayOf(1), 1) } is IllegalStateException)
        check(failure { unacquired.preferredUpdateRateNanos } is IllegalStateException)

        val duration = WorkDuration()
        check(duration.actualTotalDurationNanos == 0L && duration.actualCpuDurationNanos == 0L &&
            duration.actualGpuDurationNanos == 0L && duration.workPeriodStartTimestampNanos == 0L)
        duration.workPeriodStartTimestampNanos = 100
        duration.actualTotalDurationNanos = 20
        duration.actualCpuDurationNanos = 12
        duration.actualGpuDurationNanos = 8
        val equal = WorkDuration(100, 20, 12, 8)
        check(duration == equal && duration.hashCode() == equal.hashCode())
        for (different in listOf(WorkDuration(101, 20, 12, 8), WorkDuration(100, 21, 12, 8),
                WorkDuration(100, 20, 13, 8), WorkDuration(100, 20, 12, 9))) {
            check(duration != different)
        }
        for (invalid in listOf(-1L, 0L)) {
            check(failure { duration.actualTotalDurationNanos = invalid } is IllegalArgumentException)
            check(failure { duration.workPeriodStartTimestampNanos = invalid } is IllegalArgumentException)
        }
        check(failure { duration.actualCpuDurationNanos = -1 } is IllegalArgumentException)
        check(failure { duration.actualGpuDurationNanos = -1 } is IllegalArgumentException)
        check(duration == equal) // Rejected setters leave the measurement unchanged.
        duration.actualCpuDurationNanos = 0
        duration.actualGpuDurationNanos = 0

        // Hidden constructor represents a closed session, not an acquired capability.
        val closed = PerformanceHintManager.Session(0)
        repeat(2) { closed.close() }
        closed.setThreads(intArrayOf()) // Stock closed-session check precedes validation.
        PerformanceHintManager.Session::class.java.getMethod("setThreads", IntArray::class.java)
            .invoke(closed, null)
        check(failure { closed.updateTargetWorkDuration(0) } is IllegalArgumentException)
        check(failure { closed.reportActualWorkDuration(0L) } is IllegalArgumentException)
        check(failure { closed.sendHint(-1) } is IllegalArgumentException)
        for (invalid in listOf(WorkDuration(), WorkDuration(0, 1, 1, 0),
                WorkDuration(1, 0, 1, 0), WorkDuration(1, 1, -1, 1),
                WorkDuration(1, 1, 1, -1), WorkDuration(1, 1, 0, 0))) {
            check(failure { closed.reportActualWorkDuration(invalid) } is IllegalArgumentException)
        }

        // No legal manager/session can reach JNI operations when acquisition failed.
        // Reject hidden invalid-handle calls explicitly instead of accepting fake hints.
        for (action in listOf<() -> Any?>(
                { closed.updateTargetWorkDuration(1) },
                { closed.reportActualWorkDuration(1L) },
                { closed.reportActualWorkDuration(equal) },
                { closed.sendHint(PerformanceHintManager.Session.CPU_LOAD_UP) },
                { closed.setPreferPowerEfficiency(true) },
                { closed.threadIds })) {
            val error = failure(action)
            check(error is IllegalStateException && error.message?.contains("performance_hint") == true)
        }
        System.err.println("stock-performance-hint: service absence, duration values/validation and closed-session contracts passed")
    }
}
