/* artzig shim: android.app.ActivityThread — minimal statics (full hand-written version in M2). */
package android.app;
public final class ActivityThread {
    private static Application sApplication;
    private static ActivityThread sThread;
    /** artzig: in-process activity launching (ApkHost installs the launcher). */
    public interface Launcher {
        void startActivity(android.content.Intent intent);
        void finishActivity(Activity activity);
    }
    private static Launcher sLauncher;
    public static void setLauncher(Launcher launcher) { sLauncher = launcher; }
    public static void startActivityInProcess(android.content.Intent intent) {
        if (sLauncher != null) sLauncher.startActivity(intent);
        else android.util.Log.w("ActivityThread", "startActivity dropped (no launcher): " + intent);
    }
    public static void finishActivityInProcess(Activity activity) {
        if (sLauncher != null) sLauncher.finishActivity(activity);
    }
    /** artzig: in-process services (ApkHost installs the manager). */
    public interface ServiceManagerHook {
        android.content.ComponentName startService(android.content.Intent intent);
        boolean stopService(android.content.Intent intent);
        boolean bindService(android.content.Intent intent, android.content.ServiceConnection conn, int flags);
        void unbindService(android.content.ServiceConnection conn);
    }
    private static ServiceManagerHook sServices;
    public static void setServiceManager(ServiceManagerHook hook) { sServices = hook; }
    public static android.content.ComponentName startServiceInProcess(android.content.Intent intent) {
        if (sServices != null) return sServices.startService(intent);
        android.util.Log.w("ActivityThread", "startService dropped (no manager): " + intent);
        return null;
    }
    public static boolean stopServiceInProcess(android.content.Intent intent) {
        return sServices != null && sServices.stopService(intent);
    }
    public static boolean bindServiceInProcess(android.content.Intent intent,
            android.content.ServiceConnection conn, int flags) {
        return sServices != null && sServices.bindService(intent, conn, flags);
    }
    public static void unbindServiceInProcess(android.content.ServiceConnection conn) {
        if (sServices != null) sServices.unbindService(conn);
    }
    public static Application currentApplication() { return sApplication; }
    public static ActivityThread currentActivityThread() { return sThread; }
    public Application getApplication() { return sApplication; }
    public android.content.Context getSystemContext() { return sApplication; }
    public android.content.Context getSystemUiContext() { return sApplication; }
    public static class ActivityClientRecord {
        public android.window.ActivityWindowInfo mActivityWindowInfo = new android.window.ActivityWindowInfo();
        public android.window.ActivityWindowInfo getActivityWindowInfo() { return mActivityWindowInfo; }
    }
    public ActivityClientRecord getActivityClient(android.os.IBinder token) { return null; }
    public static String currentPackageName() { return sApplication != null ? "app" : null; }
    public static String currentProcessName() { return "artzig"; }
    public static boolean isSystem() { return false; }
    public static void setApplication(Application app) { sApplication = app; }
}
