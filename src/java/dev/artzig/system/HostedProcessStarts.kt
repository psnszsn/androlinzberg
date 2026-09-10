package dev.artzig.system

/** Host counterpart of ProcessList's pending-start sequences and AMS's attach
 * deadline. Process creation replaces the zygote; ActivityThread still reports
 * the sequence through attachApplication and finishAttachApplication. Calls and
 * scheduled callbacks belong to the activity manager's lock. */
internal class HostedProcessStarts<M>(
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val finished: (Request<M>, Failure?) -> Unit,
) {
    data class Key(val packageName: String, val processName: String,
        val userId: Int = 0, val isolated: Boolean = false)
    /** The owner supplies captured metadata; the supervisor never mutates it. */
    data class Request<M>(val key: Key, val apkPath: String, val sequence: Long,
        val metadata: M?)
    enum class Failure { SPAWN, EXIT_BEFORE_READY, ATTACH_TIMEOUT }

    data class Identity(val pid: Int, val uid: Int)
    interface Child { val identity: Identity; fun terminate() }
    fun interface Launcher<M> {
        /** Exit notification must be dispatched on the same owner as other calls. */
        fun launch(request: Request<M>, onExit: () -> Unit): Child?
    }

    private class Entry<M>(val request: Request<M>) {
        var child: Child? = null
        var deadline: AutoCloseable? = null
        var attached = false
        var ready = false
    }
    private val entries = linkedMapOf<Key, Entry<M>>()
    private var nextSequence = 1L

    fun start(key: Key, apkPath: String, launcher: Launcher<M>,
        metadata: M? = null): Boolean {
        if (entries.containsKey(key)) return true
        check(nextSequence > 0) { "Process start sequences exhausted" }
        val entry = Entry(Request(key, apkPath, nextSequence++, metadata))
        entries[key] = entry
        val child = try { launcher.launch(entry.request) { exited(entry) } }
        catch (failure: Exception) {
            remove(entry, Failure.SPAWN)
            throw failure
        }
        if (child == null) {
            remove(entry, Failure.SPAWN)
            return false
        }
        // A launcher may report an immediate exit before returning its handle.
        if (entries[key] !== entry) {
            child.terminate()
            return false
        }
        entry.child = child
        entry.deadline = schedule(10_000) {
            if (entries[key] === entry && !entry.ready) {
                try { remove(entry, Failure.ATTACH_TIMEOUT) }
                finally { child.terminate() }
            }
        }
        return true
    }

    fun requestFor(key: Key): Request<M>? = entries[key]?.request

    fun identity(key: Key): Identity? = entries[key]?.child?.identity

    fun request(sequence: Long, identity: Identity): Request<M>? = entries.values.firstOrNull {
        it.request.sequence == sequence && it.child?.identity == identity
    }?.request

    /** Sequence zero belongs to a launcher-started peer, outside this supervisor.
     * It cannot claim an in-flight supervised start for the same process. */
    fun attach(key: Key, sequence: Long, identity: Identity): Boolean {
        val entry = entries[key] ?: return sequence == 0L
        if (entry.request.sequence != sequence || entry.attached || entry.child?.identity != identity) return false
        entry.attached = true
        return true
    }

    fun ready(key: Key, sequence: Long) {
        val entry = entries[key] ?: return
        check(entry.request.sequence == sequence && entry.attached)
        entry.ready = true
        entry.deadline?.close()
        entry.deadline = null
    }

    /** A death notification must never cancel the replacement generation. */
    fun died(key: Key, sequence: Long) {
        val entry = entries[key]?.takeIf { it.request.sequence == sequence } ?: return
        try { remove(entry, if (entry.ready) null else Failure.EXIT_BEFORE_READY) }
        finally { entry.child?.terminate() }
    }

    private fun exited(entry: Entry<M>) {
        remove(entry, if (entry.ready) null else Failure.EXIT_BEFORE_READY)
    }

    private fun remove(entry: Entry<M>, failure: Failure?) {
        if (entries[entry.request.key] !== entry) return
        entries.remove(entry.request.key)
        entry.deadline?.close()
        finished(entry.request, failure)
    }
}
