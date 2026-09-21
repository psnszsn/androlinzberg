package dev.artzig.system

import android.app.IApplicationThread
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import dev.artzig.testing.IInspectionRegistry
import dev.artzig.testing.IProcessInspection
import dev.artzig.testing.ISessionDiagnostics
import dev.artzig.testing.RuntimeIssue
import dev.artzig.testing.RuntimeJournal
import dev.artzig.testing.RuntimeLog
import dev.artzig.testing.SessionJournal
import org.json.JSONObject

/** Bounded, generation-checked registry of real AMS process observers. Never
 * holds its registry lock or WMS lock while calling into an application. */
class HostedInspectionRegistry(
    private val activityManager: HostedActivityManagerService,
    private val inputSettled: (Long) -> Boolean = { false },
    private val windowTask: (IBinder, Int, Int) -> Int?,
) : IInspectionRegistry.Stub() {
    private class Entry(val process: HostedActivityManagerService.AttachedProcess,
        val endpoint: IProcessInspection, val generation: Long, val death: IBinder.DeathRecipient)
    private val lock = Any()
    private val processes = LinkedHashMap<Int, Entry>()
    private var nextGeneration = 1L
    private val journal = SessionJournal()
    private val systemSource = SessionJournal.Source(Process.myPid(), 0, Process.SYSTEM_UID, "system_server")

    override fun inputIdle(sequence: Long): Boolean {
        enforceSystem()
        require(sequence > 0)
        // Binder worker waits outside registry/WM locks; the serial shell and
        // app loopers must remain free to finish the preceding input callbacks.
        return inputSettled(sequence)
    }

    private fun source(entry: Entry) = SessionJournal.Source(entry.process.pid, entry.generation,
        entry.process.uid, entry.process.processName)

    override fun registerProcess(application: IApplicationThread?, endpoint: IProcessInspection?): Long {
        val process = activityManager.attachedProcess(application)
            ?: throw SecurityException("Inspection requires an attached application thread")
        if (process.pid != Binder.getCallingPid() || process.uid != Binder.getCallingUid()) {
            throw SecurityException("Inspection process does not belong to the Binder caller")
        }
        val observer = requireNotNull(endpoint)
        synchronized(lock) {
            processes[process.pid]?.let {
                if (it.process.thread.asBinder() == application!!.asBinder() &&
                    it.endpoint.asBinder() == observer.asBinder()) return it.generation
                throw IllegalStateException("This process already registered an inspection endpoint")
            }
            check(processes.size < 64) { "Process inspection capacity exhausted" }
            val generation = nextGeneration++
            val death = IBinder.DeathRecipient { retire(process.pid, generation) }
            observer.asBinder().linkToDeath(death, 0)
            try { process.thread.asBinder().linkToDeath(death, 0) }
            catch (failure: Throwable) {
                observer.asBinder().unlinkToDeath(death, 0)
                throw failure
            }
            // linkToDeath rejects an already-dead endpoint. A subsequent death
            // waits for this lock and removes the published generation, avoiding
            // a second liveness check that could leak the application death link.
            processes[process.pid] = Entry(process, observer, generation, death)
            return generation
        }
    }

    override fun listProcesses(): Bundle {
        enforceSystem()
        val entries = synchronized(lock) { processes.values.toList() }
        return Bundle().apply {
            putParcelableArrayList("processes", ArrayList(entries.map(::metadata)))
        }
    }

    override fun observe(pid: Int, generation: Long, afterRevision: Long, afterLog: Long, afterIssue: Long): Bundle {
        enforceSystem()
        // listProcesses and observe are separate transactions. Normal service
        // retirement can invalidate a listed generation before or during this
        // read; report its lifecycle state instead of inventing an app exception.
        val entry = synchronized(lock) { processes[pid]?.takeIf { it.generation == generation } }
            ?: return gone()
        try {
            // Pinned ActivityThread.handleBindApplication calls AMS.finishAttachApplication
            // after Application.onCreate. Before that acknowledgement, collect the
            // thread-safe journal without queueing a UI request behind application startup.
            val ready = checkCurrent(entry).ready
            val observation = if (ready) entry.endpoint.observe(afterRevision, afterLog, afterIssue)
                else Bundle().apply {
                    putString("runtime", entry.endpoint.runtimeDiagnostics(afterLog, afterIssue))
                }
            observation.putBoolean("starting", !ready)
            // WMS owns task attribution, including embedded input targets.
            val tokens = observation.getBundle("window_tokens") ?: Bundle()
            val tasks = Bundle()
            for (window in tokens.keySet()) {
                tokens.getBinder(window)?.let { token ->
                    windowTask(token, entry.process.uid, entry.process.pid)?.let { tasks.putInt(window, it) }
                }
            }
            observation.remove("window_tokens")
            observation.putBundle("window_tasks", tasks)
            observation.putBundle("process", metadata(entry))
            synchronized(journal) {
                checkCurrent(entry)
                journal.ingest(source(entry), JSONObject(checkNotNull(observation.getString("runtime"))))
            }
            return observation
        } catch (failure: DeadObjectException) {
            retire(pid, generation)
            return gone()
        } catch (failure: Exception) {
            // Death callbacks and AMS detach can win the race with validation
            // after the endpoint has replied. Live endpoint failures still enter
            // the journal, and already-ingested runtime issues survive retirement.
            if (!isCurrent(entry)) {
                retire(pid, generation)
                return gone()
            }
            synchronized(journal) { journal.failure(source(entry), "observe", failure.toString()) }
            throw failure
        }
    }

    override fun perform(pid: Int, generation: Long, revision: Long, windowId: Long,
        viewId: Int, virtualId: Int, action: Int, text: String?): Boolean {
        val entry = entry(pid, generation)
        try {
            if (!checkCurrent(entry).ready) return false
            return entry.endpoint.perform(revision, windowId, viewId, virtualId, action, text)
                .also { checkCurrent(entry) }
        } catch (failure: Exception) {
            synchronized(journal) { journal.failure(source(entry), "perform", failure.toString()) }
            throw failure
        }
    }

    override fun validateInputTarget(pid: Int, generation: Long, revision: Long, windowId: Long, expectedTarget: String?): Int {
        val entry = entry(pid, generation)
        if (!checkCurrent(entry).ready) return -1
        try {
            val token = entry.endpoint.inputWindow(revision, windowId, expectedTarget) ?: return -1
            val task = windowTask(token, entry.process.uid, entry.process.pid) ?: return -1
            checkCurrent(entry)
            return task
        } catch (failure: Exception) {
            synchronized(journal) { journal.failure(source(entry), "validateInputTarget", failure.toString()) }
            throw failure
        }
    }

    override fun diagnostics(afterLog: Long, afterIssue: Long): String {
        enforceSystem()
        return synchronized(journal) {
            val cursor = journal.cursor(systemSource)
            journal.ingest(systemSource, JSONObject(dev.artzig.ArtzigTestDiagnostics.snapshot(cursor.logs, cursor.issues)))
            journal.snapshot(afterLog, afterIssue).toString()
        }
    }

    /**
     * The typed journal for host test tools. Unlike [diagnostics], it gathers the
     * attached processes' records itself, through their thread-safe journal
     * endpoint, so a reader needs no per-process observation loop.
     */
    val sessionDiagnostics: ISessionDiagnostics.Stub = object : ISessionDiagnostics.Stub() {
        override fun read(afterLog: Long, afterIssue: Long): RuntimeJournal {
            val caller = Binder.getCallingUid()
            if (caller != Process.SYSTEM_UID && caller != Process.SHELL_UID)
                throw SecurityException("Session diagnostics require the system or shell identity")
            val entries = synchronized(lock) { processes.values.toList() }
            val snapshot = synchronized(journal) {
                val system = journal.cursor(systemSource)
                journal.ingest(systemSource, JSONObject(dev.artzig.ArtzigTestDiagnostics.snapshot(system.logs, system.issues)))
                for (entry in entries) {
                    val source = source(entry)
                    val cursor = journal.cursor(source)
                    try {
                        journal.ingest(source, JSONObject(entry.endpoint.runtimeDiagnostics(cursor.logs, cursor.issues)))
                    } catch (_: android.os.RemoteException) {
                        // The process died; its death recipient retires the entry.
                    }
                }
                journal.snapshot(afterLog, afterIssue)
            }
            return RuntimeJournal().apply {
                logCursor = snapshot.getLong("log_cursor")
                issueCursor = snapshot.getLong("issue_cursor")
                logsDroppedThrough = snapshot.getLong("logs_dropped_through")
                issuesDroppedThrough = snapshot.getLong("issues_dropped_through")
                logs = records(snapshot.getJSONArray("logs")) { record, process ->
                    RuntimeLog().apply {
                        sequence = record.getLong("seq"); monotonicMs = record.optLong("monotonic_ms")
                        priority = record.optInt("priority"); tag = record.optString("tag")
                        message = record.optString("message"); thread = record.optString("thread")
                        pid = process?.optInt("pid") ?: 0; this.process = process?.optString("name") ?: ""
                    }
                }.toTypedArray()
                issues = records(snapshot.getJSONArray("issues")) { record, process ->
                    RuntimeIssue().apply {
                        sequence = record.getLong("seq"); monotonicMs = record.optLong("monotonic_ms")
                        kind = record.optString("kind"); owner = record.optString("owner")
                        member = record.optString("member"); detail = record.optString("detail")
                        thread = record.optString("thread")
                        pid = process?.optInt("pid") ?: 0; this.process = process?.optString("name") ?: ""
                    }
                }.toTypedArray()
            }
        }
    }

    private fun <T> records(array: org.json.JSONArray, convert: (JSONObject, JSONObject?) -> T): List<T> =
        (0 until array.length()).map { index -> array.getJSONObject(index).let { convert(it, it.optJSONObject("process")) } }

    private fun gone() = Bundle().apply { putBoolean("gone", true) }

    private fun isCurrent(entry: Entry): Boolean {
        if (!synchronized(lock) { processes[entry.process.pid] === entry }) return false
        val current = activityManager.attachedProcess(entry.process.thread)
        return current != null && current.pid == entry.process.pid && current.uid == entry.process.uid
    }

    private fun retire(pid: Int, generation: Long) {
        val removed = synchronized(lock) {
            processes[pid]?.takeIf { it.generation == generation }?.also { processes.remove(pid) }
        } ?: return
        removed.endpoint.asBinder().unlinkToDeath(removed.death, 0)
        removed.process.thread.asBinder().unlinkToDeath(removed.death, 0)
        synchronized(journal) { journal.forget(source(removed)) }
    }

    private fun metadata(entry: Entry) = Bundle().apply {
        putInt("pid", entry.process.pid); putInt("uid", entry.process.uid)
        putLong("generation", entry.generation)
        putString("package", entry.process.packageName); putString("name", entry.process.processName)
        putBoolean("ready", activityManager.attachedProcess(entry.process.thread)?.ready == true)
    }

    private fun entry(pid: Int, generation: Long): Entry {
        enforceSystem()
        val entry = synchronized(lock) { processes[pid]?.takeIf { it.generation == generation } }
            ?: throw IllegalStateException("Inspection target is gone or belongs to another generation")
        checkCurrent(entry)
        return entry
    }

    private fun checkCurrent(entry: Entry): HostedActivityManagerService.AttachedProcess {
        check(synchronized(lock) { processes[entry.process.pid] === entry }) {
            "Inspection process died during the request"
        }
        val current = activityManager.attachedProcess(entry.process.thread)
        check(current != null && current.pid == entry.process.pid && current.uid == entry.process.uid) {
            "Inspection application is no longer attached"
        }
        return current
    }

    private fun enforceSystem() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("Inspection requires the system controller")
    }
}
