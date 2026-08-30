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
        android.content.ComponentName resolveActivity(android.content.Intent intent);
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
    public static android.content.ComponentName resolveActivityInProcess(
            android.content.Intent intent) {
        return HostedActivityRuntime.isConfigured()
                ? HostedActivityRuntime.resolveActivity(intent) : null;
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
    // Service calls go to the session's activity manager ("activity" in the
    // broker registry); HostedServiceRuntime only executes its callbacks here.
    public static android.content.ComponentName startServiceInProcess(android.content.Intent intent) {
        return HostedServiceClient.startService(intent);
    }
    public static boolean stopServiceInProcess(android.content.Intent intent) {
        return HostedServiceClient.stopService(intent);
    }
    public static boolean bindServiceInProcess(android.content.Context context,
            android.content.Intent intent, android.content.ServiceConnection conn, int flags) {
        return HostedServiceClient.bindService(context, intent, conn, flags);
    }
    public static void unbindServiceInProcess(android.content.ServiceConnection conn) {
        HostedServiceClient.unbindService(conn);
    }
    /** Registers this process with the activity manager (AOSP attachApplication). */
    public static void attachApplicationHosted(dev.artzig.system.HostedProcessManifest manifest) {
        HostedServiceClient.attachApplication(manifest);
    }
    public static Application currentApplication() { return sApplication; }
    /** Never null: generated AIDL stubs fetch the PermissionEnforcer context through this. */
    public static synchronized ActivityThread currentActivityThread() {
        if (sThread == null) sThread = new ActivityThread();
        return sThread;
    }
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
    private static boolean sSystem;
    /** Called once by the session's system process before any Context exists. */
    public static void markSystem() { sSystem = true; sPackageName = "android"; }
    public static boolean isSystem() { return sSystem; }
    public static void setApplication(Application app) { sApplication = app; }
}
