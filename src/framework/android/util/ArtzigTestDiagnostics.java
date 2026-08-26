/* artzig: bounded structured diagnostics for the private headless test seam. */
package android.util;

import java.util.ArrayDeque;

public final class ArtzigTestDiagnostics {
    private static final int MAX_LOGS = 512;
    private static final int MAX_ISSUES = 256;
    private static final int MAX_TEXT = 64 * 1024;

    private static long sLogSequence;
    private static long sIssueSequence;
    private static long sLogsDroppedThrough;
    private static long sIssuesDroppedThrough;
    private static final ArrayDeque<LogRecord> sLogs = new ArrayDeque<>();
    private static final ArrayDeque<IssueRecord> sIssues = new ArrayDeque<>();

    private static final class LogRecord {
        long sequence;
        long monotonicMs;
        int priority;
        String tag;
        String message;
        String thread;
    }

    private static final class IssueRecord {
        long sequence;
        long monotonicMs;
        String kind;
        String owner;
        String member;
        String detail;
        String thread;
    }

    private ArtzigTestDiagnostics() {}

    public static synchronized long logSequence() { return sLogSequence; }
    public static synchronized long issueSequence() { return sIssueSequence; }

    static synchronized void recordLog(int priority, String tag, String message) {
        LogRecord record = new LogRecord();
        record.sequence = ++sLogSequence;
        record.monotonicMs = monotonicMs();
        record.priority = priority;
        record.tag = safe(tag);
        record.message = safe(message);
        record.thread = Thread.currentThread().getName();
        if (sLogs.size() == MAX_LOGS) {
            sLogsDroppedThrough = sLogs.removeFirst().sequence;
        }
        sLogs.addLast(record);

        String signature = missingSignature(record.message);
        if (signature != null) {
            reportIssueLocked("runtime_exception", record.tag, signature,
                    firstLine(record.message));
        }
    }

    public static synchronized void reportMissing(String owner, String member,
            String detail) {
        reportIssueLocked("unimplemented", safe(owner), safe(member), safe(detail));
    }

    public static UnsupportedOperationException unsupported(String owner, String member,
            String detail) {
        String message = detail == null || detail.isEmpty()
                ? owner + "." + member + " is not implemented"
                : detail;
        reportMissing(owner, member, message);
        return new UnsupportedOperationException(message);
    }

    private static void reportIssueLocked(String kind, String owner, String member,
            String detail) {
        IssueRecord record = new IssueRecord();
        record.sequence = ++sIssueSequence;
        record.monotonicMs = monotonicMs();
        record.kind = safe(kind);
        record.owner = safe(owner);
        record.member = safe(member);
        record.detail = safe(detail);
        record.thread = Thread.currentThread().getName();
        if (sIssues.size() == MAX_ISSUES) {
            sIssuesDroppedThrough = sIssues.removeFirst().sequence;
        }
        sIssues.addLast(record);
    }

    public static synchronized String snapshot(long afterLogSequence,
            long afterIssueSequence) {
        StringBuilder out = new StringBuilder(4096);
        out.append('{');
        field(out, "log_cursor", sLogSequence).append(',');
        field(out, "issue_cursor", sIssueSequence).append(',');
        field(out, "logs_dropped_through", sLogsDroppedThrough).append(',');
        field(out, "issues_dropped_through", sIssuesDroppedThrough).append(',');
        out.append("\"logs\":[");
        boolean first = true;
        for (LogRecord record : sLogs) {
            if (record.sequence <= afterLogSequence) continue;
            if (!first) out.append(',');
            first = false;
            out.append('{');
            field(out, "seq", record.sequence).append(',');
            field(out, "monotonic_ms", record.monotonicMs).append(',');
            field(out, "priority", record.priority).append(',');
            field(out, "level", levelName(record.priority)).append(',');
            field(out, "tag", record.tag).append(',');
            field(out, "message", record.message).append(',');
            field(out, "thread", record.thread);
            out.append('}');
        }
        out.append("],\"issues\":[");
        first = true;
        for (IssueRecord record : sIssues) {
            if (record.sequence <= afterIssueSequence) continue;
            if (!first) out.append(',');
            first = false;
            out.append('{');
            field(out, "seq", record.sequence).append(',');
            field(out, "monotonic_ms", record.monotonicMs).append(',');
            field(out, "kind", record.kind).append(',');
            field(out, "owner", record.owner).append(',');
            field(out, "member", record.member).append(',');
            field(out, "detail", record.detail).append(',');
            field(out, "thread", record.thread);
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private static String missingSignature(String message) {
        if (message.contains("AbstractMethodError")) return "AbstractMethodError";
        if (message.contains("UnsatisfiedLinkError")) return "UnsatisfiedLinkError";
        if (message.contains("ClassNotFoundException")) return "ClassNotFoundException";
        if (message.contains("NoClassDefFoundError")) return "NoClassDefFoundError";
        if (message.contains("VerifyError")) return "VerifyError";
        if (message.contains("RuntimeException: Stub!")) return "Stub";
        // UnsupportedOperationException is recorded at the framework stub
        // itself, avoiding duplicates when a caller catches and logs it.
        // NoSuchMethodError is deliberately excluded: the launcher probes
        // optional host methods with GetStaticMethodID and clears failures.
        return null;
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }

    private static long monotonicMs() { return System.nanoTime() / 1_000_000L; }

    private static String levelName(int priority) {
        switch (priority) {
            case Log.VERBOSE: return "verbose";
            case Log.DEBUG: return "debug";
            case Log.INFO: return "info";
            case Log.WARN: return "warn";
            case Log.ERROR: return "error";
            case Log.ASSERT: return "assert";
            default: return "unknown";
        }
    }

    private static StringBuilder field(StringBuilder out, String name, long value) {
        quote(out, name).append(':').append(value);
        return out;
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        quote(out, name).append(':');
        quote(out, value);
        return out;
    }

    private static StringBuilder quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; ++pad) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"');
    }
}
