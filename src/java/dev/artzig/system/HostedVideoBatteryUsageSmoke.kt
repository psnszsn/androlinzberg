package dev.artzig.system

object HostedVideoBatteryUsageSmoke {
    fun run() {
        var time = 0L
        val usage = HostedVideoBatteryUsage(false) { time }
        usage.start(10001)
        time = 100
        usage.powerChanged(true)
        time = 200
        usage.start(10001) // Nested codecs share this UID's single timer.
        usage.start(10002)
        time = 300
        usage.stop(10001)
        val nested = usage.snapshot()
        check(nested.totalUs == 200000L)
        check(nested.uidUs == mapOf(10001 to 150000L, 10002 to 50000L))
        time = 400
        usage.stop(10001)
        time = 500
        usage.powerChanged(false)
        time = 600
        usage.stop(10002)
        usage.stop(10002)
        val stopped = usage.snapshot()
        check(stopped.totalUs == 400000L)
        check(stopped.uidUs == mapOf(10001 to 200000L, 10002 to 200000L))
        usage.start(10001)
        usage.start(10001)
        time = 700
        usage.powerChanged(true)
        time = 725
        usage.reset()
        time = 750
        usage.reset()
        usage.stop(10001)
        check(usage.snapshot().totalUs == 425000L)
        check(usage.snapshot().uidUs == mapOf(10001 to 225000L, 10002 to 200000L))
        check(stopped.totalUs == 400000L)
        // Three pooled timers expose fractional microseconds. Reading cannot
        // change subsequent accounting through premature integer rounding.
        val pool = HostedVideoBatteryUsage(true) { time }
        for (uid in 1..3) pool.start(uid)
        time++
        check(pool.snapshot().uidUs.values.all { it == 333L })
        time += 2
        check(pool.snapshot().uidUs.values.all { it == 1000L })
        pool.reset()
        check(pool.snapshot().totalUs == 3000L)
        val poweredPool = HostedVideoBatteryUsage(true) { time }
        for (uid in 1..3) poweredPool.start(uid)
        time++
        poweredPool.powerChanged(false)
        time += 10
        poweredPool.powerChanged(true)
        time += 2
        poweredPool.reset()
        check(poweredPool.snapshot().totalUs == 3000L)
        check(poweredPool.snapshot().uidUs.values.all { it == 999L })
        val denied = HostedBatteryService(HostedActivityManagerService()).batteryStats
        check(runCatching { denied.noteStartVideo(10001) }.exceptionOrNull() is SecurityException)
        check(runCatching { denied.noteStopVideo(10001) }.exceptionOrNull() is SecurityException)
        check(runCatching { denied.noteResetVideo() }.exceptionOrNull() is SecurityException)
    }
}
