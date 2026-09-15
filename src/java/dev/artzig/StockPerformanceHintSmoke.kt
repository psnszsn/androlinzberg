package dev.artzig

import android.app.SystemServiceRegistry
import android.content.Context
import android.os.PerformanceHintManager
import android.os.ServiceManager
import android.os.WorkDuration
import java.lang.reflect.InvocationTargetException

/** Original JNI/NDK client reads real system-service capabilities without a Power HAL. */
internal object StockPerformanceHintSmoke {
    private fun failure(action: () -> Any?): Throwable? =
        runCatching(action).exceptionOrNull().let {
            if (it is InvocationTargetException) it.cause else it
        }

    fun run() {
        check(SystemServiceRegistry.getSystemServiceName(PerformanceHintManager::class.java) ==
            Context.PERFORMANCE_HINT_SERVICE)
        val manager = PerformanceHintManager.create()
        check(manager.preferredUpdateRateNanos == -1L)
        check(PerformanceHintManager.create().preferredUpdateRateNanos == -1L)
        val context: Context = android.app.ActivityThread.currentActivityThread().systemContext
        check(context.getSystemService(PerformanceHintManager::class.java) != null)
        val service = android.os.IHintManager.Stub.asInterface(checkNotNull(
            ServiceManager.getService(Context.PERFORMANCE_HINT_SERVICE)))
        val data = service.clientData
        check(data.preferredRateNanos == -1L && data.powerHalVersion == 0)
        check(!data.supportInfo.usesSessions && !data.supportInfo.headroom.isCpuSupported &&
            !data.supportInfo.headroom.isGpuSupported)
        check(failure { service.getCpuHeadroom(android.os.CpuHeadroomParamsInternal()) }
            is UnsupportedOperationException)
        check(failure { service.getGpuHeadroom(android.os.GpuHeadroomParamsInternal()) }
            is UnsupportedOperationException)

        val create = PerformanceHintManager::class.java.getMethod("createHintSession",
            IntArray::class.java, java.lang.Long.TYPE)
        check(failure { create.invoke(manager, null, 1L) } is NullPointerException)
        check(failure { manager.createHintSession(intArrayOf(), 1) } is IllegalArgumentException)
        for (invalid in listOf(-1L, 0L)) {
            check(failure { manager.createHintSession(intArrayOf(1), invalid) } is IllegalArgumentException)
        }
        check(manager.createHintSession(intArrayOf(android.os.Process.myTid()), 1) == null)

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

        System.err.println("stock-performance-hint: original native client, Binder capabilities, absent Power HAL and Java value contracts passed")
    }
}
