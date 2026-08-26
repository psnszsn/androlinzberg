/* artzig shim: android.os.SharedMemory — unsupported (used only by Typeface IPC serialization). */
package android.os;
import java.io.Closeable;
import java.nio.ByteBuffer;
public final class SharedMemory implements Parcelable, Closeable {
    private SharedMemory() {}
    public static SharedMemory create(String name, int size) {
        throw android.util.ArtzigTestDiagnostics.unsupported(
                "android.os.SharedMemory", "create", "SharedMemory not supported");
    }
    public ByteBuffer mapReadOnly() { throw android.util.ArtzigTestDiagnostics.unsupported(
            "android.os.SharedMemory", "mapReadOnly", null); }
    public ByteBuffer mapReadWrite() { throw android.util.ArtzigTestDiagnostics.unsupported(
            "android.os.SharedMemory", "mapReadWrite", null); }
    public static void unmap(ByteBuffer buffer) {}
    public int getSize() { return 0; }
    public boolean setProtect(int prot) { return true; }
    public static SharedMemory fromFileDescriptor(ParcelFileDescriptor fd) {
        throw android.util.ArtzigTestDiagnostics.unsupported(
                "android.os.SharedMemory", "fromFileDescriptor", "SharedMemory not supported");
    }
    @Override public void close() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {}
    public static final Creator<SharedMemory> CREATOR = new Creator<SharedMemory>() {
        public SharedMemory createFromParcel(Parcel in) { return null; }
        public SharedMemory[] newArray(int size) { return new SharedMemory[size]; }
    };
}
