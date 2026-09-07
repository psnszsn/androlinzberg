package dev.artzig.testing

import org.json.JSONArray
import org.json.JSONObject

object SessionJournalSmoke {
    fun run() {
        val journal = SessionJournal(logLimit = 2, issueLimit = 2)
        val first = SessionJournal.Source(10, 1, 10001, "first")
        val second = SessionJournal.Source(20, 2, 10002, "second")
        fun page(logs: Long, issues: Long = 0, dropped: Long = 0,
            logRecords: List<Long> = listOf(logs), issueRecords: List<Long> = emptyList()): JSONObject =
            JSONObject().put("log_cursor", logs).put("issue_cursor", issues)
                .put("logs_dropped_through", dropped).put("issues_dropped_through", 0)
                .put("logs", JSONArray().apply {
                    logRecords.forEach { put(JSONObject().put("seq", it).put("message", "log $it")) }
                }).put("issues", JSONArray().apply {
                    issueRecords.forEach { put(JSONObject().put("seq", it).put("detail", "issue $it")) }
                })
        journal.ingest(first, page(1))
        journal.ingest(second, page(1))
        journal.ingest(first, page(1)) // A repeated page cannot duplicate session records.
        check(journal.logSequence == 2L && journal.issueSequence == 0L)
        val initial = journal.snapshot(0, 0).getJSONArray("logs")
        check(initial.getJSONObject(0).getLong("source_seq") == 1L &&
            initial.getJSONObject(1).getLong("source_seq") == 1L)
        check(initial.getJSONObject(0).getJSONObject("process").getInt("pid") == first.pid)
        check(initial.getJSONObject(1).getJSONObject("process").getInt("pid") == second.pid)
        journal.ingest(first, page(2))
        check(journal.snapshot(0, 0).getLong("logs_dropped_through") == 1L)
        initial.getJSONObject(1).put("message", "mutated output")
        check(journal.snapshot(0, 0).getJSONArray("logs").getJSONObject(0).getString("message") == "log 1")

        journal.ingest(first, page(4, dropped = 3))
        val lost = journal.snapshot(0, 0).getJSONArray("issues").getJSONObject(0)
        check(lost.getString("kind") == "diagnostics_lost" && lost.getString("member") == "logs")
        check(journal.cursor(first).logs == 4L)
        journal.forget(first)
        val restarted = first.copy(generation = 3)
        journal.ingest(restarted, page(1))
        check(journal.logSequence == 5L && journal.cursor(restarted).logs == 1L)
        val retained = journal.snapshot(0, 0).getJSONArray("logs")
        check(retained.getJSONObject(0).getJSONObject("process").getLong("generation") == 1L)
        check(retained.getJSONObject(1).getJSONObject("process").getLong("generation") == 3L)

        // Validate all source records before committing either stream.
        check(runCatching { journal.ingest(second, page(2, issues = 2, issueRecords = listOf(2))) }
            .exceptionOrNull() is IllegalArgumentException)
        check(journal.logSequence == 5L && journal.issueSequence == 1L && journal.cursor(second).logs == 1L)
        check(runCatching { journal.ingest(second, page(0, logRecords = emptyList())) }
            .exceptionOrNull() is IllegalArgumentException)
        journal.failure(second, "observe", "main thread timed out")
        journal.failure(restarted, "observe", "process disconnected")
        check(journal.snapshot(0, 0).getLong("issues_dropped_through") == 1L)
        check(journal.snapshot(4, 2).getJSONArray("logs").length() == 1)
        check(journal.snapshot(4, 2).getJSONArray("issues").length() == 1)
    }
}
