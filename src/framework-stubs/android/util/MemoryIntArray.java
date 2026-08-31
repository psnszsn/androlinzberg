/* artzig stub: android.util.MemoryIntArray — heap-backed.
 * Settings.NameValueCache generation tracking compiles against this; it stays
 * unreachable because the hosted SettingsProvider never returns
 * CALL_METHOD_TRACK_GENERATION_KEY (every read calls through, uncached). A
 * real memfd-backed array is the named follow-up for cached generations. */
package android.util;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.IOException;
public final class MemoryIntArray implements Parcelable, java.io.Closeable {
    private final int[] mValues;
    private boolean mClosed;
    public MemoryIntArray(int size) throws IOException { mValues = new int[size]; }
    public int get(int index) throws IOException { return mValues[index]; }
    public void set(int index, int value) throws IOException { mValues[index] = value; }
    public int size() throws IOException { return mValues.length; }
    public boolean isClosed() { return mClosed; }
    @Override public void close() throws IOException { mClosed = true; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException("MemoryIntArray cannot cross processes here");
    }
    public static final Creator<MemoryIntArray> CREATOR = new Creator<MemoryIntArray>() {
        @Override public MemoryIntArray createFromParcel(Parcel source) {
            throw new UnsupportedOperationException("MemoryIntArray cannot cross processes here");
        }
        @Override public MemoryIntArray[] newArray(int size) { return new MemoryIntArray[size]; }
    };
}
