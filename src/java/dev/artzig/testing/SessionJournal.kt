package dev.artzig.testing

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Merges independent process cursors into the existing test protocol's single
 * session cursor. Lost source history remains an explicit diagnostic. */
class SessionJournal(private val logLimit: Int = 512, private val issueLimit: Int = 256) {
    data class Source(val pid: Int, val generation: Long, val uid: Int, val name: String) {
        fun json() = JSONObject().put("pid", pid).put("generation", generation).put("uid", uid).put("name", name)
    }
    data class Cursor(val logs: Long = 0, val issues: Long = 0)
    private val cursors = LinkedHashMap<Source, Cursor>()
    private val logs = ArrayDeque<JSONObject>()
    private val issues = ArrayDeque<JSONObject>()
    var logSequence = 0L
        private set
    var issueSequence = 0L
        private set
    private var logsDropped = 0L
    private var issuesDropped = 0L

    init { require(logLimit > 0 && issueLimit > 0) }

    fun cursor(source: Source) = cursors[source] ?: Cursor()
    fun forget(source: Source) { cursors.remove(source) }

    fun ingest(source: Source, observation: JSONObject) {
        val previous = cursor(source)
        val next = Cursor(observation.getLong("log_cursor"), observation.getLong("issue_cursor"))
        require(next.logs >= previous.logs && next.issues >= previous.issues) { "Process diagnostic cursor rewound" }
        check(cursors.containsKey(source) || cursors.size < 65) { "Session diagnostic source capacity exhausted" }
        fun read(name: String, after: Long, through: Long, dropped: Long): List<JSONObject> {
            require(dropped in 0..through) { "Invalid process history boundary" }
            val records = observation.getJSONArray(name)
            val result = ArrayList<JSONObject>()
            var last = maxOf(after, dropped)
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val sequence = record.getLong("seq")
                if (sequence <= after) continue
                require(sequence == last + 1 && sequence <= through) { "Process diagnostic records contain an unexplained gap" }
                last = sequence
                result += JSONObject(record.toString()).put("source_seq", sequence).put("process", source.json())
            }
            require(last == through) { "Process diagnostic page did not reach its reported cursor" }
            return result
        }
        val droppedLogs = observation.getLong("logs_dropped_through")
        val droppedIssues = observation.getLong("issues_dropped_through")
        // Validate the entire page before publishing any part of it: a malformed
        // issue batch must not duplicate its log batch on the next refresh.
        val newLogs = read("logs", previous.logs, next.logs, droppedLogs)
        val newIssues = read("issues", previous.issues, next.issues, droppedIssues)
        if (droppedLogs > previous.logs) failure(source, "logs",
            "Process history was lost after ${previous.logs} through $droppedLogs", "diagnostics_lost")
        if (droppedIssues > previous.issues) failure(source, "issues",
            "Process history was lost after ${previous.issues} through $droppedIssues", "diagnostics_lost")
        newLogs.forEach(::appendLog)
        newIssues.forEach(::appendIssue)
        cursors[source] = next
    }

    fun failure(source: Source, member: String, detail: String, kind: String = "runtime_exception") {
        appendIssue(JSONObject().put("kind", kind).put("owner", "SessionInspection").put("member", member)
            .put("detail", detail.take(64 * 1024)).put("process", source.json())
            .put("thread", Thread.currentThread().name).put("monotonic_ms", System.nanoTime() / 1_000_000))
    }

    fun snapshot(afterLog: Long, afterIssue: Long): JSONObject = JSONObject()
        .put("log_cursor", logSequence).put("issue_cursor", issueSequence)
        .put("logs_dropped_through", logsDropped).put("issues_dropped_through", issuesDropped)
        .put("logs", recordsAfter(logs, afterLog)).put("issues", recordsAfter(issues, afterIssue))

    private fun recordsAfter(records: ArrayDeque<JSONObject>, after: Long): JSONArray = JSONArray().apply {
        for (record in records) if (record.getLong("seq") > after) put(JSONObject(record.toString()))
    }

    private fun appendLog(record: JSONObject) {
        record.put("seq", ++logSequence)
        if (logs.size == logLimit) logsDropped = logs.removeFirst().getLong("seq")
        logs.addLast(record)
    }

    private fun appendIssue(record: JSONObject) {
        record.put("seq", ++issueSequence)
        if (issues.size == issueLimit) issuesDropped = issues.removeFirst().getLong("seq")
        issues.addLast(record)
    }
}
