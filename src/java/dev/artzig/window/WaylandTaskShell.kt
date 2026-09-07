package dev.artzig.window

/**
 * Session-owned task lifecycle. Every entry point and completion runs on the
 * same serial executor. Android owns task removal and configuration; desktop
 * placement never enters this module. StockTaskOrganizer supplies the pinned
 * TaskOrganizer/WCT boundary, while Presenter owns Wayland protocol objects.
 */
internal class WaylandTaskShell<Token : Any, Leash : Any>(
    private val android: Android<Token, Leash>,
    private val presenter: Presenter,
    private val geometry: TaskGeometry = TaskGeometry(TaskGeometry.DESKTOP_SIZE, TaskGeometry.DESKTOP_SIZE),
) : AutoCloseable {
    data class Info<Token>(val id: Int, val token: Token, val appId: String,
        val title: String, val width: Int, val height: Int,
        val resizable: Boolean = true, val application: Boolean = true,
        val left: Int = 0, val top: Int = 0)
    data class Appeared<Token, Leash>(val info: Info<Token>, val leash: Leash)
    data class Size(val width: Int, val height: Int) {
        init { require(width in 1..16384 && height in 1..16384) }
    }
    interface Events<Token, Leash> {
        fun appeared(task: Appeared<Token, Leash>)
        fun changed(info: Info<Token>)
        fun vanished(id: Int, token: Token)
    }
    interface Android<Token, Leash> {
        fun register(events: Events<Token, Leash>): List<Appeared<Token, Leash>>
        fun unregister()
        fun release(leash: Leash)
        /** Completion means the coordinated task/output transaction committed. */
        fun configure(token: Token, leash: Leash, window: Window,
            change: TaskGeometry.Proposal.Change, size: Size, committed: (Boolean) -> Unit)
        /** Request Android lifecycle teardown; vanish remains authoritative. */
        fun remove(token: Token)
        /** Reject an unpresentable task through Android's removal lifecycle. */
        fun reject(token: Token, reason: TaskGeometry.Rejection)
    }
    interface Presenter {
        fun open(task: TaskGeometry.Task, appId: String, title: String, size: Size,
            events: WindowEvents): Window
    }
    interface WindowEvents {
        fun configure(size: Size)
        fun closeRequested()
    }
    interface Window : AutoCloseable {
        val size: Size
        fun metadata(appId: String, title: String)
        fun committed(bounds: TaskGeometry.Bounds, size: Size)
        fun rejected(reason: TaskGeometry.Rejection)
    }
    private inner class Entry(val key: TaskGeometry.Task, var info: Info<Token>, val leash: Leash) {
        var window: Window? = null
        var pending: TaskGeometry.Proposal.Change? = null
        var desired: Size? = null
        var presentationSize: Size? = null
        var closing = false
    }
    private val entries = linkedMapOf<Int, Entry>()
    private var generation = 0L
    private var registered = false
    private var closed = false
    private val events = object : Events<Token, Leash> {
        override fun appeared(task: Appeared<Token, Leash>) = onAppeared(task)
        override fun changed(info: Info<Token>) = onChanged(info)
        override fun vanished(id: Int, token: Token) {
            val entry = entries[id] ?: return
            if (entry.info.token == token) retire(entry)
        }
    }

    fun start() {
        check(!closed && !registered)
        registered = true
        try {
            // Callbacks can overlap the initial snapshot. Token equality owns
            // deduplication; every redundant leash still needs releasing.
            val initial = android.register(events)
            var delivered = 0
            try {
                for (task in initial) { delivered++; onAppeared(task) }
            } catch (failure: Throwable) {
                for (task in initial.drop(delivered)) {
                    try { android.release(task.leash) }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun onAppeared(task: Appeared<Token, Leash>) {
        val info = task.info
        if (closed || !info.application) { android.release(task.leash); return }
        val previous = entries[info.id]
        if (previous != null && previous.info.token == info.token) {
            if (previous.leash !== task.leash) android.release(task.leash)
            onChanged(info)
            return
        }
        if (previous != null) retire(previous)
        check(generation < Long.MAX_VALUE) { "Task generation exhausted" }
        val entry = Entry(TaskGeometry.Task(info.id, ++generation), info, task.leash)
        val rejected = geometry.reserve(entry.key)
        if (rejected != null) {
            try { android.reject(info.token, rejected) } finally { android.release(task.leash) }
            return
        }
        entries[info.id] = entry
        try {
            val initial = Size(info.width, info.height)
            entry.window = presenter.open(entry.key, info.appId, info.title, initial,
                object : WindowEvents {
                    override fun configure(size: Size) {
                        if (current(entry) && !entry.closing) resize(entry, size)
                    }
                    override fun closeRequested() {
                        if (!current(entry) || entry.closing) return
                        entry.closing = true
                        try { android.remove(entry.info.token) }
                        catch (failure: Throwable) { entry.closing = false; throw failure }
                    }
                })
            resize(entry, entry.desired ?: checkNotNull(entry.window).size)
        } catch (failure: Throwable) {
            try { retire(entry) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun onChanged(info: Info<Token>) {
        val entry = entries[info.id] ?: return
        if (entry.info.token != info.token) return
        if (!info.application) { retire(entry); return }
        entry.info = info
        entry.window?.metadata(info.appId, info.title)
        val bounds = geometry.bounds(entry.key)
        if (bounds != null && entry.pending == null && !entry.closing &&
            (bounds.left != info.left || bounds.top != info.top ||
                bounds.width != info.width || bounds.height != info.height)) {
            // Stock ActivityStarter/LaunchParams can change a reused task's
            // bounds when another activity starts inside it. Reconcile through
            // the same WCT path; the compositor still owns the output size.
            resize(entry, checkNotNull(entry.presentationSize))
        }
    }

    private fun current(entry: Entry): Boolean = !closed && entries[entry.key.id] === entry

    private fun resize(entry: Entry, size: Size) {
        entry.desired = size
        val window = entry.window ?: return
        if (entry.pending != null) return // Coalesce a configure storm behind one Android sync.
        val width = if (entry.info.resizable) size.width else entry.info.width
        val height = if (entry.info.resizable) size.height else entry.info.height
        val proposal = geometry.prepare(entry.key, width, height)
        if (proposal is TaskGeometry.Proposal.Rejected) {
            entry.desired = null
            window.rejected(proposal.reason)
            if (geometry.bounds(entry.key) == null) {
                entry.closing = true
                android.reject(entry.info.token, proposal.reason)
                // Android still owns destruction. Keep its output alive until
                // the matching vanish callback, as with a desktop close.
            }
            return
        }
        val change = proposal as TaskGeometry.Proposal.Change
        entry.pending = change
        entry.desired = null
        try {
            android.configure(entry.info.token, entry.leash, window, change, size) { success ->
                if (current(entry) && entry.pending === change) {
                    entry.pending = null
                    if (success) {
                        check(geometry.commit(change))
                        entry.presentationSize = size
                        window.committed(change.after, size)
                    } else {
                        check(geometry.cancel(change))
                        // Failed initial composition has no displayable allocation.
                        if (change.before == null) { retire(entry); return@configure }
                    }
                    val next = entry.desired
                    if (next != null && !entry.closing) resize(entry, next)
                }
            }
        } catch (failure: Throwable) {
            if (current(entry) && entry.pending === change) {
                entry.pending = null
                geometry.cancel(change)
                if (change.before == null) retire(entry)
            }
            throw failure
        }
    }

    private fun retire(entry: Entry) {
        if (entries[entry.key.id] !== entry) return
        entries.remove(entry.key.id)
        geometry.remove(entry.key)
        entry.pending = null
        // Detach SF composition before abandoning the presenter's producer queue.
        try { android.release(entry.leash) }
        finally { entry.window?.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try { if (registered) android.unregister() }
        catch (error: Throwable) { failure = error }
        registered = false
        for (entry in entries.values.toList()) {
            try { retire(entry) }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
