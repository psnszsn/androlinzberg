/* artzig shim: android.os.Binder — transact calls onTransact directly (ATL pattern). */
package android.os;
import java.io.FileDescriptor;
public class Binder implements IBinder {
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
        return false;
    }
    @Override public void linkToDeath(DeathRecipient recipient, int flags) {}
    @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    public static int getCallingPid() { return Process.myPid(); }
    public static int getCallingUid() { return Process.myUid(); }
    public static UserHandle getCallingUserHandle() { return UserHandle.of(0); }
    public static long clearCallingIdentity() { return 0; }
    public static void restoreCallingIdentity(long token) {}
    public static void flushPendingCommands() {}
    public static IBinder allowBlocking(IBinder binder) { return binder; }
    public static boolean isProxy(IInterface iface) { return false; }
    public static IBinder defaultBlocking(IBinder binder) { return binder; }
    public static void withCleanCallingIdentity(com.android.internal.util.FunctionalUtils.ThrowingRunnable action) {
        action.run();
    }
    public static <T> T withCleanCallingIdentity(com.android.internal.util.FunctionalUtils.ThrowingSupplier<T> action) {
        return action.get();
    }
    public static void joinThreadPool() { for (;;) try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {} }
    public void dump(FileDescriptor fd, String[] args) {}
    protected void dump(FileDescriptor fd, java.io.PrintWriter fout, String[] args) {}
    public void dumpAsync(FileDescriptor fd, String[] args) {}
}
