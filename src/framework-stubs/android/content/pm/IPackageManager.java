/* artzig stub. */
package android.content.pm;
public interface IPackageManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default String[] getPackagesForUid(int uid) throws android.os.RemoteException { return new String[0]; }
    final class Stub {
        public static IPackageManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
