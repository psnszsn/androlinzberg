package dev.artzig.testing;
import android.os.Bundle;

/** Private, system-authorized test observations executed on an app's main thread. */
interface IProcessInspection {
    Bundle observe(long afterRevision, long afterLog, long afterIssue);
    boolean perform(long revision, long windowId, int viewId, int virtualId, int action, @nullable String text);
}
