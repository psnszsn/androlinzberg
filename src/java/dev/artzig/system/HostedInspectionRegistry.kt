package dev.artzig.system

import android.app.IApplicationThread
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.artzig.testing.IInspectionRegistry
import dev.artzig.testing.IProcessInspection
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
            val death = IBinder.DeathRecipient {
                val removed = synchronized(lock) {
                    processes[process.pid]?.takeIf { it.generation == generation }
                        ?.also { processes.remove(process.pid) }
                }
                removed?.let {
                    it.endpoint.asBinder().unlinkToDeath(it.death, 0)
                    it.process.thread.asBinder().unlinkToDeath(it.death, 0)
                    synchronized(journal) { journal.forget(source(it)) }
                }
            }
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
        val entry = entry(pid, generation)
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
        } catch (failure: Exception) {
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
