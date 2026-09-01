/* artzig stub. */
package android.app;
public interface IActivityManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default void setRenderThread(int tid) throws android.os.RemoteException {}
    default void closeSystemDialogs(String reason) throws android.os.RemoteException {}
    /** AOSP IActivityManager.enableAppFreezer; no freezer on this host, always reports enabled state. */
    default boolean enableAppFreezer(boolean enable) throws android.os.RemoteException { return true; }
}
