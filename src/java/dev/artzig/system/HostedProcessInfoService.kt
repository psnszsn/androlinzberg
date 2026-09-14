package dev.artzig.system

import android.app.ActivityManager
import android.os.IProcessInfoService
import java.io.File
import java.io.IOException

/**
 * Follows pinned AMS.ProcessInfoService/getProcessStatesAndOomScoresForPIDs:
 * permission precedes validation, registry snapshots are locked, missing PIDs
 * retain NONEXISTENT/INVALID_ADJ, and the newest pending TOP PID wins.
 * AMS/ProcessStateRecord require Android's OomAdjuster and LMKD. The hosted
 * registry supplies framework state; Linux supplies the actual oom_score_adj.
 */
internal class HostedProcessInfoService(
    private val registryLock: Any,
    private val process: (Int) -> Entry?,
    private val enforceScorePermission: () -> Unit,
    private val oomScore: (Int) -> Int = ::linuxOomScore,
) : IProcessInfoService.Stub() {
    data class Entry(val state: Int, val pendingTopTime: Long = -1)

    override fun getProcessStatesFromPids(pids: IntArray?, states: IntArray?) = query(pids, states, null)

    override fun getProcessStatesAndOomScoresFromPids(pids: IntArray?, states: IntArray?, scores: IntArray?) =
        query(pids, states, scores)

    private fun query(pids: IntArray?, states: IntArray?, scores: IntArray?) {
        if (scores != null) enforceScorePermission()
        if (pids == null) throw NullPointerException("pids")
        if (states == null) throw NullPointerException("states")
        if (pids.size != states.size) throw IllegalArgumentException("pids and states arrays have different lengths!")
        if (scores != null && pids.size != scores.size)
            throw IllegalArgumentException("pids and scores arrays have different lengths!")
        synchronized(registryLock) {
            var newestIndex = -1
            var newestTime = Long.MIN_VALUE
            for (i in pids.indices) {
                val entry = process(pids[i])
                if (entry == null) {
                    states[i] = ActivityManager.PROCESS_STATE_NONEXISTENT
                    if (scores != null) scores[i] = INVALID_ADJ
                } else if (entry.pendingTopTime != -1L) {
                    states[i] = ActivityManager.PROCESS_STATE_TOP
                    if (scores != null) scores[i] = -1
                    if (entry.pendingTopTime > newestTime) {
                        newestIndex = i
                        newestTime = entry.pendingTopTime
                    }
                } else {
                    states[i] = entry.state
                    if (scores != null) scores[i] = oomScore(pids[i])
                }
            }
            if (newestIndex != -1 && scores != null) scores[newestIndex] = -2
        }
    }

    companion object {
        private const val INVALID_ADJ = -10000

        private fun linuxOomScore(pid: Int): Int = try {
            File("/proc/$pid/oom_score_adj").readText().trim().toIntOrNull()
                ?.takeIf { it in -1000..1000 } ?: INVALID_ADJ
        } catch (_: IOException) {
            // A process can exit between the registry snapshot and the proc read.
            INVALID_ADJ
        }
    }
}
