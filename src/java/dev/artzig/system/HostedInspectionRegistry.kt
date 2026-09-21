package dev.artzig.system

import android.app.IApplicationThread
import android.os.Binder
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

/** Bounded, generation-checked registry of the journal endpoints of real AMS
 * processes. Never holds its registry lock while calling into an application. */
class HostedInspectionRegistry(
    private val activityManager: HostedActivityManagerService,
) : IInspectionRegistry.Stub() {
    private class Entry(val process: HostedActivityManagerService.AttachedProcess,
        val endpoint: IProcessInspection, val generation: Long, val death: IBinder.DeathRecipient)
    private val lock = Any()
    private val processes = LinkedHashMap<Int, Entry>()
    private var nextGeneration = 1L
    private val journal = SessionJournal()
    private val systemSource = SessionJournal.Source(Process.myPid(), 0, Process.SYSTEM_UID, "system_server")

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

    /**
     * The typed journal for host test tools. It gathers the attached processes'
     * records itself, through their thread-safe journal endpoint, so a reader
     * needs no per-process observation loop.
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

    private fun retire(pid: Int, generation: Long) {
        val removed = synchronized(lock) {
            processes[pid]?.takeIf { it.generation == generation }?.also { processes.remove(pid) }
        } ?: return
        removed.endpoint.asBinder().unlinkToDeath(removed.death, 0)
        removed.process.thread.asBinder().unlinkToDeath(removed.death, 0)
        synchronized(journal) { journal.forget(source(removed)) }
    }

}
