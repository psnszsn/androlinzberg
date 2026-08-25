/* artzig shim: android.os.IBinder — in-process only (ATL pattern). */
package android.os;
import java.io.FileDescriptor;
public interface IBinder {
    int FIRST_CALL_TRANSACTION = 1, LAST_CALL_TRANSACTION = 0xffffff;
    int PING_TRANSACTION = ('_' << 24) | ('P' << 16) | ('N' << 8) | 'G';
    int DUMP_TRANSACTION = ('_' << 24) | ('D' << 16) | ('M' << 8) | 'P';
    int INTERFACE_TRANSACTION = ('_' << 24) | ('N' << 16) | ('T' << 8) | 'F';
    int FLAG_ONEWAY = 1;
    int MAX_IPC_SIZE = 64 * 1024;
    static int getSuggestedMaxIpcSizeBytes() { return MAX_IPC_SIZE; }
    String getInterfaceDescriptor() throws RemoteException;
    boolean pingBinder();
    boolean isBinderAlive();
    IInterface queryLocalInterface(String descriptor);
    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
    interface DeathRecipient { void binderDied(); }
    interface FrozenStateChangeCallback {
        int STATE_FROZEN = 0;
        int STATE_UNFROZEN = 1;
        void onFrozenStateChanged(IBinder who, int state);
    }
    default void addFrozenStateChangeCallback(java.util.concurrent.Executor executor,
            FrozenStateChangeCallback callback) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
    default boolean removeFrozenStateChangeCallback(FrozenStateChangeCallback callback) {
        return false;
    }
    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
    default void dump(FileDescriptor fd, String[] args) throws RemoteException {}
    default void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {}
}
