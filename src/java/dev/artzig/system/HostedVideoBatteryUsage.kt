package dev.artzig.system

/**
 * BatteryStatsImpl.noteVideoOn/Off/Reset and Uid's pooled StopwatchTimer, over
 * the host battery time base. BatteryStatsImpl requires absent power collectors
 * and history infrastructure. Retain actual session durations in microseconds;
 * simultaneous UIDs share the pooled duration, nested codecs within one UID do
 * not. Reading a snapshot does not advance/round the timers.
 */
internal class HostedVideoBatteryUsage(private var onBattery: Boolean,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    data class Snapshot(val totalUs: Long, val uidUs: Map<Int, Long>)
    private class Timer {
        var nesting = 0
        var accumulated = 0L
        var started = 0L
    }
    private val timers = HashMap<Int, Timer>()
    private var batteryUs = 0L
    private var powerSince = now()
    private val total = Timer()
    private val uidResolver = com.android.server.power.stats.PowerStatsUidResolver()
    private val isolatedVideo = HashMap<Int, Int>()

    @Synchronized fun isolatedUidAdded(uid: Int, owner: Int) = uidResolver.noteIsolatedUidAdded(uid, owner)
    @Synchronized fun isolatedUidRemoved(uid: Int, owner: Int) = uidResolver.noteIsolatedUidRemoved(uid, owner)

    // BatteryStatsImpl.mapUid. The host has no delayed CPU collector to hold
    // removed mappings: retain them until outstanding codec notifications finish.
    private fun owner(uid: Int): Int = if (android.os.Process.isSdkSandboxUid(uid))
        android.os.Process.getAppUidForSdkSandboxUid(uid) else uidResolver.mapUid(uid)

    private fun batteryTime(timestamp: Long): Long = batteryUs +
        if (onBattery) (timestamp - powerSince).coerceAtLeast(0L) * 1000 else 0L

    private fun active() = timers.values.filter { it.nesting > 0 }

    private fun refresh(time: Long) {
        val pool = active()
        for (timer in pool) {
            timer.accumulated += (time - timer.started).coerceAtLeast(0L) / pool.size
            timer.started = time
        }
    }

    @Synchronized fun powerChanged(value: Boolean, timestamp: Long = now()) {
        batteryUs = batteryTime(timestamp)
        if (onBattery && !value) {
            // StopwatchTimer.onTimeStopped commits each timer's integer share.
            refresh(batteryUs)
            if (total.nesting > 0) {
                total.accumulated += batteryUs - total.started
                total.started = batteryUs
            }
        }
        powerSince = timestamp
        onBattery = value
    }

    @Synchronized fun start(uid: Int, timestamp: Long = now()) {
        val mapped = owner(uid)
        if (uidResolver.mapUid(uid) != uid) {
            uidResolver.retainIsolatedUid(uid)
            isolatedVideo[uid] = (isolatedVideo[uid] ?: 0) + 1
        }
        val time = batteryTime(timestamp)
        if (total.nesting++ == 0) total.started = time
        val timer = timers.getOrPut(mapped) { Timer() }
        if (timer.nesting == 0) {
            refresh(time)
            timer.started = time
        }
        timer.nesting++
    }

    @Synchronized fun stop(uid: Int, timestamp: Long = now()) {
        if (total.nesting == 0) return
        val mapped = owner(uid)
        isolatedVideo[uid]?.let { count ->
            if (count == 1) isolatedVideo.remove(uid) else isolatedVideo[uid] = count - 1
            uidResolver.releaseIsolatedUid(uid)
        }
        val time = batteryTime(timestamp)
        if (--total.nesting == 0) total.accumulated += time - total.started
        val timer = timers[mapped] ?: return
        if (timer.nesting == 1) refresh(time)
        if (timer.nesting > 0) timer.nesting--
    }

    @Synchronized fun reset(timestamp: Long = now()) {
        if (total.nesting == 0) return
        val time = batteryTime(timestamp)
        total.accumulated += time - total.started
        total.nesting = 0
        refresh(time)
        timers.values.forEach { it.nesting = 0 }
        for ((uid, count) in isolatedVideo) repeat(count) { uidResolver.releaseIsolatedUid(uid) }
        isolatedVideo.clear()
    }

    @Synchronized fun snapshot(): Snapshot {
        val time = batteryTime(now())
        val count = active().size
        return Snapshot(total.accumulated + if (total.nesting > 0) time - total.started else 0L,
            timers.mapValues { (_, timer) -> timer.accumulated +
                if (timer.nesting > 0) (time - timer.started) / count else 0L }.toSortedMap())
    }
}
