package dev.artzig.testing;
import android.app.IApplicationThread;
import android.os.Bundle;
import dev.artzig.testing.IProcessInspection;

/** Process identity comes from AMS and Binder, never from observation JSON. */
interface IInspectionRegistry {
    long registerProcess(IApplicationThread application, IProcessInspection endpoint);
    Bundle listProcesses();
    Bundle observe(int pid, long generation, long afterRevision, long afterLog, long afterIssue);
    boolean perform(int pid, long generation, long revision, long windowId, int viewId, int virtualId, int action, @nullable String text);
    String diagnostics(long afterLog, long afterIssue);
    int validateInputTarget(int pid, long generation, long revision, long windowId);
}
