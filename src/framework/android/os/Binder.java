/* Hosted adaptation of AOSP android.os.Binder.
 *
 * Local calls follow AOSP: transact() runs onTransact() on the caller's thread (there is no
 * kernel driver). Objects that cross processes are exported through HostedBinderRuntime, which
 * installs the broker-provided calling identity in the thread-local state read here. The
 * default onTransact() handles the same reserved codes as AOSP (PING, INTERFACE, DUMP). */
package android.os;
import java.io.FileDescriptor;
public class Binder implements IBinder {
    /** Calling identity for the current thread: null means "self". */
    private static final ThreadLocal<long[]> sCallingIdentity = new ThreadLocal<>();
    private static final long TOKEN_SELF = -1L;

    private String mDescriptor;
    private IInterface mOwner;
    public Binder() {}
    public Binder(String descriptor) { mDescriptor = descriptor; }
    public void attachInterface(IInterface owner, String descriptor) { mOwner = owner; mDescriptor = descriptor; }
    @Override public String getInterfaceDescriptor() { return mDescriptor; }
    @Override public boolean pingBinder() { return true; }
    @Override public boolean isBinderAlive() { return true; }
    @Override public IInterface queryLocalInterface(String descriptor) {
        return mDescriptor != null && mDescriptor.equals(descriptor) ? mOwner : null;
    }
    @Override public final boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        data.setDataPosition(0);
        boolean r = onTransact(code, data, reply, flags);
        if (reply != null) reply.setDataPosition(0);
        return r;
    }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(mDescriptor);
            return true;
        } else if (code == DUMP_TRANSACTION) {
            return true;
        } else if (code == PING_TRANSACTION) {
            return true;
        }
        return false;
    }
    @Override public void linkToDeath(DeathRecipient recipient, int flags) {}
    @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }

    // ---- calling identity (installed by HostedBinderRuntime around incoming transactions) ----
    static void setHostedCallingIdentity(int pid, int uid) {
        sCallingIdentity.set(new long[] { pid, uid });
    }
    static void clearHostedCallingIdentity() { sCallingIdentity.remove(); }
    public static int getCallingPid() {
        long[] id = sCallingIdentity.get();
        return id != null ? (int) id[0] : Process.myPid();
    }
    public static int getCallingUid() {
        long[] id = sCallingIdentity.get();
        return id != null ? (int) id[1] : Process.myUid();
    }
    /** No work-source propagation on this host; the calling uid stands in. */
    public static int getCallingWorkSourceUid() { return getCallingUid(); }
    public static int getCallingUidOrThrow() { return getCallingUid(); }
    public static UserHandle getCallingUserHandle() { return UserHandle.of(0); }
    /** Token: -1 when the identity was already "self", else pid<<32 | uid. */
    public static long clearCallingIdentity() {
        long[] id = sCallingIdentity.get();
        if (id == null) return TOKEN_SELF;
        sCallingIdentity.remove();
        return (id[0] << 32) | (id[1] & 0xffffffffL);
    }
    public static void restoreCallingIdentity(long token) {
        if (token == TOKEN_SELF) { sCallingIdentity.remove(); return; }
        sCallingIdentity.set(new long[] { token >>> 32, token & 0xffffffffL });
    }
    public static void flushPendingCommands() {}
    public static IBinder allowBlocking(IBinder binder) { return binder; }
    /** AOSP: an interface is a proxy when its binder is not the interface object itself. */
    public static boolean isProxy(IInterface iface) { return iface.asBinder() != iface; }
    public static IBinder defaultBlocking(IBinder binder) { return binder; }
    public static void withCleanCallingIdentity(com.android.internal.util.FunctionalUtils.ThrowingRunnable action) {
        long token = clearCallingIdentity();
        try { action.run(); } finally { restoreCallingIdentity(token); }
    }
    public static <T> T withCleanCallingIdentity(com.android.internal.util.FunctionalUtils.ThrowingSupplier<T> action) {
        long token = clearCallingIdentity();
        try { return action.get(); } finally { restoreCallingIdentity(token); }
    }
    public static void joinThreadPool() { for (;;) try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {} }
    public void dump(FileDescriptor fd, String[] args) {}
    protected void dump(FileDescriptor fd, java.io.PrintWriter fout, String[] args) {}
    public void dumpAsync(FileDescriptor fd, String[] args) {}
}
