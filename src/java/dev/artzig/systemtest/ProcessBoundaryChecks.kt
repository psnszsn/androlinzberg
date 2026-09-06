package dev.artzig.systemtest

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import dev.artzig.system.HostActivityManagerAccess as Access
import java.util.concurrent.atomic.AtomicReference

internal object ProcessBoundaryChecks {
    fun local() {
        procParsing()
        procQueries()
        processHistory()
        val pid = Int.MAX_VALUE
        val owner = Binder()
        val replacement = Binder()
        val denied = mutableSetOf("test.denied")
        Access.attached(pid, owner, denied)
        denied.clear()
        try {
            check(Access.checkComponentPermission("test.denied", pid, 10001, 10001, true) ==
                PackageManager.PERMISSION_DENIED)
            check(Access.checkComponentPermission(null, pid, 10001, 10001, false) ==
                PackageManager.PERMISSION_GRANTED)
            Access.attached(pid, replacement, setOf("test.denied"))
            Access.died(pid, owner)
            check(Access.checkComponentPermission("test.denied", pid, Process.SYSTEM_UID, -1, true) ==
                PackageManager.PERMISSION_DENIED)
            Access.died(pid, replacement)
            check(Access.checkComponentPermission("test.denied", pid, 10001, 10001, false) ==
                PackageManager.PERMISSION_GRANTED)
            check(Access.checkComponentPermission("test.denied", Process.myPid(), 99001, -1, false) ==
                PackageManager.PERMISSION_GRANTED)
        } finally { Access.died(pid, replacement); Access.died(pid, owner) }

        val item = Any()
        val component = ComponentName("test", "test.Activity")
        check(Access.ItemMatcher().match(null, null))
        val exact = Access.ItemMatcher().apply { build("test/.Activity") }
        check(exact.match(item, component) && !exact.match(item, ComponentName("other", "other.Activity")))
        val identity = Access.ItemMatcher().apply { build(System.identityHashCode(item).toString(16)) }
        check(identity.match(item, null) && !identity.match(Any(), component))
        val substring = Access.ItemMatcher()
        check(substring.build(arrayOf("ignored", "Activity", "--", "other"), 1) == 3)
        check(substring.match(item, component) && !substring.match(item, ComponentName("other", "other.Name")))

        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                val tid = Process.myTid()
                check(Process.getThreadScheduler(tid) == Process.SCHED_OTHER)
                check(!Access.scheduleAsRegularPriority(Int.MAX_VALUE, true))
                var badPolicy = false
                try { Process.setThreadScheduler(tid, -1, 0) }
                catch (_: IllegalArgumentException) { badPolicy = true }
                check(badPolicy)
                try {
                    val accepted = Access.scheduleAsFifoPriority(tid, true)
                    val policy = Process.getThreadScheduler(tid) and Process.SCHED_RESET_ON_FORK.inv()
                    check(policy == if (accepted) Process.SCHED_FIFO else Process.SCHED_OTHER) {
                        "A denied scheduling request was reported as successful"
                    }
                } finally { check(Access.scheduleAsRegularPriority(tid, true)) }
            } catch (error: Throwable) { failure.set(error) }
        }, "scheduler-contract")
        thread.start()
        thread.join(5000)
        check(!thread.isAlive) { "Scheduler contract test stalled" }
        failure.get()?.let { throw it }
        println("ProcessBoundary: permission denial ownership, dump matching and scheduler error semantics passed")
    }

    private fun procQueries() {
        val directory = java.nio.file.Files.createTempDirectory("process-list").toFile()
        try {
            check(Process.getPids(directory.path, null) == null)
            listOf("20", "3", "001", "not-a-pid", "-8").forEach {
                check(java.io.File(directory, it).createNewFile())
            }
            val reused = IntArray(3) { 99 }
            check(Process.getPids(directory.path, reused) === reused)
            check(reused.contentEquals(intArrayOf(1, 3, 20)))
            val grown = Process.getPids(directory.path, IntArray(1))
            check(grown.contentEquals(intArrayOf(1, 3, 20, -1)))
            val padded = IntArray(8)
            check(Process.getPids(directory.path, padded) === padded)
            check(padded.drop(3).all { it == -1 })
            check(Process.getPids(directory.path + "/absent", reused) == null)
            check(reused.contentEquals(intArrayOf(1, 3, 20)))
        } finally { directory.deleteRecursively() }
        val self = Process.myPid()
        val pids = Process.getPids("/proc", null).takeWhile { it >= 0 }
        check(self in pids && pids == pids.sorted())
        val command = java.io.File("/proc/self/cmdline").readBytes().takeWhile {
            it != 0.toByte() && it != ' '.code.toByte()
        }.toByteArray().toString(Charsets.UTF_8)
        val matches = Process.getPidsForCommands(arrayOf(command, command))
        check(matches.count { it == self } == 1)
        check(Process.getPidsForCommands(arrayOf("azk-absent-command-123456")).isEmpty())
        check(Process.getPss(self) > 0 && Process.getPss(Int.MAX_VALUE) == -1L)
        val rss = Process.getRss(self)
        check(rss.size == 5 && rss[0] > 0 && rss.all { it >= 0 })
        check(Process.getRss(Int.MAX_VALUE).contentEquals(LongArray(5)))
        val cpu = com.android.internal.os.ProcessCpuTracker(false)
        cpu.init()
        cpu.update()
        check(cpu.countStats() > 0 && cpu.getCpuTimeForPid(self) >= 0)
        println("Process: sorted PID reuse, command lookup, PSS/RSS and stock CPU tracker update passed")
    }

    private fun procParsing() {
        val space = Process.PROC_SPACE_TERM or Process.PROC_COMBINE
        val bytes = "xx(name with space)  \"quoted words\" -17ms 1.25e+oops Z\u0001bad".toByteArray()
        val strings = arrayOfNulls<String>(5)
        val longs = LongArray(5) { 99 }
        val floats = FloatArray(5) { 99f }
        check(Process.parseProcLine(bytes, 2, bytes.size, intArrayOf(
            space or Process.PROC_PARENS or Process.PROC_OUT_STRING,
            space or Process.PROC_QUOTES or Process.PROC_OUT_STRING,
            space or Process.PROC_OUT_LONG,
            space or Process.PROC_OUT_FLOAT,
            Process.PROC_ZERO_TERM or Process.PROC_CHAR or Process.PROC_OUT_LONG or Process.PROC_OUT_STRING
        ), strings, longs, floats))
        check(strings.contentEquals(arrayOf("name with space", "quoted words", null, null, "Z?bad")))
        check(longs.contentEquals(longArrayOf(99, 99, -17, 99, 'Z'.code.toLong())))
        check(floats.contentEquals(floatArrayOf(99f, 99f, 99f, 1.25f, 99f)))
        check(bytes.toString(Charsets.UTF_8).endsWith("Z?bad"))

        val partial = longArrayOf(7, 8)
        check(!Process.parseProcLine("42 ".toByteArray(), 0, 3,
            intArrayOf(space or Process.PROC_OUT_LONG, space or Process.PROC_OUT_LONG), null, partial, null))
        check(partial.contentEquals(longArrayOf(42, 8)))
        val numbers = "9223372036854775808 -9223372036854775809 0x1.8p+1".toByteArray()
        val out = LongArray(2)
        val real = FloatArray(3)
        check(Process.parseProcLine(numbers, 0, numbers.size, intArrayOf(
            space or Process.PROC_OUT_LONG, space or Process.PROC_OUT_LONG,
            Process.PROC_OUT_FLOAT), null, out, real))
        check(out.contentEquals(longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE)) && real[2] == 3f)
        check(!Process.readProcFile("/proc/2147483647/stat", intArrayOf(Process.PROC_OUT_LONG), null, out, null))
        var badRange = false
        try { Process.parseProcLine(bytes, -1, bytes.size, intArrayOf(), null, null, null) }
        catch (_: IndexOutOfBoundsException) { badRange = true }
        check(badRange)
        val file = java.io.File.createTempFile("proc-lines", ".txt")
        try {
            file.writeText("Size: 123 kB\nSigned: -7\nCount: 42things\n")
            val values = longArrayOf(9, 9, 9, 9, 9)
            Process.readProcLines(file.absolutePath, arrayOf("Size:", "Signed:", "Count:", "Absent:"), values)
            check(values.contentEquals(longArrayOf(123, 0, 42, 0, 9)))
            Process.readProcLines("/proc/2147483647/stat", arrayOf("Size:"), values)
            check(values[0] == 123L)
            var tooShort = false
            try { Process.readProcLines(file.absolutePath, arrayOf("Size:"), longArrayOf()) }
            catch (_: IllegalArgumentException) { tooShort = true }
            check(tooShort)
        } finally { check(file.delete()) }
        println("ProcessBoundary: proc field indices, quoting, numeric prefixes, mutation and partial reads passed")
    }

    private fun processHistory() {
        val history = dev.artzig.system.HostedProcessHistory(2)
        val owner = Binder()
        val state = byteArrayOf(1, 2)
        history.summary(owner, state)
        state[0] = 9
        fun record(pidValue: Int, uidValue: Int, pkg: String, time: Long) =
            android.app.ApplicationExitInfo().apply {
                pid = pidValue; realUid = uidValue; packageUid = uidValue
                packageName = pkg; processName = pkg; timestamp = time
                reason = android.app.ApplicationExitInfo.REASON_UNKNOWN
            }
        val first = record(10, 10001, "a", 1)
        history.died(owner, first)
        first.pid = 99
        val result = history.query("a", 10001, 10, 1).single()
        check(result.pid == 10 && result.processStateSummary.contentEquals(byteArrayOf(1, 2)))
        result.processStateSummary!![0] = 8
        check(history.query("a", 10001, 0, 0).single().processStateSummary!![0] == 1.toByte())
        history.died(Binder(), record(11, 10001, "a", 2))
        history.died(Binder(), record(12, 10001, "a", 3))
        history.died(Binder(), record(13, 10001, "b", 4))
        history.died(Binder(), record(14, 10002, "c", 5))
        check(history.query("a", 10001, 0, 0).map { it.pid } == listOf(12, 11))
        check(history.query(null, 10001, 0, 2).map { it.pid } == listOf(13, 12))
        check(history.query(null, 10002, 0, 0).single().pid == 14)
        check(history.query(null, 10001, 10, 0).isEmpty())
        var oversized = false
        try { history.summary(owner, ByteArray(129)) }
        catch (_: IllegalArgumentException) { oversized = true }
        check(oversized)
        println("ProcessBoundary: bounded exit history, uid/pid filters and copied state summaries passed")
    }
}
