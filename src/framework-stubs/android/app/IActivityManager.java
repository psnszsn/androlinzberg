/* artzig stub. */
package android.app;
public interface IActivityManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default void setRenderThread(int tid) throws android.os.RemoteException {}
    default void closeSystemDialogs(String reason) throws android.os.RemoteException {}
}
