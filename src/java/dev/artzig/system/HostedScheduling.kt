package dev.artzig.system

import android.os.Process
import android.util.Slog
import java.util.concurrent.atomic.AtomicBoolean

/** Optional system-service task profiles where the host has no Android cgroups. */
object HostedScheduling {
    private val reported = AtomicBoolean()

    @JvmStatic
    fun setThreadGroup(tid: Int, group: Int): Boolean {
        Process.getThreadPriority(tid) // preserve a missing-thread error
        return try {
            Process.setThreadGroup(tid, group)
            true
        } catch (_: SecurityException) {
            report()
            false
        }
    }

    private fun report() {
        if (reported.compareAndSet(false, true))
            Slog.i("HostedScheduling", "Host task profiles do not allow the requested service scheduling group")
    }
}
