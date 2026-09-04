/* artzig shim: android.app.ActivityThread — the hosted process thread. The
 * process-facing surface the real Context/ContextImpl/LoadedApk/Application/
 * Service/ContentResolver use is the AOSP instance shape (package caches,
 * provider connections, service handlers, bind-application ordering, system
 * context), plus the AOSP ClientTransactionHandler contract so the real
 * IApplicationThread's scheduleTransaction drives the AOSP TransactionExecutor.
 * What is host-specific: the activity lifecycle still runs in
 * HostedActivityRuntime behind the HostAdapter seam (real Activity/
 * performLaunchActivity arrive with the activity-core milestone), and a
 * process is bound to its package by the host entry points below instead of a
 * zygote/AMS handshake. */
package android.app;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class ActivityThread extends ClientTransactionHandler {
    public static final String TAG = "ActivityThread";
    public static final boolean DEBUG = false;
    public static final boolean DEBUG_CONFIGURATION = false;
    public static final boolean DEBUG_MEMORY_TRIM = false;
    public static final boolean DEBUG_ORDER = false;
    public static final boolean DEBUG_BROADCAST = false;

    static final int SERVICE_DONE_EXECUTING_ANON = 0;
    static final int SERVICE_DONE_EXECUTING_START = 1;
    static final int SERVICE_DONE_EXECUTING_STOP = 2;
    static final int SERVICE_DONE_EXECUTING_REBIND = 3;
    static final int SERVICE_DONE_EXECUTING_UNBIND = 4;

    /** AOSP AppBindData: the parts LoadedApk/Instrumentation/handleBindApplication read. */
    static final class AppBindData {
        LoadedApk info;
        ApplicationInfo appInfo;
        String processName;
        boolean isSdkInSandbox;
        List<ProviderInfo> providers;
    }
    static final class CreateServiceData {
        IBinder token;
        ServiceInfo info;
        CompatibilityInfo compatInfo;
        Intent intent;
        public String toString() {
            return "CreateServiceData{token=" + token + " className=" + info.name
                    + " packageName=" + info.packageName + " intent=" + intent + "}";
        }
    }
    static final class BindServiceData {
        IBinder token;
        Intent intent;
        boolean rebind;
        long bindSeq;
    }
    static final class ServiceArgsData {
        IBinder token;
        boolean taskRemoved;
        int startId;
        int flags;
        Intent args;
    }

    private static volatile ActivityThread sCurrentActivityThread;
    private static boolean sSystem;
    private static volatile android.content.pm.IPackageManager sPackageManager;

    AppBindData mBoundApplication;
    /** Instrumented-package fields LoadedApk reads (no instrumentation on this host). */
    String mInstrumentationPackageName, mInstrumentationAppDir, mInstrumentationLibDir,
            mInstrumentedAppDir, mInstrumentedLibDir;
    String[] mInstrumentationSplitAppDirs, mInstrumentedSplitAppDirs;
    /** The process's Instrumentation (AOSP: created in handleBindApplication; here at construction). */
    Instrumentation mInstrumentation;
    Application mInitialApplication;
    final ArrayList<Application> mAllApplications = new ArrayList<>();
    final ResourcesManager mResourcesManager = ResourcesManager.getInstance();
    final CompatibilityInfo mCompatibilityInfo = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
    private final ArrayMap<String, WeakReference<LoadedApk>> mPackages = new ArrayMap<>();
    private final ArrayMap<String, WeakReference<LoadedApk>> mResourcePackages = new ArrayMap<>();
    private final Handler mH = new Handler(Looper.getMainLooper());
    /** AOSP ActivityThread.sMainThreadHandler (SharedPreferencesImpl posts listeners through it). */
    static volatile Handler sMainThreadHandler;
    private final Executor mExecutor = new HandlerExecutor(mH);
    private final Bundle mCoreSettings = new Bundle();
    private ContextImpl mSystemContext;
    final ArrayMap<IBinder, Service> mServices = new ArrayMap<>();
    final ArrayMap<IBinder, CreateServiceData> mServicesData = new ArrayMap<>();
    /** Providers this process hosts, by authority (AOSP mLocalProviders + ProviderClientRecord, reduced). */
    private final ArrayMap<String, ContentProvider> mLocalProvidersByAuthority = new ArrayMap<>();

    private ActivityThread() {
        sMainThreadHandler = mH;
        mInstrumentation = new Instrumentation();
        mInstrumentation.basicInit(this);
    }

    // ------------------------------------------------------------ process identity (AOSP statics)

    /** Never null: generated AIDL stubs fetch the PermissionEnforcer context through this. */
    public static ActivityThread currentActivityThread() {
        ActivityThread thread = sCurrentActivityThread;
        if (thread == null) {
            synchronized (ActivityThread.class) {
                thread = sCurrentActivityThread;
                if (thread == null) {
                    thread = new ActivityThread();
                    sCurrentActivityThread = thread;
                }
            }
        }
        return thread;
    }
    /** AOSP ActivityThread.systemMain: the system process's thread (no AMS attach here). */
    public static ActivityThread systemMain() {
        markSystem();
        return currentActivityThread();
    }
    /** Called once by a system process before any Context exists. */
    public static void markSystem() { sSystem = true; }
    public static boolean isSystem() { return sSystem; }
    public static Application currentApplication() {
        ActivityThread am = sCurrentActivityThread;
        return am != null ? am.mInitialApplication : null;
    }
    public static String currentPackageName() {
        ActivityThread am = sCurrentActivityThread;
        if (am != null && am.mBoundApplication != null) return am.mBoundApplication.appInfo.packageName;
        return sSystem ? "android" : null;
    }
    public static String currentProcessName() {
        ActivityThread am = sCurrentActivityThread;
        if (am != null && am.mBoundApplication != null) return am.mBoundApplication.processName;
        return sSystem ? "system_server" : null;
    }
    public static String currentOpPackageName() {
        ActivityThread am = sCurrentActivityThread;
        return (am != null && am.getApplication() != null) ? am.getApplication().getOpPackageName() : null;
    }
    /** AOSP ActivityThread.currentAttributionSource: this process's identity (broker uid, bound package). */
    public static android.content.AttributionSource currentAttributionSource() {
        return new android.content.AttributionSource(android.os.Process.myUid(), currentPackageName(), null);
    }
    /** The session's package manager binder (AOSP static of the same name). */
    public static android.content.pm.IPackageManager getPackageManager() {
        android.content.pm.IPackageManager pm = sPackageManager;
        if (pm != null) return pm;
        IBinder binder = android.os.ServiceManager.waitForService("package");
        if (binder == null) throw new IllegalStateException("no hosted package manager in this session");
        pm = android.content.pm.IPackageManager.Stub.asInterface(binder);
        sPackageManager = pm;
        return pm;
    }
    /** AOSP: the unsafe-intent-launch protected-broadcast query; StrictMode gates it. */
    public static boolean isProtectedBroadcast(Intent intent) {
        if (!android.os.StrictMode.vmUnsafeIntentLaunchEnabled()) return false;
        try { return getPackageManager().isProtectedBroadcast(intent.getAction()); }
        catch (RemoteException ignored) { }
        return false;
    }
    /** AOSP ActivityThread.isProtectedComponent: exported-with-permission or non-exported components. */
    public static boolean isProtectedComponent(ComponentInfo ci, String permission) {
        return !ci.exported || permission != null;
    }
    public static boolean isProtectedComponent(ServiceInfo si) { return isProtectedComponent(si, si.permission); }
    public static boolean isProtectedComponent(ActivityInfo ai) { return isProtectedComponent(ai, ai.permission); }

    // ------------------------------------------------------------ instance surface

    public Handler getHandler() { return mH; }
    public Looper getLooper() { return Looper.getMainLooper(); }
    public Executor getExecutor() { return mExecutor; }
    /** AOSP ActivityThread.post: main-looper delivery for LoadedApk's receiver/service dispatchers. */
    final boolean post(Runnable r) { return mH.post(r); }
    /** AOSP returns the ApplicationThread Binder (IApplicationThread.Stub); Instrumentation takes it as an IBinder. */
    public IApplicationThread.Stub getApplicationThread() { return (IApplicationThread.Stub) HostedServiceClient.applicationThread(); }
    public Instrumentation getInstrumentation() { return mInstrumentation; }
    public Application getApplication() { return mInitialApplication; }
    public String getProcessName() { String p = currentProcessName(); return p != null ? p : "android"; }
    public boolean isProfiling() { return false; }
    public String getProfileFilePath() { return null; }
    /*package*/ final void finishInstrumentation(int resultCode, Bundle results) {}
    Bundle getCoreSettings() { synchronized (mCoreSettings) { return mCoreSettings; } }
    public String getStringCoreSetting(String key, String defaultValue) {
        synchronized (mCoreSettings) { return mCoreSettings.getString(key, defaultValue); }
    }
    /** LoadedApk.makeApplicationInner registers every Application it creates. */
    void addApplication(Application app) { mAllApplications.add(app); }
    /** AOSP schedules a GC after freeStorage; nothing to schedule here. */
    public void scheduleGcIdler() {}
    public void unscheduleGcIdler() {}
    /** AOSP ContextImpl.performFinalCleanup runs on the main thread after the component is gone. */
    final void scheduleContextCleanup(ContextImpl context, String who, String what) {
        mH.post(() -> context.performFinalCleanup(who, what));
    }
    /** AOSP: releases the SystemUI display context; the hosted system UI context is the system context. */
    public void onSystemUiContextCleanup(Context context) {}

    /** AOSP ActivityThread.getSystemContext: one ContextImpl over the "android" LoadedApk. */
    public ContextImpl getSystemContext() {
        synchronized (this) {
            if (mSystemContext == null) mSystemContext = ContextImpl.createSystemContext(this);
            return mSystemContext;
        }
    }
    public ContextImpl getSystemUiContext() { return getSystemContext(); }
    /** AOSP ActivityThread.installSystemApplicationInfo (SystemServer calls it once the "android"
     * package is known): gives the system context its data directory and class loader. */
    public void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        synchronized (this) {
            getSystemContext().installSystemApplicationInfo(info, classLoader);
        }
    }

    // ------------------------------------------------------------ providers (AOSP acquireProvider family)

    /** Hosted: HostedServiceClient keeps one connection per authority for the
     * process lifetime (no ProviderRefCount release timers); the activity
     * manager spawns the owning package on demand. */
    public final IContentProvider acquireProvider(Context c, String auth, int userId, boolean stable) {
        return HostedServiceClient.acquireContentProvider(c, auth);
    }
    public final IContentProvider acquireExistingProvider(Context c, String auth, int userId, boolean stable) {
        return HostedServiceClient.existingContentProvider(auth);
    }
    /** AOSP returns whether the last reference was dropped; hosted connections stay. */
    public final boolean releaseProvider(IContentProvider provider, boolean stable) { return false; }
    public final void handleUnstableProviderDied(IBinder provider, boolean fromClient) {
        HostedServiceClient.forgetContentProvider(provider);
    }
    public final void appNotRespondingViaProvider(IBinder provider) {}
    /** A provider installed in this process, keyed by one of its authorities. */
    public ContentProvider localProvider(String authority) {
        synchronized (mLocalProvidersByAuthority) { return mLocalProvidersByAuthority.get(authority); }
    }

    /** AOSP ActivityThread.installContentProviders: instantiate, attachInfo, publish. */
    private void installContentProviders(Context context, List<ProviderInfo> providers) {
        final ArrayList<ContentProviderHolder> results = new ArrayList<>();
        for (ProviderInfo cpi : providers) {
            ContentProviderHolder cph = installProvider(context, cpi);
            if (cph != null) {
                cph.noReleaseNeeded = true;
                results.add(cph);
            }
        }
        IActivityManager am = HostedServiceClient.activityManagerOrNull();
        if (am == null || results.isEmpty()) return;
        try {
            am.publishContentProviders(getApplicationThread(), results);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }
    /** AOSP ActivityThread.installProvider for a local provider (the only kind
     * this process installs; remote holders are cached by HostedServiceClient). */
    private ContentProviderHolder installProvider(Context context, ProviderInfo info) {
        ContentProvider localProvider;
        IContentProvider provider;
        try {
            final ClassLoader cl = context.getClassLoader();
            LoadedApk packageInfo = peekPackageInfo(info.applicationInfo.packageName, true);
            if (packageInfo == null) packageInfo = getSystemContext().mPackageInfo;
            localProvider = packageInfo.getAppFactory().instantiateProvider(cl, info.name);
            provider = localProvider.getIContentProvider();
            if (provider == null) {
                Log.e(TAG, "Failed to instantiate class " + info.name + " from sourceDir "
                        + info.applicationInfo.sourceDir);
                return null;
            }
            localProvider.attachInfo(context, info);
        } catch (Exception e) {
            if (!mInstrumentation.onException(null, e)) {
                throw new RuntimeException("Unable to get provider " + info.name + ": " + e.toString(), e);
            }
            return null;
        }
        ContentProviderHolder holder = new ContentProviderHolder(info);
        holder.provider = provider;
        holder.noReleaseNeeded = true;
        synchronized (mLocalProvidersByAuthority) {
            if (info.authority != null) {
                for (String auth : info.authority.split(";")) mLocalProvidersByAuthority.put(auth, localProvider);
            }
        }
        return holder;
    }

    // ------------------------------------------------------------ LoadedApk cache (AOSP getPackageInfo)

    public final LoadedApk getPackageInfo(ApplicationInfo ai, CompatibilityInfo compatInfo, int flags) {
        boolean includeCode = (flags & Context.CONTEXT_INCLUDE_CODE) != 0;
        boolean securityViolation = includeCode && ai.uid != 0
                && ai.uid != android.os.Process.SYSTEM_UID && (mBoundApplication != null
                        ? !android.os.UserHandle.isSameApp(ai.uid, mBoundApplication.appInfo.uid) : true);
        boolean registerPackage = includeCode && (flags & Context.CONTEXT_REGISTER_PACKAGE) != 0;
        if ((flags & (Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY))
                == Context.CONTEXT_INCLUDE_CODE && securityViolation) {
            throw new SecurityException("Requesting code from " + ai.packageName + " (with uid " + ai.uid + ")");
        }
        return getPackageInfo(ai, compatInfo, null, securityViolation, includeCode, registerPackage);
    }
    /** AOSP ActivityThread.getPackageInfo(String, ...): the package's ApplicationInfo from the
     * session package manager (AOSP reads it through the PackageManager cache). */
    public final LoadedApk getPackageInfo(String packageName, CompatibilityInfo compatInfo, int flags, int userId) {
        ApplicationInfo ai;
        try {
            ai = getPackageManager().getApplicationInfo(packageName,
                    (long) android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES,
                    userId < 0 ? android.os.UserHandle.myUserId() : userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return ai != null ? getPackageInfo(ai, compatInfo, flags) : null;
    }
    public final LoadedApk getPackageInfoNoCheck(ApplicationInfo ai, CompatibilityInfo compatInfo) {
        return getPackageInfo(ai, compatInfo, null, false, true, false);
    }
    @Override public LoadedApk getPackageInfoNoCheck(ApplicationInfo ai) {
        return getPackageInfo(ai, mCompatibilityInfo, null, false, true, false);
    }
    public final LoadedApk peekPackageInfo(String packageName, boolean includeCode) {
        synchronized (mResourcesManager) {
            WeakReference<LoadedApk> ref = includeCode ? mPackages.get(packageName) : mResourcePackages.get(packageName);
            return ref != null ? ref.get() : null;
        }
    }
    private LoadedApk getPackageInfo(ApplicationInfo aInfo, CompatibilityInfo compatInfo, ClassLoader baseLoader,
            boolean securityViolation, boolean includeCode, boolean registerPackage) {
        synchronized (mResourcesManager) {
            WeakReference<LoadedApk> ref = includeCode ? mPackages.get(aInfo.packageName) : mResourcePackages.get(aInfo.packageName);
            LoadedApk packageInfo = ref != null ? ref.get() : null;
            if (packageInfo != null) return packageInfo;
            packageInfo = new LoadedApk(this, aInfo, compatInfo, baseLoader, securityViolation,
                    includeCode && (aInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0, registerPackage);
            if (includeCode) mPackages.put(aInfo.packageName, new WeakReference<>(packageInfo));
            else mResourcePackages.put(aInfo.packageName, new WeakReference<>(packageInfo));
            return packageInfo;
        }
    }
    /** AOSP ActivityThread.getTopLevelResources (ApplicationPackageManager.getResourcesForApplication). */
    public Resources getTopLevelResources(String resDir, String[] splitResDirs, String[] legacyOverlayDirs,
            String[] overlayPaths, String[] libDirs, LoadedApk pkgInfo, Configuration overrideConfig) {
        return mResourcesManager.getResources(null, resDir, splitResDirs, legacyOverlayDirs,
                overlayPaths, libDirs, null, overrideConfig, pkgInfo.getCompatibilityInfo(),
                pkgInfo.getClassLoader(), null);
    }

    // ------------------------------------------------------------ bind application (AOSP handleBindApplication)

    /** Host entry replacing the zygote/AMS bindApplication handshake: the process
     * is bound to an installed package. AOSP ordering is kept: LoadedApk,
     * Instrumentation, Application (attach), content providers (install +
     * publish), then Application.onCreate. */
    public Application handleBindApplication$artzig(ApplicationInfo appInfo, List<ProviderInfo> providers) {
        if (mBoundApplication != null) throw new IllegalStateException("an Application is already bound to this process");
        final AppBindData data = new AppBindData();
        data.appInfo = appInfo;
        data.processName = appInfo.processName != null ? appInfo.processName : appInfo.packageName;
        data.providers = providers;
        mBoundApplication = data;
        android.os.Process.setArgV0(data.processName);
        data.info = getPackageInfoNoCheck(data.appInfo, mCompatibilityInfo);
        mInstrumentation = new Instrumentation();
        mInstrumentation.basicInit(this);
        final Application app = data.info.makeApplicationInner(false, null);
        mInitialApplication = app;
        if (providers != null && !providers.isEmpty()) installContentProviders(app, providers);
        try {
            mInstrumentation.callApplicationOnCreate(app);
        } catch (Exception e) {
            if (!mInstrumentation.onException(app, e)) {
                throw new RuntimeException("Unable to create application " + app.getClass().getName()
                        + ": " + e.toString(), e);
            }
        }
        return app;
    }
    /** AOSP ActivityThread.attach(system): the system process's Application over the system context. */
    public Application bindSystemApplication$artzig() {
        if (mInitialApplication != null) return mInitialApplication;
        ContextImpl context = getSystemContext();
        mInitialApplication = context.mPackageInfo.makeApplicationInner(true, null);
        mInitialApplication.onCreate();
        return mInitialApplication;
    }
    /** Host shutdown: AOSP never terminates an Application on a device; the
     * hosted process does when its window closes. */
    public void shutdownApplication$artzig() {
        Application app = mInitialApplication;
        if (app != null) app.onTerminate();
        mInitialApplication = null;
        mBoundApplication = null;
        synchronized (mLocalProvidersByAuthority) { mLocalProvidersByAuthority.clear(); }
    }

    // ------------------------------------------------------------ services (AOSP handle*Service)

    public void handleCreateService(CreateServiceData data) {
        unscheduleGcIdler();
        final LoadedApk packageInfo = getPackageInfoNoCheck(data.info.applicationInfo, mCompatibilityInfo);
        Service service = null;
        try {
            Application app = packageInfo.makeApplicationInner(false, mInstrumentation);
            final ClassLoader cl = packageInfo.getClassLoader();
            service = packageInfo.getAppFactory().instantiateService(cl, data.info.name, data.intent);
            ContextImpl context = ContextImpl.getImpl(service.createServiceBaseContext(this, packageInfo));
            context.getResources().addLoaders(
                    app.getResources().getLoaders().toArray(new android.content.res.loader.ResourcesLoader[0]));
            context.setOuterContext(service);
            service.attach(context, this, data.info.name, data.token, app, ActivityManager.getService());
            service.onCreate();
            mServicesData.put(data.token, data);
            mServices.put(data.token, service);
            try {
                ActivityManager.getService().serviceDoneExecuting(data.token, SERVICE_DONE_EXECUTING_ANON, 0, 0, null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(service, e)) {
                throw new RuntimeException("Unable to create service " + data.info.name + ": " + e.toString(), e);
            }
        }
    }
    public void handleBindService(BindServiceData data) {
        CreateServiceData createData = mServicesData.get(data.token);
        Service s = mServices.get(data.token);
        if (s == null) return;
        try {
            data.intent.setExtrasClassLoader(s.getClassLoader());
            data.intent.prepareToEnterProcess(isProtectedComponent(createData.info), s.getAttributionSource());
            try {
                if (!data.rebind) {
                    IBinder binder = s.onBind(data.intent);
                    ActivityManager.getService().publishService(data.token, data.intent, binder);
                } else {
                    s.onRebind(data.intent);
                    ActivityManager.getService().serviceDoneExecuting(data.token, SERVICE_DONE_EXECUTING_REBIND, 0, 0, data.intent);
                }
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(s, e)) {
                throw new RuntimeException("Unable to bind to service " + s + " with " + data.intent + ": " + e.toString(), e);
            }
        }
    }
    public void handleUnbindService(BindServiceData data) {
        CreateServiceData createData = mServicesData.get(data.token);
        Service s = mServices.get(data.token);
        if (s == null) return;
        try {
            data.intent.setExtrasClassLoader(s.getClassLoader());
            data.intent.prepareToEnterProcess(isProtectedComponent(createData.info), s.getAttributionSource());
            boolean doRebind = s.onUnbind(data.intent);
            try {
                if (doRebind) {
                    ActivityManager.getService().unbindFinished(data.token, data.intent);
                } else {
                    ActivityManager.getService().serviceDoneExecuting(data.token, SERVICE_DONE_EXECUTING_UNBIND, 0, 0, data.intent);
                }
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(s, e)) {
                throw new RuntimeException("Unable to unbind to service " + s + " with " + data.intent + ": " + e.toString(), e);
            }
        }
    }
    public void handleServiceArgs(ServiceArgsData data) {
        CreateServiceData createData = mServicesData.get(data.token);
        Service s = mServices.get(data.token);
        if (s == null) return;
        try {
            if (data.args != null) {
                data.args.setExtrasClassLoader(s.getClassLoader());
                data.args.prepareToEnterProcess(isProtectedComponent(createData.info), s.getAttributionSource());
            }
            int res;
            if (!data.taskRemoved) {
                res = s.onStartCommand(data.args, data.flags, data.startId);
            } else {
                s.onTaskRemoved(data.args);
                res = Service.START_TASK_REMOVED_COMPLETE;
            }
            QueuedWork.waitToFinish();
            try {
                ActivityManager.getService().serviceDoneExecuting(data.token, SERVICE_DONE_EXECUTING_START, data.startId, res, null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(s, e)) {
                throw new RuntimeException("Unable to start service " + s + " with " + data.args + ": " + e.toString(), e);
            }
        }
    }
    public void handleStopService(IBinder token) {
        mServicesData.remove(token);
        Service s = mServices.remove(token);
        if (s == null) {
            Log.i(TAG, "handleStopService: token=" + token + " not found.");
            return;
        }
        try {
            s.onDestroy();
            s.detachAndCleanUp();
            Context context = s.getBaseContext();
            if (context instanceof ContextImpl) {
                ((ContextImpl) context).scheduleFinalCleanup(s.getClassName(), "Service");
            }
            QueuedWork.waitToFinish();
            try {
                ActivityManager.getService().serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_STOP, 0, 0, null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(s, e)) {
                throw new RuntimeException("Unable to stop service " + s + ": " + e.toString(), e);
            }
            Log.i(TAG, "handleStopService: exception for " + token, e);
        }
    }
    /** Host shutdown: destroy every service this process still runs. */
    public void shutdownServices$artzig() {
        final ArrayList<IBinder> tokens = new ArrayList<>(mServices.keySet());
        mServicesData.clear();
        for (IBinder token : tokens) {
            Service s = mServices.remove(token);
            try {
                s.onDestroy();
                s.detachAndCleanUp();
            } catch (Throwable t) {
                Log.w(TAG, "service shutdown failed: " + s, t);
            }
        }
    }

    // ------------------------------------------------------------ ClientTransactionHandler

    /** AOSP ActivityThread.H message ids the transaction handler uses. */
    public static final class H {
        public static final int EXECUTE_TRANSACTION = 159;
        public static final int RELAUNCH_ACTIVITY = 160;
    }
    private final android.app.servertransaction.TransactionExecutor mTransactionExecutor =
            new android.app.servertransaction.TransactionExecutor(this);
    final ArrayMap<IBinder, ActivityClientRecord> mActivities = new ArrayMap<>();
    @Override android.app.servertransaction.TransactionExecutor getTransactionExecutor() { return mTransactionExecutor; }
    @Override void sendMessage(int what, Object obj) {
        if (what == H.EXECUTE_TRANSACTION) {
            final android.app.servertransaction.ClientTransaction transaction = (android.app.servertransaction.ClientTransaction) obj;
            mH.post(() -> mTransactionExecutor.execute(transaction));
            return;
        }
        throw new UnsupportedOperationException("ActivityThread message " + what + " is not hosted");
    }
    private static UnsupportedOperationException notHosted(String what) {
        return new UnsupportedOperationException("ActivityThread." + what + " is not hosted (activity lifecycle runs in HostedActivityRuntime)");
    }
    @Override public Activity getActivity(IBinder token) { ActivityClientRecord r = mActivities.get(token); return r != null ? r.activity : null; }
    @Override public ActivityClientRecord getActivityClient(IBinder token) { return mActivities.get(token); }
    @Override public void updatePendingConfiguration(Configuration config) {}
    @Override public void updateProcessState(int processState, boolean fromIpc) {}
    @Override public void countLaunchingActivities(int num) {}
    @Override public java.util.Map<IBinder, android.app.servertransaction.DestroyActivityItem> getActivitiesToBeDestroyed() { return new ArrayMap<>(); }
    @Override public void handleDestroyActivity(ActivityClientRecord r, boolean finishing, boolean getNonConfigInstance, String reason) { throw notHosted("handleDestroyActivity"); }
    @Override public void handlePauseActivity(ActivityClientRecord r, boolean finished, boolean userLeaving, boolean autoEnteringPip, android.app.servertransaction.PendingTransactionActions pendingActions, String reason) { throw notHosted("handlePauseActivity"); }
    @Override public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest, boolean isForward, boolean shouldSendCompatFakeFocus, String reason) { throw notHosted("handleResumeActivity"); }
    @Override public void handleTopResumedActivityChanged(ActivityClientRecord r, boolean isTopResumedActivity, String reason) { throw notHosted("handleTopResumedActivityChanged"); }
    @Override public void handleStopActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, boolean finalStateRequest, String reason) { throw notHosted("handleStopActivity"); }
    @Override public void reportStop(android.app.servertransaction.PendingTransactionActions pendingActions) { throw notHosted("reportStop"); }
    @Override public void performRestartActivity(ActivityClientRecord r, boolean start) { throw notHosted("performRestartActivity"); }
    @Override public void reportRefresh(ActivityClientRecord r) { throw notHosted("reportRefresh"); }
    @Override public void updatePendingActivityConfiguration(IBinder token, Configuration overrideConfig) { throw notHosted("updatePendingActivityConfiguration"); }
    @Override public void handleActivityConfigurationChanged(ActivityClientRecord r, Configuration overrideConfig, int displayId, android.window.ActivityWindowInfo activityWindowInfo) { throw notHosted("handleActivityConfigurationChanged"); }
    @Override public void handleWindowContextInfoChanged(IBinder clientToken, android.window.WindowContextInfo info) { throw notHosted("handleWindowContextInfoChanged"); }
    @Override public void handleWindowContextWindowRemoval(IBinder clientToken) { throw notHosted("handleWindowContextWindowRemoval"); }
    @Override public void handleSendResult(ActivityClientRecord r, List<ResultInfo> results, String reason) { throw notHosted("handleSendResult"); }
    @Override public void handleNewIntent(ActivityClientRecord r, List<com.android.internal.content.ReferrerIntent> intents) { throw notHosted("handleNewIntent"); }
    @Override public void handlePictureInPictureRequested(ActivityClientRecord r) { throw notHosted("handlePictureInPictureRequested"); }
    @Override public void handlePictureInPictureStateChanged(ActivityClientRecord r, PictureInPictureUiState pipState) { throw notHosted("handlePictureInPictureStateChanged"); }
    @Override public boolean isHandleSplashScreenExit(IBinder token) { return false; }
    @Override public void handleAttachSplashScreenView(ActivityClientRecord r, android.window.SplashScreenView.SplashScreenViewParcelable parcelable, android.view.SurfaceControl startingWindowLeash) { throw notHosted("handleAttachSplashScreenView"); }
    @Override public Activity handleLaunchActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, int deviceId, Intent customIntent) { throw notHosted("handleLaunchActivity"); }
    @Override public void handleStartActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, ActivityOptions.SceneTransitionInfo sceneTransitionInfo) { throw notHosted("handleStartActivity"); }
    /** A system-sent configuration (a ConfigurationChangeItem): the hosted system sends deltas in
     * Configuration.updateFrom form (only the changed fields defined), so merge into the process
     * configuration and apply it as a launcher-driven change (AOSP: ResourcesManager first, then
     * the activities and components). */
    @Override public void handleConfigurationChanged(Configuration config, int deviceId) {
        if (config == null) return;
        Configuration current = ResourcesManager.getInstance().getConfiguration();
        Configuration next = new Configuration(current);
        int changes = next.updateFrom(config);
        if (changes == 0) return;
        android.util.DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        ResourcesManager.getInstance().applyConfigurationToResources(next, null);
        applyConfigurationInProcess(next, changes, metrics.widthPixels, metrics.heightPixels);
    }
    @Override public ActivityClientRecord prepareRelaunchActivity(IBinder token, List<ResultInfo> pendingResults, List<com.android.internal.content.ReferrerIntent> pendingNewIntents, int configChanges, android.util.MergedConfiguration config, boolean preserveWindow, android.window.ActivityWindowInfo activityWindowInfo) { throw notHosted("prepareRelaunchActivity"); }
    @Override public void handleRelaunchActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions) { throw notHosted("handleRelaunchActivity"); }
    @Override public void reportRelaunch(ActivityClientRecord r) { throw notHosted("reportRelaunch"); }

    /** AOSP ActivityThread.ActivityClientRecord: the fields the servertransaction executor reads. */
    public static final class ActivityClientRecord {
        public IBinder token;
        public Activity activity;
        public Intent intent;
        public ActivityInfo activityInfo;
        public Bundle state;
        public boolean isForward;
        public boolean isTopResumedActivity;
        public LoadedApk packageInfo;
        private int mLifecycleState = android.app.servertransaction.ActivityLifecycleItem.PRE_ON_CREATE;
        public android.window.ActivityWindowInfo activityWindowInfo;
        public android.window.ActivityWindowInfo mActivityWindowInfo = new android.window.ActivityWindowInfo();
        public ActivityClientRecord() {}
        public ActivityClientRecord(IBinder token, Intent intent, int ident, ActivityInfo info, Configuration overrideConfig, String referrer, com.android.internal.app.IVoiceInteractor voiceInteractor, Bundle state, android.os.PersistableBundle persistentState, List<ResultInfo> pendingResults, List<com.android.internal.content.ReferrerIntent> pendingNewIntents, ActivityOptions.SceneTransitionInfo sceneTransitionInfo, boolean isForward, ProfilerInfo profilerInfo, ClientTransactionHandler client, IBinder assistToken, IBinder shareableActivityToken, boolean launchedFromBubble, IBinder taskFragmentToken, IBinder initialCallerInfoAccessToken, android.window.ActivityWindowInfo activityWindowInfo) {
            this.token = token; this.intent = intent; this.activityInfo = info; this.state = state; this.isForward = isForward;
            this.activityWindowInfo = activityWindowInfo;
            if (activityWindowInfo != null) this.mActivityWindowInfo = activityWindowInfo;
        }
        public int getLifecycleState() { return mLifecycleState; }
        public void setState(int newLifecycleState) { mLifecycleState = newLifecycleState; }
        public android.window.ActivityWindowInfo getActivityWindowInfo() { return mActivityWindowInfo; }
    }

    // ------------------------------------------------------------ hosted activity seam (HostedActivityRuntime)

    /** Host-only presentation seam; lifecycle policy stays in HostedActivityRuntime. */
    public interface HostAdapter {
        Context getActivityContext();
        ClassLoader getActivityClassLoader();
        void showActivity(Activity activity);
        void clearActivityWindows();
        void resizeActivityRoot(Activity activity, int width, int height);
        ComponentName resolveActivity(Intent intent);
    }
    public static void configureActivityHost(HostAdapter adapter, java.util.Map<String, ActivityInfo> activities) {
        HostedActivityRuntime.configure(adapter, activities);
    }
    /** AOSP ContextImpl.createActivityContext for an activity this process launches (fresh token,
     * default display): the per-activity base context of ActivityThread.performLaunchActivity. */
    public ContextImpl createActivityContext(ActivityInfo info) {
        // A component without an APK behind it (host demos, smokes) lives in the system package.
        LoadedApk packageInfo = info.applicationInfo != null && info.applicationInfo.sourceDir != null
                ? getPackageInfoNoCheck(info.applicationInfo, mCompatibilityInfo)
                : getSystemContext().mPackageInfo;
        return ContextImpl.createActivityContext(this, packageInfo, info, new android.os.Binder(),
                android.view.Display.DEFAULT_DISPLAY, null);
    }
    public static Activity launchMainActivity(String activityClassName) { return HostedActivityRuntime.launchMain(activityClassName); }
    public static Activity currentActivity() { return HostedActivityRuntime.currentActivity(); }
    /** AOSP ActivityThread.sCurrentBroadcastIntent: the Intent a receiver on this thread is handling. */
    private static final ThreadLocal<Intent> sCurrentBroadcastIntent = new ThreadLocal<>();
    public static Intent getIntentBeingBroadcast() { return sCurrentBroadcastIntent.get(); }
    /** Set by the receiver dispatcher around onReceive (LoadedApk.ReceiverDispatcher.Args.run). */
    public static void setIntentBeingBroadcast$artzig(Intent intent) { sCurrentBroadcastIntent.set(intent); }
    public static int getManifestOrientation(String activityClassName) { return HostedActivityRuntime.manifestOrientation(activityClassName); }
    public static boolean applyConfigurationInProcess(Configuration config, int changes, int width, int height) {
        return HostedActivityRuntime.applyConfiguration(config, changes, width, height);
    }
    public static void shutdownActivitiesInProcess() { HostedActivityRuntime.shutdown(); }
    public static void startActivityInProcess(Intent intent) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.startActivity(intent);
        else Log.w(TAG, "startActivity dropped (no launcher): " + intent);
    }
    public static void startActivityInProcess(Activity caller, String resultWho, Intent intent,
            int requestCode) {
        if (HostedActivityRuntime.isConfigured()) {
            HostedActivityRuntime.startActivity(caller, resultWho, intent, requestCode);
        } else {
            Log.w(TAG, "startActivity dropped (no launcher): " + intent);
        }
    }
    public static ComponentName resolveActivityInProcess(Intent intent) {
        return HostedActivityRuntime.isConfigured() ? HostedActivityRuntime.resolveActivity(intent) : null;
    }
    public static void finishActivityInProcess(Activity activity) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.finishActivity(activity);
    }
    public static void recreateActivityInProcess(Activity activity) {
        if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.recreateActivity(activity);
    }
    /** Registers this process with the activity manager (AOSP attachApplication). */
    public static void attachApplicationHosted(String packageName) { HostedServiceClient.attachApplication(packageName); }
}
