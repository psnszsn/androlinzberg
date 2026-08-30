package dev.artzig.system;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import dev.artzig.system.HostedProcessManifest;
import dev.artzig.system.IHostedApplicationThread;

/**
 * The hosted activity manager (the subset of android.app.IActivityManager the
 * service path needs; the real interface drags ProfilerInfo, ParceledListSlice
 * and a 57-method application thread).
 */
interface IHostedActivityManager {
    void attachApplication(IHostedApplicationThread thread, in HostedProcessManifest manifest);
    ComponentName startService(in Intent service, String callingPackage);
    int stopService(in Intent service);
    int bindService(IHostedApplicationThread caller, in Intent service, IServiceConnection connection, int flags, String callingPackage);
    boolean unbindService(IServiceConnection connection);
    void publishService(IBinder token, in Intent intent, IBinder service);
    void unbindFinished(IBinder token, in Intent intent);
    oneway void serviceDoneExecuting(IBinder token, int type, int startId, int res);
    boolean stopServiceToken(in ComponentName className, IBinder token, int startId);
    /** Returns the first matching sticky broadcast, if any. broadcastPermission (if non-null)
     * is the permission a sender must hold; flags may carry RECEIVER_NOT_EXPORTED. */
    Intent registerReceiver(IIntentReceiver receiver, in IntentFilter filter, String callingPackage, String broadcastPermission, int flags);
    void unregisterReceiver(IIntentReceiver receiver);
    int broadcastIntent(in Intent intent, IIntentReceiver resultTo, int resultCode, String requiredPermission, boolean ordered, boolean sticky, String callingPackage);
    /** Ordered-broadcast receivers report completion here (who = the registered IIntentReceiver). */
    void finishReceiver(IIntentReceiver who, int resultCode, String resultData, in Bundle resultExtras, boolean abortBroadcast);
    void removeStickyBroadcast(in Intent intent);
}
