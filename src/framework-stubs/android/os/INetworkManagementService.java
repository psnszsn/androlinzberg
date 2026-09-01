/* artzig stub: netd's management interface — no netd on this host. StrictMode's
 * cleartext-network policy push finds no service and logs, as AOSP does. */
package android.os;
public interface INetworkManagementService extends IInterface {
    @Override default IBinder asBinder() { return null; }
    default void setUidCleartextNetworkPolicy(int uid, int policy) throws RemoteException {}
    final class Stub {
        public static INetworkManagementService asInterface(IBinder b) { return null; }
        private Stub() {}
    }
}
