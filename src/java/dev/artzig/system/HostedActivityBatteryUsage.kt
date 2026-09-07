package dev.artzig.system

/** BatteryStatsImpl.Uid's nested foreground-activity StopwatchTimer and
 * Uid.Proc foreground CPU accounting over the hosted power-supply time base.
 * BatteryStatsImpl itself imports power HAL, BPF and external collector stacks
 * absent here (81-source closure, 20 blockers). This records session durations;
 * it does not estimate energy or implement the Android battery-history database. */
internal class HostedActivityBatteryUsage(private var onBattery: Boolean,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    data class Snapshot(val uid: Int, val foregroundMs: Long, val foregroundCpuMs: Map<String, Long>)
    private class State {
        var nesting = 0
        var foregroundMs = 0L
        val cpu = HashMap<String, Long>()
    }
    private val states = HashMap<Int, State>()
    private var updatedAt = now()

    private fun account(timestamp: Long) {
        val duration = (timestamp - updatedAt).coerceAtLeast(0L)
        if (onBattery) for (state in states.values) {
            if (state.nesting > 0) state.foregroundMs += duration
        }
        updatedAt = maxOf(updatedAt, timestamp)
    }

    @Synchronized fun powerChanged(value: Boolean, timestamp: Long = now()) {
        account(timestamp)
        onBattery = value
    }

    @Synchronized fun activity(uid: Int, resumed: Boolean, timestamp: Long = now()) {
        account(timestamp)
        val state = states.getOrPut(uid) { State() }
        if (resumed) state.nesting++ else if (state.nesting > 0) state.nesting--
    }

    @Synchronized fun foregroundCpu(packageName: String, uid: Int, duration: Long) {
        if (!onBattery) return
        val state = states.getOrPut(uid) { State() }
        state.cpu[packageName] = (state.cpu[packageName] ?: 0L) + duration
    }

    @Synchronized fun snapshot(): List<Snapshot> {
        account(now())
        return states.map { (uid, state) -> Snapshot(uid, state.foregroundMs, state.cpu.toMap()) }
            .sortedBy { it.uid }
    }
}
