/* artzig stub: compile-time shim for AOSP classes that reference the real
 * IPackageManager (DisplayManager.createVirtualDisplay, a dead path here).
 * The hosted package manager is dev.artzig.system.IHostedPackageManager. */
package android.content.pm;
public interface IPackageManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default String[] getPackagesForUid(int uid) throws android.os.RemoteException { return new String[0]; }
    final class Stub {
        public static IPackageManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
