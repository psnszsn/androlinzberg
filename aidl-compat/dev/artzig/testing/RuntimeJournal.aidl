package dev.artzig.testing;

import dev.artzig.testing.RuntimeIssue;
import dev.artzig.testing.RuntimeLog;

/** Records after a reader's cursors, and the cursors to pass next time.
 * A nonzero droppedThrough means records up to that sequence were evicted. */
parcelable RuntimeJournal {
    long logCursor;
    long issueCursor;
    long logsDroppedThrough;
    long issuesDroppedThrough;
    RuntimeLog[] logs;
    RuntimeIssue[] issues;
}
