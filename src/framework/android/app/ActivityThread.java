/* artzig shim: android.app.ActivityThread — the hosted process thread: statics
 * for the host's application/activity/service runtimes plus the AOSP
 * ClientTransactionHandler contract so the real IApplicationThread's
 * scheduleTransaction drives the AOSP TransactionExecutor. Activity lifecycle
 * transactions are not hosted yet (HostedActivityRuntime runs the activity
 * lifecycle in-process); the handlers throw UnsupportedOperationException. */
package android.app;
public final class ActivityThread extends ClientTransactionHandler {
    public static final boolean DEBUG_MEMORY_TRIM = false;
    public static final boolean DEBUG_ORDER = false;
    /** AOSP ActivityThread.H message ids the transaction handler uses. */
    public static final class H {
        public static final int EXECUTE_TRANSACTION = 159;
        public static final int RELAUNCH_ACTIVITY = 160;
    }
    private final android.app.servertransaction.TransactionExecutor mTransactionExecutor =
            new android.app.servertransaction.TransactionExecutor(this);
    private final android.util.ArrayMap<android.os.IBinder, ActivityClientRecord> mActivities = new android.util.ArrayMap<>();
    @Override android.app.servertransaction.TransactionExecutor getTransactionExecutor() { return mTransactionExecutor; }
    @Override void sendMessage(int what, Object obj) {
        if (what == H.EXECUTE_TRANSACTION) {
            final android.app.servertransaction.ClientTransaction transaction = (android.app.servertransaction.ClientTransaction) obj;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> mTransactionExecutor.execute(transaction));
            return;
        }
        throw new UnsupportedOperationException("ActivityThread message " + what + " is not hosted");
    }
    private static UnsupportedOperationException notHosted(String what) {
        return new UnsupportedOperationException("ActivityThread." + what + " is not hosted (activity lifecycle runs in HostedActivityRuntime)");
    }
    @Override public Activity getActivity(android.os.IBinder token) { ActivityClientRecord r = mActivities.get(token); return r != null ? r.activity : null; }
    @Override public void updatePendingConfiguration(android.content.res.Configuration config) {}
    @Override public void updateProcessState(int processState, boolean fromIpc) {}
    @Override public void countLaunchingActivities(int num) {}
    @Override public java.util.Map<android.os.IBinder, android.app.servertransaction.DestroyActivityItem> getActivitiesToBeDestroyed() { return new android.util.ArrayMap<>(); }
    @Override public void handleDestroyActivity(ActivityClientRecord r, boolean finishing, boolean getNonConfigInstance, String reason) { throw notHosted("handleDestroyActivity"); }
    @Override public void handlePauseActivity(ActivityClientRecord r, boolean finished, boolean userLeaving, boolean autoEnteringPip, android.app.servertransaction.PendingTransactionActions pendingActions, String reason) { throw notHosted("handlePauseActivity"); }
    @Override public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest, boolean isForward, boolean shouldSendCompatFakeFocus, String reason) { throw notHosted("handleResumeActivity"); }
    @Override public void handleTopResumedActivityChanged(ActivityClientRecord r, boolean isTopResumedActivity, String reason) { throw notHosted("handleTopResumedActivityChanged"); }
    @Override public void handleStopActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, boolean finalStateRequest, String reason) { throw notHosted("handleStopActivity"); }
    @Override public void reportStop(android.app.servertransaction.PendingTransactionActions pendingActions) { throw notHosted("reportStop"); }
    @Override public void performRestartActivity(ActivityClientRecord r, boolean start) { throw notHosted("performRestartActivity"); }
    @Override public void reportRefresh(ActivityClientRecord r) { throw notHosted("reportRefresh"); }
    @Override public void updatePendingActivityConfiguration(android.os.IBinder token, android.content.res.Configuration overrideConfig) { throw notHosted("updatePendingActivityConfiguration"); }
    @Override public void handleActivityConfigurationChanged(ActivityClientRecord r, android.content.res.Configuration overrideConfig, int displayId, android.window.ActivityWindowInfo activityWindowInfo) { throw notHosted("handleActivityConfigurationChanged"); }
    @Override public void handleWindowContextInfoChanged(android.os.IBinder clientToken, android.window.WindowContextInfo info) { throw notHosted("handleWindowContextInfoChanged"); }
    @Override public void handleWindowContextWindowRemoval(android.os.IBinder clientToken) { throw notHosted("handleWindowContextWindowRemoval"); }
    @Override public void handleSendResult(ActivityClientRecord r, java.util.List<ResultInfo> results, String reason) { throw notHosted("handleSendResult"); }
    @Override public void handleNewIntent(ActivityClientRecord r, java.util.List<com.android.internal.content.ReferrerIntent> intents) { throw notHosted("handleNewIntent"); }
    @Override public void handlePictureInPictureRequested(ActivityClientRecord r) { throw notHosted("handlePictureInPictureRequested"); }
    @Override public void handlePictureInPictureStateChanged(ActivityClientRecord r, PictureInPictureUiState pipState) { throw notHosted("handlePictureInPictureStateChanged"); }
    @Override public boolean isHandleSplashScreenExit(android.os.IBinder token) { return false; }
    @Override public void handleAttachSplashScreenView(ActivityClientRecord r, android.window.SplashScreenView.SplashScreenViewParcelable parcelable, android.view.SurfaceControl startingWindowLeash) { throw notHosted("handleAttachSplashScreenView"); }
    @Override public Activity handleLaunchActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, int deviceId, android.content.Intent customIntent) { throw notHosted("handleLaunchActivity"); }
    @Override public void handleStartActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, ActivityOptions.SceneTransitionInfo sceneTransitionInfo) { throw notHosted("handleStartActivity"); }
    @Override public LoadedApk getPackageInfoNoCheck(android.content.pm.ApplicationInfo ai) { return new LoadedApk(); }
    @Override public void handleConfigurationChanged(android.content.res.Configuration config, int deviceId) { throw notHosted("handleConfigurationChanged"); }
    @Override public ActivityClientRecord prepareRelaunchActivity(android.os.IBinder token, java.util.List<ResultInfo> pendingResults, java.util.List<com.android.internal.content.ReferrerIntent> pendingNewIntents, int configChanges, android.util.MergedConfiguration config, boolean preserveWindow, android.window.ActivityWindowInfo activityWindowInfo) { throw notHosted("prepareRelaunchActivity"); }
    @Override public void handleRelaunchActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions) { throw notHosted("handleRelaunchActivity"); }
    @Override public void reportRelaunch(ActivityClientRecord r) { throw notHosted("reportRelaunch"); }
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
    /** AOSP ActivityThread.ActivityClientRecord: the fields the servertransaction executor reads. */
    public static final class ActivityClientRecord {
        public android.os.IBinder token;
        public Activity activity;
        public android.content.Intent intent;
        public android.content.pm.ActivityInfo activityInfo;
        public android.os.Bundle state;
        public boolean isForward;
        public LoadedApk packageInfo;
        private int mLifecycleState = android.app.servertransaction.ActivityLifecycleItem.PRE_ON_CREATE;
        public android.window.ActivityWindowInfo activityWindowInfo;
        public ActivityClientRecord() {}
        public ActivityClientRecord(android.os.IBinder token, android.content.Intent intent, int ident, android.content.pm.ActivityInfo info, android.content.res.Configuration overrideConfig, String referrer, com.android.internal.app.IVoiceInteractor voiceInteractor, android.os.Bundle state, android.os.PersistableBundle persistentState, java.util.List<ResultInfo> pendingResults, java.util.List<com.android.internal.content.ReferrerIntent> pendingNewIntents, ActivityOptions.SceneTransitionInfo sceneTransitionInfo, boolean isForward, ProfilerInfo profilerInfo, ClientTransactionHandler client, android.os.IBinder assistToken, android.os.IBinder shareableActivityToken, boolean launchedFromBubble, android.os.IBinder taskFragmentToken, android.os.IBinder initialCallerInfoAccessToken, android.window.ActivityWindowInfo activityWindowInfo) {
            this.token = token; this.intent = intent; this.activityInfo = info; this.state = state; this.isForward = isForward;
            this.activityWindowInfo = activityWindowInfo;
            if (activityWindowInfo != null) this.mActivityWindowInfo = activityWindowInfo;
        }
        public int getLifecycleState() { return mLifecycleState; }
        public void setState(int newLifecycleState) { mLifecycleState = newLifecycleState; }
        public android.window.ActivityWindowInfo mActivityWindowInfo = new android.window.ActivityWindowInfo();
        public android.window.ActivityWindowInfo getActivityWindowInfo() { return mActivityWindowInfo; }
    }
    @Override public ActivityClientRecord getActivityClient(android.os.IBinder token) { return mActivities.get(token); }
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
