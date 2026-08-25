/* artzig stub: perfetto unavailable on host. */
package com.android.internal.inputmethod;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;
import java.io.PrintWriter;
public class ImeTracingServerImpl extends ImeTracing {
    @Override public void addToBuffer(ProtoOutputStream proto, int source) {}
    @Override public void triggerClientDump(String where, InputMethodManager immInstance, byte[] icProto) {}
    @Override public void triggerServiceDump(String where, ServiceDumper dumper, byte[] icProto) {}
    @Override public void triggerManagerServiceDump(String where, ServiceDumper dumper) {}
    @Override public void startTrace(PrintWriter pw) {}
    @Override public void stopTrace(PrintWriter pw) {}
}
