package dev.artzig.window

/**
 * Bounded synthetic display coordinates, owned by the task shell's serial executor.
 * Desktop positions never enter this allocator. Both sides of an unacknowledged
 * Android bounds transaction remain reserved until commit or cancellation.
 */
internal class TaskGeometry(
    val width: Int,
    val height: Int,
    private val capacity: Int = 64,
    private val gap: Int = 1,
) {
    data class Task(val id: Int, val generation: Long) {
        init { require(id >= 0 && generation > 0) }
    }

    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        init { require(left >= 0 && top >= 0 && width > 0 && height > 0) }
        val right: Long get() = left.toLong() + width
        val bottom: Long get() = top.toLong() + height
    }

    enum class Rejection { CAPACITY, TOO_LARGE, NO_SPACE, STALE_TASK, TRANSACTION_PENDING }

    sealed interface Proposal {
        class Change internal constructor(
            val task: Task,
            val before: Bounds?,
            val after: Bounds,
        ) : Proposal
        data class Rejected(val reason: Rejection) : Proposal
    }

    private class Entry(val task: Task, var bounds: Bounds?, var pending: Proposal.Change?)
    private val entries = linkedMapOf<Int, Entry>()

    init {
        // Matches the hosted composer/output dimension limit. All edge and
        // spacing calculations still widen before addition, including restores.
        require(width in 1..16384 && height in 1..16384)
        require(capacity in 1..256 && gap in 0..16384)
    }

    /**
     * Prefer a live task's origin. A restored task may request its saved bounds;
     * if occupied, deterministic first-fit places it without moving its peers.
     * Invalid/oversized dimensions cannot mutate the current allocation.
     */
    fun prepare(task: Task, taskWidth: Int, taskHeight: Int, restored: Bounds? = null): Proposal {
        require(taskWidth > 0 && taskHeight > 0)
        val entry = entries[task.id]
        if (entry != null && entry.task != task) return reject(Rejection.STALE_TASK)
        if (entry?.pending != null) return reject(Rejection.TRANSACTION_PENDING)
        if (entry == null && entries.size == capacity) return reject(Rejection.CAPACITY)
        if (taskWidth > width || taskHeight > height) return reject(Rejection.TOO_LARGE)
        val occupied = entries.values.filter { it !== entry }.flatMap {
            listOfNotNull(it.bounds, it.pending?.after)
        }
        val previous = entry?.bounds
        val preferred = previous ?: restored
        val inPlace = preferred?.let { Bounds(it.left, it.top, taskWidth, taskHeight) }
        val candidate = if (inPlace != null && fits(inPlace, occupied)) inPlace
            else findSpace(taskWidth, taskHeight, occupied)
        if (candidate == null) return reject(Rejection.NO_SPACE)
        val change = Proposal.Change(task, previous, candidate)
        if (entry == null) entries[task.id] = Entry(task, null, change)
        else entry.pending = change
        return change
    }

    /** Called only after the matching Android transaction has taken effect. */
    fun commit(change: Proposal.Change): Boolean {
        val entry = entries[change.task.id] ?: return false
        if (entry.task != change.task || entry.pending !== change) return false
        entry.bounds = change.after
        entry.pending = null
        return true
    }

    /** A failed transaction releases only its proposal, preserving live bounds. */
    fun cancel(change: Proposal.Change): Boolean {
        val entry = entries[change.task.id] ?: return false
        if (entry.task != change.task || entry.pending !== change) return false
        if (entry.bounds == null) entries.remove(change.task.id) else entry.pending = null
        return true
    }

    /** A late disappearance/ack for an earlier task generation cannot free its replacement. */
    fun remove(task: Task): Boolean {
        if (entries[task.id]?.task != task) return false
        entries.remove(task.id)
        return true
    }

    fun bounds(task: Task): Bounds? = entries[task.id]?.takeIf { it.task == task }?.bounds

    private fun reject(reason: Rejection) = Proposal.Rejected(reason)

    private fun fits(candidate: Bounds, occupied: List<Bounds>): Boolean =
        candidate.right <= width && candidate.bottom <= height && occupied.all {
            candidate.right + gap <= it.left || it.right + gap <= candidate.left ||
                candidate.bottom + gap <= it.top || it.bottom + gap <= candidate.top
        }

    private fun findSpace(taskWidth: Int, taskHeight: Int, occupied: List<Bounds>): Bounds? {
        // Any free rectangle can slide upward and leftward until its edges
        // meet the display or another reservation. Examine those bounded edge
        // coordinates in a stable top-to-bottom, left-to-right order.
        val xs = sortedSetOf(0L)
        val ys = sortedSetOf(0L)
        for (bounds in occupied) {
            if (bounds.right + gap + taskWidth <= width) xs.add(bounds.right + gap)
            if (bounds.bottom + gap + taskHeight <= height) ys.add(bounds.bottom + gap)
        }
        for (y in ys) for (x in xs) {
            val candidate = Bounds(x.toInt(), y.toInt(), taskWidth, taskHeight)
            if (fits(candidate, occupied)) return candidate
        }
        return null
    }
}
