package dev.artzig.system;

import android.content.ComponentName;
import android.content.Intent;

/**
 * The activity manager's handle to one hosted app process (the subset of
 * android.app.IApplicationThread the hosted service path needs). All calls are
 * oneway, as in AOSP; the app process runs them on its main looper.
 */
oneway interface IHostedApplicationThread {
    void scheduleCreateService(IBinder token, in ComponentName name);
    void scheduleBindService(IBinder token, in Intent intent, boolean rebind);
    void scheduleUnbindService(IBinder token, in Intent intent);
    void scheduleServiceArgs(IBinder token, int startId, int flags, in Intent args);
    void scheduleStopService(IBinder token);
    void scheduleExit();
}
