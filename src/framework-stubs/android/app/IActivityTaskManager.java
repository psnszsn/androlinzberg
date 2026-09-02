/* artzig stub: activity-task-manager service interface — no ATMS on this host.
 * Only the members reused framework code links against. */
package android.app;
public interface IActivityTaskManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default IActivityClientController getActivityClientController() throws android.os.RemoteException { return null; }
    default String getVoiceInteractorPackageName(android.os.IBinder callingVoiceInteractor) throws android.os.RemoteException { return null; }
    default void registerBackgroundActivityStartCallback(IBackgroundActivityLaunchCallback callback)
            throws android.os.RemoteException {}
}
