package dev.artzig

import android.util.Log
import java.util.ArrayDeque

/** Bounded, cursor-based diagnostics for the private headless test interface. */
object ArtzigTestDiagnostics {
    private const val MAX_LOGS = 512
    private const val MAX_ISSUES = 256
    private const val MAX_TEXT = 64 * 1024

    private var logSequence = 0L
    private var issueSequence = 0L
    private var logsDroppedThrough = 0L
    private var issuesDroppedThrough = 0L
    private val logs = ArrayDeque<LogRecord>()
    private val issues = ArrayDeque<IssueRecord>()

    private class LogRecord(
        val sequence: Long,
        val monotonicMs: Long,
        val priority: Int,
        val tag: String,
        val message: String,
        val thread: String,
    )

    private class IssueRecord(
        val sequence: Long,
        val monotonicMs: Long,
        val kind: String,
        val owner: String,
        val member: String,
        val detail: String,
        val thread: String,
    )

    @JvmStatic @Synchronized fun logSequence(): Long = logSequence
    @JvmStatic @Synchronized fun issueSequence(): Long = issueSequence

    /** Called by the Zig liblog sink with the same JNI descriptor as the former Java sink. */
    @JvmStatic @Synchronized fun recordLog(priority: Int, tag: String?, message: String?) {
        val record = LogRecord(
            ++logSequence, monotonicMs(), priority, safe(tag), safe(message),
            Thread.currentThread().name,
        )
        if (logs.size == MAX_LOGS) logsDroppedThrough = logs.removeFirst().sequence
        logs.addLast(record)
        val signature = missingSignature(record.message)
        if (signature != null) {
            reportIssueLocked("runtime_exception", record.tag, signature,
                record.message.substringBefore('\n'))
        }
    }

    @JvmStatic @Synchronized fun reportMissing(owner: String?, member: String?, detail: String?) {
        reportIssueLocked("unimplemented", safe(owner), safe(member), safe(detail))
    }

    /** Host subsystems use this only for bounded, actionable asynchronous failures. */
    @JvmStatic @Synchronized fun reportIssue(
        kind: String?, owner: String?, member: String?, detail: String?,
    ) {
        reportIssueLocked(safe(kind), safe(owner), safe(member), safe(detail))
    }

    @JvmStatic fun unsupported(
        owner: String?, member: String?, detail: String?,
    ): UnsupportedOperationException {
        val message = if (detail.isNullOrEmpty()) "$owner.$member is not implemented" else detail
        reportMissing(owner, member, message)
        return UnsupportedOperationException(message)
    }

    private fun reportIssueLocked(kind: String, owner: String, member: String, detail: String) {
        val record = IssueRecord(
            ++issueSequence, monotonicMs(), safe(kind), safe(owner), safe(member), safe(detail),
            Thread.currentThread().name,
        )
        if (issues.size == MAX_ISSUES) issuesDroppedThrough = issues.removeFirst().sequence
        issues.addLast(record)
    }

    @JvmStatic @Synchronized fun snapshot(afterLogSequence: Long, afterIssueSequence: Long): String {
        val out = StringBuilder(4096)
        out.append('{')
        field(out, "log_cursor", logSequence).append(',')
        field(out, "issue_cursor", issueSequence).append(',')
        field(out, "logs_dropped_through", logsDroppedThrough).append(',')
        field(out, "issues_dropped_through", issuesDroppedThrough).append(',')
        out.append("\"logs\":[")
        var first = true
        for (record in logs) {
            if (record.sequence <= afterLogSequence) continue
            if (!first) out.append(',')
            first = false
            out.append('{')
            field(out, "seq", record.sequence).append(',')
            field(out, "monotonic_ms", record.monotonicMs).append(',')
            field(out, "priority", record.priority.toLong()).append(',')
            field(out, "level", levelName(record.priority)).append(',')
            field(out, "tag", record.tag).append(',')
            field(out, "message", record.message).append(',')
            field(out, "thread", record.thread)
            out.append('}')
        }
        out.append("],\"issues\":[")
        first = true
        for (record in issues) {
            if (record.sequence <= afterIssueSequence) continue
            if (!first) out.append(',')
            first = false
            out.append('{')
            field(out, "seq", record.sequence).append(',')
            field(out, "monotonic_ms", record.monotonicMs).append(',')
            field(out, "kind", record.kind).append(',')
            field(out, "owner", record.owner).append(',')
            field(out, "member", record.member).append(',')
            field(out, "detail", record.detail).append(',')
            field(out, "thread", record.thread)
            out.append('}')
        }
        return out.append("]}").toString()
    }

    private fun missingSignature(message: String): String? {
        for (signature in arrayOf("AbstractMethodError", "UnsatisfiedLinkError",
                "ClassNotFoundException", "NoClassDefFoundError", "VerifyError")) {
            if (message.contains(signature)) return signature
        }
        if (message.contains("RuntimeException: Stub!")) return "Stub"
        // Explicit unsupported calls record their own issues, avoiding duplicate log reports.
        // NoSuchMethodError is excluded because the launcher probes optional host methods.
        return null
    }

    private fun safe(value: String?): String = value?.take(MAX_TEXT) ?: ""
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    private fun levelName(priority: Int): String = when (priority) {
        Log.VERBOSE -> "verbose"
        Log.DEBUG -> "debug"
        Log.INFO -> "info"
        Log.WARN -> "warn"
        Log.ERROR -> "error"
        Log.ASSERT -> "assert"
        else -> "unknown"
    }

    private fun field(out: StringBuilder, name: String, value: Long): StringBuilder =
        quote(out, name).append(':').append(value)

    private fun field(out: StringBuilder, name: String, value: String): StringBuilder {
        quote(out, name).append(':')
        return quote(out, value)
    }

    private fun quote(out: StringBuilder, value: String): StringBuilder {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c < ' ') {
                        out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        return out.append('"')
    }
}
