/* artzig stub. */
package android.app;
public interface IUriGrantsManager {
    default int checkGrantUriPermission_ignoreNonSystem(int callingUid, String targetPkg,
            android.net.Uri uri, int modeFlags, int userId) throws android.os.RemoteException { return -1; }
    final class Stub {
        public static IUriGrantsManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
