/* artzig stub. */
package android.util.proto;
import java.io.InputStream;
public final class ProtoInputStream {
    public static final int NO_MORE_FIELDS = -1;
    public ProtoInputStream(InputStream in) {}
    public ProtoInputStream(byte[] buffer) {}
    public int nextField() { return NO_MORE_FIELDS; }
    public int getFieldNumber() { return 0; }
    public long start(long fieldId) { return 0; }
    public void end(long token) {}
    public int readInt(long fieldId) { return 0; }
    public long readLong(long fieldId) { return 0; }
    public float readFloat(long fieldId) { return 0; }
    public double readDouble(long fieldId) { return 0; }
    public boolean readBoolean(long fieldId) { return false; }
    public String readString(long fieldId) { return null; }
    public byte[] readBytes(long fieldId) { return null; }
}
