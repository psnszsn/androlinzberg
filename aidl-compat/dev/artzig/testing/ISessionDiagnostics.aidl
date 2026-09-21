package dev.artzig.testing;

import dev.artzig.testing.RuntimeJournal;

/** Session-wide runtime journal for host test tools: the system server's own
 * records merged with those of every attached app process. Available to the
 * system and to the broker's inspector (shell) identity. */
interface ISessionDiagnostics {
    RuntimeJournal read(long afterLog, long afterIssue);
}
