package com.android.server

import android.os.Handler
import android.os.HandlerThread
import com.android.server.am.DropboxRateLimiter
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stock checker state/callback contracts and the host report-store boundary. */
object StockWatchdogSmoke {
    private class TestClock : Clock() {
        var now = 10_000L
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis() = now
    }

    fun run() {
        checkMonitors()
        checkReports()
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Watchdog fixture handler timed out" }
    }

    private fun checkMonitors() {
        val thread = HandlerThread("stock-watchdog-fixture").also { it.start() }
        val handler = Handler(thread.looper)
        val releases = ArrayList<CountDownLatch>()
        fun blockHandler(): CountDownLatch {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also { releases.add(it) }
            check(handler.post { entered.countDown(); await(release) })
            await(entered)
            return release
        }
        fun drain() {
            val done = CountDownLatch(1)
            check(handler.post { done.countDown() })
            await(done)
        }
        try {
            val lock = Any()
            val clock = TestClock()
            val events = ArrayList<Int>()
            val checker = Watchdog.HandlerChecker(handler, "fixture", lock, clock)
            val release = blockHandler()
            synchronized(lock) {
                checker.addMonitorLocked { events.add(1) }
                check(checker.completionStateLocked == Watchdog.COMPLETED)
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.WAITING)
                clock.now += 250
                check(checker.completionStateLocked == Watchdog.WAITED_UNTIL_PRE_WATCHDOG)
                // Scheduling an in-flight check must not postpone its deadline.
                checker.scheduleCheckLocked(1_000)
                checker.addMonitorLocked { events.add(2) }
                clock.now += 750
                check(checker.completionStateLocked == Watchdog.OVERDUE)
                check(checker.thread === thread && checker.name == "fixture")
                check("Blocked in handler" in checker.describeBlockedStateLocked())
            }
            release.countDown()
            drain()
            synchronized(lock) { check(checker.completionStateLocked == Watchdog.COMPLETED) }
            check(events == listOf(1)) // newly registered monitors join the next check
            val next = blockHandler()
            synchronized(lock) { checker.scheduleCheckLocked(1_000) }
            next.countDown()
            drain()
            check(events == listOf(1, 1, 2))

            val paused = blockHandler()
            synchronized(lock) {
                checker.pauseLocked("outer")
                checker.pauseLocked("inner")
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.COMPLETED)
                checker.resumeLocked("inner complete")
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.COMPLETED)
                checker.resumeLocked("outer complete")
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.WAITING)
                clock.now += 1_000
                check(checker.completionStateLocked == Watchdog.OVERDUE)
                checker.pauseForLocked(500, "timed pause")
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.COMPLETED)
                clock.now += 499
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.COMPLETED)
                clock.now++
                checker.scheduleCheckLocked(1_000)
                check(checker.completionStateLocked == Watchdog.WAITING)
            }
            paused.countDown()
            drain()
        } finally {
            releases.forEach { it.countDown() }
            thread.quitSafely()
            thread.join(5_000)
            check(!thread.isAlive)
        }
    }

    private fun checkReports() {
        val records = ArrayList<Pair<String, String>>()
        var enabled = false
        var maxBytes = 1_024
        var now = 10_000L
        val store = object : HostedWatchdogEnvironment.ReportStore {
            override fun enabled(tag: String) = enabled
            override fun maxBytes(tag: String) = maxBytes
            override fun write(tag: String, text: String) { records.add(tag to text) }
        }
        val limiter = DropboxRateLimiter { now }
        val host = HostedWatchdogEnvironment(store, limiter) { "test.media" }
        val stack = Files.createTempFile("watchdog-stack-", ".txt").toFile()
        try {
            stack.writeText("stack-prefix\n" + "x".repeat(4_096) + "stack-tail")
            val errorId = UUID.randomUUID()
            host.report("watchdog", "blocked service\n", stack, errorId)
            check(records.isEmpty())
            enabled = true
            host.report("watchdog", "blocked service\n", stack, errorId)
            val (tag, text) = records.single()
            check(tag == "system_server_watchdog")
            check("Process: system_server\n" in text && "ErrorId: $errorId\n" in text)
            check("Dropped-Count: 0\n" in text && "blocked service\n" in text)
            check("Data File: ${stack.absolutePath}\n" in text)
            check("stack-prefix" in text && "[[TRUNCATED]]" in text && "stack-tail" !in text)
            check(text.endsWith("End Data File\n"))
            check(host.isMediaProcess("test.media") && !host.isMediaProcess("other"))
            repeat(5) { host.report("watchdog", "blocked service\n", null, errorId) }
            check(records.size == 6)
            repeat(2) { host.report("watchdog", "rate limited\n", null, errorId) }
            check(records.size == 6)
            now += 600_001
            maxBytes = 0
            host.report("watchdog", "new interval\n", stack, errorId)
            val resumed = records.last().second
            check(records.size == 7 && "Dropped-Count: 2\n" in resumed)
            check("stack-prefix" !in resumed && resumed.endsWith("End Data File\n"))
            check("/proc/self/status" in HostedWatchdogEnvironment.processDiagnostics())
            check(HostedWatchdogEnvironment.nativePids().all { it > 0 })
        } finally { stack.delete() }
    }
}
