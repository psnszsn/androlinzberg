/* artzig shim: services run in-process (ApkHost drives the lifecycle). */
package android.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.IBinder;

public abstract class Service extends ContextWrapper {
    public static final int START_STICKY_COMPATIBILITY = 0;
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int START_REDELIVER_INTENT = 3;
    public static final int START_FLAG_REDELIVERY = 1;
    public static final int START_FLAG_RETRY = 2;
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static final int STOP_FOREGROUND_DETACH = 2;

    private String mClassName;

    public Service() { super(null); }

    /** artzig: called by the in-process service manager. */
    public final void attach$artzig(Context base, String className) {
        attachBaseContext(base);
        mClassName = className;
    }

    public Application getApplication() { return ActivityThread.currentApplication(); }

    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) {
        onStart(intent, startId);
        return START_STICKY;
    }
    public void onStart(Intent intent, int startId) {}
    public void onDestroy() {}
    public abstract IBinder onBind(Intent intent);
    public boolean onUnbind(Intent intent) { return false; }
    public void onRebind(Intent intent) {}
    public void onTaskRemoved(Intent rootIntent) {}
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
    public void onLowMemory() {}
    public void onTrimMemory(int level) {}

    public final void stopSelf() { stopSelf(-1); }
    public final void stopSelf(int startId) {
        ActivityThread.stopServiceInProcess(new Intent().setClassName(getPackageName(),
                mClassName != null ? mClassName : getClass().getName()));
    }
    public final boolean stopSelfResult(int startId) { stopSelf(startId); return true; }
    public final void startForeground(int id, Notification notification) {}
    public final void startForeground(int id, Notification notification, int foregroundServiceType) {}
    public final void stopForeground(boolean removeNotification) {}
    public final void stopForeground(int flags) {}
    public final int getForegroundServiceType() { return 0; }
}
