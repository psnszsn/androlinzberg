/* artzig stub. */
package android.app;
public interface IActivityManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default void setRenderThread(int tid) throws android.os.RemoteException {}
    default void closeSystemDialogs(String reason) throws android.os.RemoteException {}
    /** BroadcastReceiver.PendingResult.sendFinished: the hosted dispatcher overrides it (ArtzigBroadcastManager.Args). */
    default void finishReceiver(android.os.IBinder who, int resultCode, String resultData,
            android.os.Bundle map, boolean abortBroadcast, int flags) throws android.os.RemoteException {}
    /** BroadcastReceiver.peekService: no service binder is peeked through this stub. */
    default android.os.IBinder peekService(android.content.Intent service, String resolvedType,
            String callingPackage) throws android.os.RemoteException { return null; }
    /** StrictMode registration/reporting: no AMS to record violations. */
    default void registerStrictModeCallback(IUnsafeIntentStrictModeCallback callback) throws android.os.RemoteException {}
    default void handleApplicationStrictModeViolation(android.os.IBinder app, int penaltyMask,
            android.os.StrictMode.ViolationInfo info) throws android.os.RemoteException {}
    /** AOSP IActivityManager.enableAppFreezer; no freezer on this host, always reports enabled state. */
    default boolean enableAppFreezer(boolean enable) throws android.os.RemoteException { return true; }
}
