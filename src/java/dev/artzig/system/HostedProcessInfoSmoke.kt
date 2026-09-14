package dev.artzig.system

import android.app.ActivityManager
import android.os.Parcel

object HostedProcessInfoSmoke {
    fun run() {
        val lock = Any()
        var permitted = true
        var checks = 0
        var scoreReads = 0
        val entries = mapOf(
            11 to HostedProcessInfoService.Entry(ActivityManager.PROCESS_STATE_SERVICE),
            12 to HostedProcessInfoService.Entry(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY, 30),
            13 to HostedProcessInfoService.Entry(ActivityManager.PROCESS_STATE_CACHED_EMPTY, 40),
            14 to HostedProcessInfoService.Entry(ActivityManager.PROCESS_STATE_CACHED_EMPTY, 40))
        val service = HostedProcessInfoService(lock, { pid ->
            check(Thread.holdsLock(lock))
            entries[pid]
        }, { checks++; if (!permitted) throw SecurityException("score access") }, {
            scoreReads++; check(it == 11); 321
        })
        val pids = intArrayOf(0, 11, 12, 13, 14, -1)
        val states = IntArray(pids.size)
        val scores = IntArray(pids.size)
        service.getProcessStatesAndOomScoresFromPids(pids, states, scores)
        check(states.contentEquals(intArrayOf(20, 10, 2, 2, 2, 20)))
        check(scores.contentEquals(intArrayOf(-10000, 321, -1, -2, -1, -10000)))
        check(checks == 1 && scoreReads == 1)
        service.getProcessStatesFromPids(pids, states)
        service.getProcessStatesAndOomScoresFromPids(pids, states, null)
        check(checks == 1 && scoreReads == 1)
        permitted = false
        check(runCatching { service.getProcessStatesAndOomScoresFromPids(null, null, scores) }
            .exceptionOrNull() is SecurityException)
        check(runCatching { service.getProcessStatesFromPids(null, states) }.exceptionOrNull() is NullPointerException)
        check(runCatching { service.getProcessStatesFromPids(pids, null) }.exceptionOrNull() is NullPointerException)
        check(runCatching { service.getProcessStatesFromPids(pids, IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        permitted = true
        check(runCatching { service.getProcessStatesAndOomScoresFromPids(pids, states, IntArray(1)) }
            .exceptionOrNull() is IllegalArgumentException)
        service.getProcessStatesAndOomScoresFromPids(intArrayOf(), intArrayOf(), intArrayOf())

        // Exercise the generated wire decoder used by the original native client.
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.os.IProcessInfoService")
            data.writeIntArray(pids)
            data.writeInt(pids.size)
            data.writeInt(pids.size)
            check(service.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 1, data, reply, 0))
            reply.readException()
            check(reply.createIntArray()!!.contentEquals(states))
            check(reply.createIntArray()!!.contentEquals(scores))
            check(reply.dataAvail() == 0)
        } finally { data.recycle(); reply.recycle() }

        val ams = HostedActivityManagerService()
        check(runCatching { ams.processInfo.getProcessStatesAndOomScoresFromPids(null, null, scores) }
            .exceptionOrNull() is SecurityException)
        val absent = IntArray(1)
        ams.processInfo.getProcessStatesFromPids(intArrayOf(0), absent)
        check(absent[0] == ActivityManager.PROCESS_STATE_NONEXISTENT)

        val nativePid = android.os.Process.myPid()
        val linux = HostedProcessInfoService(lock, { HostedProcessInfoService.Entry(ActivityManager.PROCESS_STATE_TOP) }, {})
        val score = IntArray(1)
        linux.getProcessStatesAndOomScoresFromPids(intArrayOf(nativePid), IntArray(1), score)
        check(score[0] == java.io.File("/proc/$nativePid/oom_score_adj").readText().trim().toInt())
        val pending = HostedPendingTopUids { 17L }
        pending.add(10001, 12)
        check(pending.time(10001, 12) == 17L && pending.time(10001, 13) == -1L)
        pending.delete(10001, 16)
        check(pending.time(10001, 12) == 17L)
        pending.delete(10001, 17)
        check(pending.time(10001, 12) == -1L)
    }
}
