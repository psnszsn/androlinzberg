package dev.artzig

import android.os.Process
import java.util.concurrent.atomic.AtomicReference

/** Original Process registrar, Android account names and host identity/permission boundaries. */
object StockProcessSmoke {
    @JvmStatic
    fun run() {
        for ((name, uid) in listOf("root" to 0, "system" to 1000, "shell" to 2000,
                "u0_a42" to 10042, "u2_a42" to 210042, "u1_system" to 101000,
                "u0_i7" to 90007, "12345" to 12345)) {
            check(Process.getUidForName(name) == uid) { "UID for $name" }
            check(Process.getGidForName(name) == uid) { "GID for $name" }
        }
        check(Process.getGidForName("u2_a42_cache") == 220042)
        check(Process.getGidForName("all_a42") == 50042)
        check(Process.getUidForName("u2_a42_cache") == -1)
        check(Process.getUidForName("all_a42") == -1)
        check(Process.getUidForName("azk-no-such-android-account") == -1)
        check(Process.getGidForName("azk-no-such-android-account") == -1)
        check(runCatching { Process.getUidForName(null) }.exceptionOrNull() is NullPointerException)
        check(runCatching { Process.getGidForName(null) }.exceptionOrNull() is NullPointerException)
        check(runCatching { Process.setUid(-1) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { Process.setGid(-1) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { Process.setThreadPriority(Int.MAX_VALUE, 0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { Process.setThreadGroup(Process.myTid(), Int.MAX_VALUE) }.exceptionOrNull() is IllegalArgumentException)
        check(Process.setUid(Process.myUid()) == 0 && Process.setGid(android.system.Os.getgid()) == 0)
        check(Process.getUidForPid(Process.myPid()) == Process.myUid())
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                val tid = Process.myTid()
                check(tid != Process.myPid())
                check(Process.getUidForPid(tid) == Process.myUid())
                for (path in listOf("/proc/$tid/status", "/proc/self/task/$tid/status", "/proc/thread-self/status")) {
                    val values = LongArray(4)
                    Process.readProcLines(path, arrayOf("Tgid:", "Pid:", "Uid:", "Gid:"), values)
                    check(values.contentEquals(longArrayOf(Process.myPid().toLong(), tid.toLong(),
                        Process.myUid().toLong(), android.system.Os.getgid().toLong()))) { "$path: ${values.toList()}" }
                }
                fun kernelNice(): Int = java.io.File("/proc/self/task/$tid/stat").readText()
                    .substringAfterLast(") ").split(' ')[16].toInt() // proc stat field 19
                val inherited = Process.getThreadPriority(tid)
                val boost = maxOf(inherited - 1, -20)
                Process.setThreadPriority(tid, boost)
                val before = Process.getThreadPriority(tid)
                check(before == boost || before == inherited)
                check(before == kernelNice())
                val lower = minOf(before + 1, 19)
                Process.setThreadPriority(tid, lower)
                check(Process.getThreadPriority(tid) == lower)
                Process.setThreadPriority(tid, before)
                val restored = Process.getThreadPriority(tid)
                check(restored == before || restored == lower)
                check(restored == kernelNice())
                repeat(100) { check(Process.getUidForName("u3_a99") == 310099) }
            } catch (error: Throwable) { failure.set(error) }
        }, "stock-process-contract")
        thread.start()
        thread.join(5000)
        check(!thread.isAlive) { "Process contract test stalled" }
        failure.get()?.let { throw it }
        check(Process.getUidForName("system") == 1000)
        check(Process.getPss(Process.myPid()) > 0)
        check(Process.getRss(Process.myPid())[0] > 0)
        println("stock-process: PASS original registrar, accounts, thread identity and host scheduling modes")
    }
}
