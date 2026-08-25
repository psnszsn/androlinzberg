/* artzig stub. */
package android.app;
public interface INotificationManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default boolean enqueueToast(String pkg, android.os.IBinder token, Object callback, int duration, boolean isUiContext, int displayId) throws android.os.RemoteException { return false; }
    default boolean enqueueTextToast(String pkg, android.os.IBinder token, CharSequence text, int duration, boolean isUiContext, int displayId) throws android.os.RemoteException { return false; }
    default void finishToken(String pkg, android.os.IBinder token) throws android.os.RemoteException {}
    default void cancelToast(String pkg, android.os.IBinder token) throws android.os.RemoteException {}
    default boolean enqueueTextToast(String pkg, android.os.IBinder token, CharSequence text, int duration, boolean isUiContext, int displayId, Object callback) throws android.os.RemoteException { return false; }
    final class Stub {
        public static INotificationManager asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
