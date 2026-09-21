package dev.artzig.testing;

/** A process's runtime journal, readable by the session's system process only. */
interface IProcessInspection {
    /** Thread-safe: available while Application.onCreate still owns main. */
    String runtimeDiagnostics(long afterLog, long afterIssue);
}
