/* artzig stub. URI grants are not enforced between hosted processes. */
package android.app;
public interface IUriGrantsManager {
    default int checkGrantUriPermission_ignoreNonSystem(int callingUid, String targetPkg,
            android.net.Uri uri, int modeFlags, int userId) throws android.os.RemoteException { return -1; }
    default void grantUriPermissionFromOwner(android.os.IBinder owner, int fromUid,
            String targetPkg, android.net.Uri uri, int mode, int sourceUserId, int targetUserId)
            throws android.os.RemoteException {}
    final class Stub {
        public static IUriGrantsManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
