/* artzig stub. */
package android.hardware;
public final class SyncFence implements AutoCloseable, android.os.Parcelable {
    private SyncFence() {}
    public static SyncFence adopt(int fd) { return new SyncFence(); }
    public static SyncFence create(android.os.ParcelFileDescriptor pfd) { return new SyncFence(); }
    public boolean isValid() { return false; }
    public boolean await(java.time.Duration timeout) { return true; }
    public boolean awaitForever() { return true; }
    @Override public void close() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final Creator<SyncFence> CREATOR = new Creator<SyncFence>() {
        public SyncFence createFromParcel(android.os.Parcel in) { return null; }
        public SyncFence[] newArray(int size) { return new SyncFence[size]; }
    };
}
