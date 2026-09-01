/* artzig stub: mount service interface — no vold/StorageManagerService on this
 * host. StrictMode.isCeStorageUnlocked looks it up; a null handle means AOSP's
 * "assume locked" default, so the single hosted user reports unlocked here. */
package android.os.storage;
public interface IStorageManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default boolean isCeStorageUnlocked(int userId) throws android.os.RemoteException { return true; }
    final class Stub {
        public static IStorageManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
