package dev.artzig.testing;
import android.app.IApplicationThread;
import dev.artzig.testing.IProcessInspection;

/** Process identity comes from AMS and Binder, never from the journal's content. */
interface IInspectionRegistry {
    long registerProcess(IApplicationThread application, IProcessInspection endpoint);
}
