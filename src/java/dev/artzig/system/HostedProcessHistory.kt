package dev.artzig.system

import android.app.ApplicationExitInfo
import android.os.IBinder

/**
 * Pinned AppExitInfoTracker's per-package bounded history, newest-first queries,
 * and state-summary ownership at the host process seam. Android's tracker needs
 * ProcessRecord, zygote wait statuses and tombstoned; Binder death alone supplies
 * an UNKNOWN reason. Session supervision can subsequently supply the exit status.
 * This object is confined to the activity manager's process lock.
 */
internal class HostedProcessHistory(private val capacity: Int) {
    private data class Key(val uid: Int, val packageName: String)
    private val exits = HashMap<Key, ArrayDeque<ApplicationExitInfo>>()
    private val summaries = HashMap<IBinder, ByteArray>()

    init { require(capacity > 0) }

    fun summary(owner: IBinder, state: ByteArray?) {
        require(state == null || state.size <= 128) { "Data size is too large" }
        if (state == null) summaries.remove(owner) else summaries[owner] = state.copyOf()
    }

    fun died(owner: IBinder, info: ApplicationExitInfo) {
        val copy = ApplicationExitInfo(info)
        copy.processStateSummary = summaries.remove(owner)
        val entries = exits.getOrPut(Key(copy.packageUid, copy.packageName)) { ArrayDeque() }
        // Stock containers index by pid; a later death replaces an old reused pid.
        entries.removeAll { it.pid == copy.pid }
        entries.addFirst(copy)
        while (entries.size > capacity) entries.removeLast()
    }

    fun query(packageName: String?, uid: Int, pid: Int, maxNum: Int): List<ApplicationExitInfo> {
        val matches = exits.entries.asSequence()
            .filter { it.key.uid == uid && (packageName.isNullOrEmpty() || it.key.packageName == packageName) }
            .flatMap { it.value.asSequence() }
            .filter { pid <= 0 || it.pid == pid }
            .sortedByDescending { it.timestamp }
        return (if (maxNum > 0) matches.take(maxNum) else matches).map { source ->
            ApplicationExitInfo(source).apply { processStateSummary = source.processStateSummary?.copyOf() }
        }.toList()
    }
}
