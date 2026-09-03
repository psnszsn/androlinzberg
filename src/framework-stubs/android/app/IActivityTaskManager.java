/* artzig stub: activity-task-manager service interface — no ATMS on this host.
 * Only the members reused framework code links against. */
package android.app;
public interface IActivityTaskManager extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default IActivityClientController getActivityClientController() throws android.os.RemoteException { return null; }
    default String getVoiceInteractorPackageName(android.os.IBinder callingVoiceInteractor) throws android.os.RemoteException { return null; }
    default void registerBackgroundActivityStartCallback(IBackgroundActivityLaunchCallback callback)
            throws android.os.RemoteException {}
    default int startActivityAsUser(IApplicationThread caller, String callingPackage, String callingFeatureId,
            android.content.Intent intent, String resolvedType, android.os.IBinder resultTo, String resultWho,
            int requestCode, int flags, ProfilerInfo profilerInfo, android.os.Bundle options, int userId)
            throws android.os.RemoteException {
        throw new UnsupportedOperationException("IActivityTaskManager.startActivityAsUser is not hosted");
    }
    default int startActivityIntentSender(IApplicationThread caller, android.content.IIntentSender target,
            android.os.IBinder whitelistToken, android.content.Intent fillInIntent, String resolvedType,
            android.os.IBinder resultTo, String resultWho, int requestCode, int flagsMask, int flagsValues,
            android.os.Bundle options) throws android.os.RemoteException {
        throw new UnsupportedOperationException("IActivityTaskManager.startActivityIntentSender is not hosted");
    }
    /** SearchManager.launchAssist: no assistant to hand context to. */
    default android.os.Bundle getAssistContextExtras(int requestType) throws android.os.RemoteException { return null; }
}
