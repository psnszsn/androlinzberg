/* artzig shim: android.app.ActivityThread — minimal statics (full hand-written version in M2). */
package android.app;
public final class ActivityThread {
    private static Application sApplication;
    private static ActivityThread sThread;
    private static String sPackageName;
    private static String sProcessName;
    /** Host-only presentation seam; lifecycle policy stays in HostedActivityRuntime. */
    public interface HostAdapter {
        android.content.Context getActivityContext();
        java.lang.ClassLoader getActivityClassLoader();
        void showActivity(Activity activity);
        void clearActivityWindows();
        void resizeActivityRoot(Activity activity, int width, int height);
    }
    public static void configureActivityHost(HostAdapter adapter,
            java.util.Map<String, android.content.pm.ActivityInfo> activities) {
        HostedActivityRuntime.configure(adapter, activities);
    }
    public static Activity launchMainActivity(String activityClassName) {
        return HostedActivityRuntime.launchMain(activityClassName);
    }
    public static Activity currentActivity() { return HostedActivityRuntime.currentActivity(); }
    public static int getManifestOrientation(String activityClassName) {
        return HostedActivityRuntime.manifestOrientation(activityClassName);
    }
    public static boolean applyConfigurationInProcess(android.content.res.Configuration config,
            int changes, int width, int height) {
        return HostedActivityRuntime.applyConfiguration(config, changes, width, height);
    }
    public static void shutdownActivitiesInProcess() { HostedActivityRuntime.shutdown(); }
    public static Application bindApplicationInProcess(android.content.Context context,
            java.lang.ClassLoader classLoader, String applicationClassName,
            java.util.List<android.content.pm.ProviderInfo> providers) {
        // AOSP receives both names in AppBindData before installing providers.
        sPackageName = context.getPackageName();
        sProcessName = sPackageName;
        return HostedApplicationRuntime.bind(
                context, classLoader, applicationClassName, providers);
    }
    public static void shutdownApplicationInProcess() {
        HostedApplicationRuntime.shutdown();
        sPackageName = null;
        sProcessName = null;
    }
    public static void startActivityInProcess(android.content.Intent intent) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.startActivity(intent);
        else android.util.Log.w("ActivityThread", "startActivity dropped (no launcher): " + intent);
    }
    public static void finishActivityInProcess(Activity activity) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.finishActivity(activity);
    }
    public static void recreateActivityInProcess(Activity activity) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.recreateActivity(activity);
    }
    public static void configureServiceHost(android.content.Context context,
            java.lang.ClassLoader classLoader) {
        HostedServiceRuntime.configure(context, classLoader);
    }
    public static void shutdownServicesInProcess() { HostedServiceRuntime.shutdown(); }
    public static android.content.ComponentName startServiceInProcess(android.content.Intent intent) {
        if (HostedServiceRuntime.isConfigured()) return HostedServiceRuntime.startService(intent);
        android.util.Log.w("ActivityThread", "startService dropped (no manager): " + intent);
        return null;
    }
    public static boolean stopServiceInProcess(android.content.Intent intent) {
        if (HostedServiceRuntime.isConfigured()) return HostedServiceRuntime.stopService(intent);
        return false;
    }
    public static boolean bindServiceInProcess(android.content.Intent intent,
            android.content.ServiceConnection conn, int flags) {
        if (HostedServiceRuntime.isConfigured()) {
            return HostedServiceRuntime.bindService(intent, conn, flags);
        }
        return false;
    }
    public static void unbindServiceInProcess(android.content.ServiceConnection conn) {
        if (HostedServiceRuntime.isConfigured()) HostedServiceRuntime.unbindService(conn);
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
    public static String currentPackageName() { return sPackageName; }
    public static String currentProcessName() { return sProcessName; }
    public static boolean isSystem() { return false; }
    public static void setApplication(Application app) { sApplication = app; }
}
