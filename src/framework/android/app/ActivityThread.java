/* artzig shim: android.app.ActivityThread — minimal statics (full hand-written version in M2). */
package android.app;
public final class ActivityThread {
    /** android 16: PropertyInvalidatedCache test gate; the host never instruments. */
    public Instrumentation getInstrumentation() { return null; }
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
    public static final String TAG = "ActivityThread";
    public static final boolean DEBUG_BROADCAST = false;
    public static Activity currentActivity() { return HostedActivityRuntime.currentActivity(); }
    /** AOSP ActivityThread.sCurrentBroadcastIntent: the Intent a receiver on this thread is handling. */
    private static final ThreadLocal<android.content.Intent> sCurrentBroadcastIntent = new ThreadLocal<>();
    public static android.content.Intent getIntentBeingBroadcast() { return sCurrentBroadcastIntent.get(); }
    /** Set by the receiver dispatcher around onReceive (LoadedApk.ReceiverDispatcher.Args.run). */
    public static void setIntentBeingBroadcast$artzig(android.content.Intent intent) { sCurrentBroadcastIntent.set(intent); }
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
    /** The session's package manager binder (AOSP static of the same name). */
    private static volatile android.content.pm.IPackageManager sPackageManager;
    public static android.content.pm.IPackageManager getPackageManager() {
        android.content.pm.IPackageManager pm = sPackageManager;
        if (pm != null) return pm;
        android.os.IBinder binder = android.os.ServiceManager.waitForService("package");
        if (binder == null) {
            throw new IllegalStateException("no hosted package manager in this session");
        }
        pm = android.content.pm.IPackageManager.Stub.asInterface(binder);
        sPackageManager = pm;
        return pm;
    }

    /** ApplicationPackageManager.getResourcesForApplication comes through
     * here (AOSP builds via ResourcesManager); the hosted build loads the
     * target APK next to the framework resources. */
    public android.content.res.Resources getTopLevelResources(String resDir,
            String[] splitResDirs, String[] legacyOverlayDirs, String[] overlayPaths,
            String[] libDirs, LoadedApk pkgInfo, android.content.res.Configuration overrideConfig) {
        try {
            android.content.res.AssetManager.Builder builder =
                    new android.content.res.AssetManager.Builder();
            String frameworkRes = System.getenv("ARTZIG_FRAMEWORK_RES");
            if (frameworkRes != null) {
                builder.addApkAssets(android.content.res.ApkAssets.loadFromPath(
                        frameworkRes, android.content.res.ApkAssets.PROPERTY_SYSTEM));
            }
            builder.addApkAssets(android.content.res.ApkAssets.loadFromPath(resDir));
            android.content.res.AssetManager assets = builder.build();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            metrics.setToDefaults();
            android.content.res.Configuration config = new android.content.res.Configuration();
            config.setToDefaults();
            if (overrideConfig != null) config.updateFrom(overrideConfig);
            return android.content.res.HostedResources.create(assets, metrics, config, null);
        } catch (java.io.IOException e) {
            android.util.Log.w("ActivityThread", "cannot load resources of " + resDir, e);
            return null;
        }
    }

    /** Registers this process with the activity manager (AOSP attachApplication). */
    public static void attachApplicationHosted(String packageName) {
        HostedServiceClient.attachApplication(packageName);
    }
    public static Application currentApplication() { return sApplication; }
    /** Never null: generated AIDL stubs fetch the PermissionEnforcer context through this. */
    public static synchronized ActivityThread currentActivityThread() {
        if (sThread == null) sThread = new ActivityThread();
        return sThread;
    }
    public Application getApplication() { return sApplication; }
    /** AOSP schedules a GC after freeStorage; nothing to schedule here. */
    public void scheduleGcIdler() {}
    public android.content.Context getSystemContext() { return sApplication; }
    public android.content.Context getSystemUiContext() { return sApplication; }
    public static class ActivityClientRecord {
        public android.window.ActivityWindowInfo mActivityWindowInfo = new android.window.ActivityWindowInfo();
        public android.window.ActivityWindowInfo getActivityWindowInfo() { return mActivityWindowInfo; }
    }
    public ActivityClientRecord getActivityClient(android.os.IBinder token) { return null; }
    public static String currentPackageName() { return sPackageName; }
    /** AOSP ActivityThread.currentAttributionSource: this process's identity (broker uid, bound package). */
    public static android.content.AttributionSource currentAttributionSource() {
        return new android.content.AttributionSource(android.os.Process.myUid(), sPackageName, null);
    }
    public static String currentProcessName() { return sProcessName; }
    private static boolean sSystem;
    /** Called once by the session's system process before any Context exists. */
    public static void markSystem() { sSystem = true; sPackageName = "android"; }
    public static boolean isSystem() { return sSystem; }
    public static void setApplication(Application app) { sApplication = app; }
}
