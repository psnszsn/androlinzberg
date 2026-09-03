/* artzig stub: android.util.proto.ProtoOutputStream — no-op debug proto sink. */
package android.util.proto;
public final class ProtoOutputStream {
    public static final long FIELD_TYPE_INT32 = 5L << 32;
    public ProtoOutputStream() {}
    public ProtoOutputStream(int chunkSize) {}
    public ProtoOutputStream(java.io.FileDescriptor fd) {}
    public ProtoOutputStream(java.io.OutputStream stream) {}
    public long start(long fieldId) { return 0; }
    public void end(long token) {}
    public void write(long fieldId, int val) {}
    public void write(long fieldId, long val) {}
    public void write(long fieldId, float val) {}
    public void write(long fieldId, double val) {}
    public void write(long fieldId, boolean val) {}
    public void write(long fieldId, String val) {}
    public void write(long fieldId, byte[] val) {}
    public void flush() {}
    public byte[] getBytes() { return new byte[0]; }
}
