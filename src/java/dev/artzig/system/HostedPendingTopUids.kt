package dev.artzig.system

/** PendingStartActivityUids' independent lock and timestamp guard. Its concrete
 * AMS/OomAdjuster references cannot be reused with the hosted process registry.
 * No Android freezer or network-rule barrier is active in this session. */
internal class HostedPendingTopUids(private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private data class Pending(val pid: Int, val since: Long)
    private val pending = HashMap<Int, Pending>()

    @Synchronized fun add(uid: Int, pid: Int) {
        if (!pending.containsKey(uid)) pending[uid] = Pending(pid, now())
    }
    @Synchronized fun time(uid: Int, pid: Int): Long = pending[uid]?.takeIf { it.pid == pid }?.since ?: -1L
    @Synchronized fun contains(uid: Int): Boolean = pending.containsKey(uid)
    @Synchronized fun delete(uid: Int, updateStarted: Long) {
        if (pending[uid]?.let { updateStarted >= it.since } == true) pending.remove(uid)
    }
    @Synchronized fun died(uid: Int, pid: Int) {
        if (pending[uid]?.pid == pid) pending.remove(uid)
    }
}
