/* artzig shim: android.app.ActivityThread — the hosted process thread. The
 * process-facing surface the real Context/ContextImpl/LoadedApk/Application/
 * Service/ContentResolver use is the AOSP instance shape (package caches,
 * provider connections, service handlers, bind-application ordering, system
 * context), plus the AOSP ClientTransactionHandler contract so the real
 * IApplicationThread's scheduleTransaction drives the AOSP TransactionExecutor.
 * What is host-specific: top-level window presentation stops at HostAdapter,
 * while lifecycle is driven by the stock transaction protocol; a process is
 * bound to its package by the host entry points below instead of a zygote/AMS
 * handshake. HostedActivityRuntime remains only for legacy in-process module
 * smokes that deliberately do not install a system service session. */
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
        if (am != null && am.mBoundApplication != null) {
            return am.mBoundApplication.appInfo.packageName;
        }
        // Hosted JVM smokes bootstrap the platform resources before the broker assigns
        // their application uid. Until AMS delivers a local bind, report no op package so
        // stock clients use PackageManager.getPackagesForUid(), just as AOSP does before
        // bindApplication. Never authenticate an app-uid Binder call as the platform package.
        if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) return null;
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

    // ------------------------------------------------------------ local activities

    /** AOSP ActivityThread.resolveActivityInfo. LocalActivityManager is an in-process caller, but
     * resolution and ActivityNotFoundException behavior still belong to PackageManager. */
    public final ActivityInfo resolveActivityInfo(Intent intent) {
        final Context context = mInitialApplication != null ? mInitialApplication : getSystemContext();
        ActivityInfo info = intent.resolveActivityInfo(context.getPackageManager(),
                android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES);
        // Host-only components (including framework smokes) deliberately have no installed APK
        // metadata. Keep PackageManager authoritative, then consult the ActivityTaskManager-side
        // host registry before reporting START_CLASS_NOT_FOUND.
        if (info == null && HostedActivityRuntime.isConfigured()) {
            info = HostedActivityRuntime.resolveActivityInfo(intent);
        }
        if (info == null) {
            Instrumentation.checkStartActivityResult(ActivityManager.START_CLASS_NOT_FOUND, intent);
        }
        return info;
    }

    /** AOSP ActivityThread.startActivityNow. This is the local-token path used by the deprecated
     * LocalActivityManager; it intentionally creates no desktop top-level window of its own. */
    public final Activity startActivityNow(Activity parent, String id, Intent intent,
            ActivityInfo activityInfo, IBinder token, Bundle state,
            Activity.NonConfigurationInstances lastNonConfigurationInstances, IBinder assistToken,
            IBinder shareableActivityToken) {
        final ActivityClientRecord r = new ActivityClientRecord();
        r.token = token;
        r.intent = intent;
        r.state = state;
        r.parent = parent;
        r.embeddedID = id;
        r.activityInfo = activityInfo;
        r.lastNonConfigurationInstances = lastNonConfigurationInstances;
        r.assistToken = assistToken;
        r.shareableActivityToken = shareableActivityToken;
        return performLaunchActivity(r, null);
    }

    /** Hosted adaptation of AOSP ActivityThread.performLaunchActivity for a process-local token.
     * Package/class resolution, context attachment, theme application, record publication, and
     * onCreate ordering are retained; system_server reporting and WindowManagerService are absent. */
    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        Activity activity = null;
        final ActivityInfo info = r.activityInfo != null ? r.activityInfo : resolveActivityInfo(r.intent);
        r.activityInfo = info;
        try {
            r.packageInfo = info.applicationInfo != null && info.applicationInfo.sourceDir != null
                    ? getPackageInfoNoCheck(info.applicationInfo, mCompatibilityInfo)
                    : getSystemContext().mPackageInfo;
            final ContextImpl activityContext = createActivityContext(
                    info, r.packageInfo, r.token, r.overrideConfig);
            final ClassLoader hostedClassLoader = HostedActivityRuntime.activityClassLoaderOrNull();
            final ClassLoader classLoader = hostedClassLoader != null
                    ? hostedClassLoader : activityContext.getClassLoader();
            ComponentName component = r.intent.getComponent();
            if (component == null) {
                component = new ComponentName(info.packageName, info.name);
                r.intent.setComponent(component);
            }
            if (info.targetActivity != null) {
                component = new ComponentName(info.packageName, info.targetActivity);
            }
            activity = r.packageInfo.getAppFactory().instantiateActivity(
                    classLoader, component.getClassName(), r.intent);
            r.intent.setExtrasClassLoader(classLoader);
            if (r.state != null) r.state.setClassLoader(classLoader);

            final Intent launchIntent = customIntent != null ? customIntent : r.intent;
            activity.attach(activityContext, info, launchIntent, r.parent, r.embeddedID, r.token,
                    r.assistToken, r.shareableActivityToken);
            activity.mReferrer = r.referrer;
            activity.setLastNonConfigurationInstances$artzig(r.lastNonConfigurationInstances);
            r.lastNonConfigurationInstances = null;
            final int theme = info.getThemeResource();
            if (theme != 0) activity.setTheme(theme);

            // Publish before onCreate, matching AOSP callbacks that look their token up here.
            r.activity = activity;
            mActivities.put(r.token, r);
            activity.performCreate(r.state);
            r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_CREATE);
            return activity;
        } catch (Exception e) {
            mActivities.remove(r.token);
            if (!mInstrumentation.onException(activity, e)) {
                throw new RuntimeException("Unable to start local activity " + r.intent + ": "
                        + e.toString(), e);
            }
            return activity;
        }
    }

    /** AOSP ActivityThread.performResumeActivity, with window visibility owned by the embedding
     * parent rather than WindowManagerService. */
    public boolean performResumeActivity(ActivityClientRecord r, boolean finalStateRequest,
            String reason) {
        if (r == null || r.activity == null || r.activity.mFinished
                || r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            return false;
        }
        r.activity.onStateNotSaved();
        r.activity.mFragments.noteStateNotSaved();
        if (r.pendingIntents != null) {
            handleNewIntent(r, r.pendingIntents);
            r.pendingIntents = null;
        }
        if (r.pendingResults != null) {
            deliverResults(r, r.pendingResults, reason);
            r.pendingResults = null;
        }
        r.activity.performResume();
        r.state = null;
        r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_RESUME);
        return true;
    }

    /** AOSP hidden API retained for LocalActivityManager. */
    final Bundle performPauseActivity(IBinder token, boolean finished, String reason,
            android.app.servertransaction.PendingTransactionActions pendingActions) {
        final ActivityClientRecord r = mActivities.get(token);
        return r != null ? performPauseActivity(r, finished, reason, pendingActions) : null;
    }

    private Bundle performPauseActivity(ActivityClientRecord r, boolean finished, String reason,
            android.app.servertransaction.PendingTransactionActions pendingActions) {
        if (finished) r.activity.mFinished = true;
        // Pinned AOSP saves pre-Honeycomb activities before onPause. Synthetic host ActivityInfo
        // defaults targetSdkVersion to 0, so this path is also required by framework smokes.
        final boolean preHoneycomb = r.activityInfo != null
                && r.activityInfo.applicationInfo != null
                && r.activityInfo.applicationInfo.targetSdkVersion
                        < android.os.Build.VERSION_CODES.HONEYCOMB;
        final boolean shouldSaveState = !r.activity.mFinished && preHoneycomb;
        if (shouldSaveState) {
            r.state = new Bundle();
            r.activity.performSaveInstanceState(r.state);
        }
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            r.activity.performPause();
            r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE);
        }
        final Bundle oldState = pendingActions != null ? pendingActions.getOldState() : null;
        if (oldState != null && preHoneycomb) r.state = oldState;
        return shouldSaveState ? r.state : null;
    }

    /** AOSP hidden API retained for LocalActivityManager. */
    final void performStopActivity(IBinder token, boolean saveState, String reason) {
        performStopActivityInner(mActivities.get(token), saveState, reason);
    }

    private void performStopActivityInner(ActivityClientRecord r, boolean saveState, String reason) {
        if (r == null || r.activity == null
                || r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_STOP) {
            return;
        }
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            performPauseActivity(r, false, reason, null);
        }
        final int targetSdk = r.activityInfo != null && r.activityInfo.applicationInfo != null
                ? r.activityInfo.applicationInfo.targetSdkVersion : 0;
        final boolean saveBeforeStop = targetSdk >= android.os.Build.VERSION_CODES.HONEYCOMB
                && targetSdk < android.os.Build.VERSION_CODES.P;
        final boolean saveAfterStop = targetSdk >= android.os.Build.VERSION_CODES.P;
        if (saveState && saveBeforeStop && !r.activity.mFinished && r.state == null) {
            r.state = new Bundle();
            r.activity.performSaveInstanceState(r.state);
        }
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_START
                || r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE) {
            r.activity.performStop();
            r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_STOP);
        }
        if (saveState && saveAfterStop && !r.activity.mFinished && r.state == null) {
            r.state = new Bundle();
            r.activity.performSaveInstanceState(r.state);
        }
    }

    /** AOSP ActivityThread.performDestroyActivity for a process-local activity record. */
    void performDestroyActivity(ActivityClientRecord r, boolean finishing,
            boolean getNonConfigInstance, String reason) {
        if (r == null || r.activity == null) return;
        if (finishing) r.activity.mFinished = true;
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            performPauseActivity(r, finishing, reason, null);
        }
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_START
                || r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE) {
            performStopActivityInner(r, false, reason);
        }
        if (getNonConfigInstance) {
            r.lastNonConfigurationInstances = r.activity.retainNonConfigurationInstances();
        }
        r.activity.performDestroy();
        r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_DESTROY);
        mActivities.remove(r.token);
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
    private final ArrayMap<IBinder, android.app.servertransaction.DestroyActivityItem>
            mActivitiesToBeDestroyed = new ArrayMap<>();
    private final ArrayList<ActivityClientRecord> mRelaunchingActivities = new ArrayList<>();
    private Configuration mPendingConfiguration;
    /** Pinned AOSP ordering guard populated by configuration transaction preExecute. */
    private final ArrayMap<IBinder, Configuration> mPendingOverrideConfigs = new ArrayMap<>();
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
    @Override public void updatePendingConfiguration(Configuration config) {
        synchronized (mRelaunchingActivities) {
            if (mPendingConfiguration == null
                    || mPendingConfiguration.isOtherSeqNewer(config)) {
                mPendingConfiguration = new Configuration(config);
            }
        }
    }

    private Configuration consumePendingConfiguration(Configuration requested) {
        synchronized (mRelaunchingActivities) {
            if (mPendingConfiguration != null
                    && (requested == null || requested.isOtherSeqNewer(mPendingConfiguration))) {
                requested = mPendingConfiguration;
            }
            mPendingConfiguration = null;
            return requested;
        }
    }

    private int applyProcessConfiguration(Configuration requested,
            boolean dispatchHostedActivityPolicy) {
        if (requested == null) return 0;
        final Configuration current = ResourcesManager.getInstance().getConfiguration();
        if (!current.isOtherSeqNewer(requested) && current.diff(requested) != 0) {
            return 0;
        }
        final Configuration next = new Configuration(current);
        final int changes = next.updateFrom(requested);
        // Sequence-only updates still become the canonical process configuration, so a delayed
        // lower-sequence delta cannot subsequently be mistaken for a new request.
        ResourcesManager.getInstance().applyConfigurationToResources(next, null);
        if (changes == 0) return 0;
        if (dispatchHostedActivityPolicy) {
            final android.util.DisplayMetrics metrics =
                    android.content.res.Resources.getSystem().getDisplayMetrics();
            applyConfigurationInProcess(next, changes, metrics.widthPixels, metrics.heightPixels);
        } else if (mInitialApplication != null) {
            mInitialApplication.onConfigurationChanged(next);
        }
        return changes;
    }
    @Override public void updateProcessState(int processState, boolean fromIpc) {}
    @Override public void countLaunchingActivities(int num) {}
    @Override public java.util.Map<IBinder, android.app.servertransaction.DestroyActivityItem> getActivitiesToBeDestroyed() {
        return mActivitiesToBeDestroyed;
    }
    @Override public void handleDestroyActivity(ActivityClientRecord r, boolean finishing, boolean getNonConfigInstance, String reason) {
        final Activity destroyed = r != null ? r.activity : null;
        performDestroyActivity(r, finishing, getNonConfigInstance, reason);
        if (finishing && destroyed != null) {
            clearPresentedTransactionActivity(destroyed);
            // Pinned ActivityThread.performDestroyActivity reports only a final destroy. A local
            // relaunch keeps the server-side ActivityRecord and is acknowledged separately by
            // ActivityRelaunchItem.postExecute.
            ActivityClient.getInstance().activityDestroyed(r.token);
        }
    }
    @Override public void handlePauseActivity(ActivityClientRecord r, boolean finished, boolean userLeaving, boolean autoEnteringPip, android.app.servertransaction.PendingTransactionActions pendingActions, String reason) {
        performPauseActivity(r, finished, reason, pendingActions);
    }
    @Override public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest, boolean isForward, boolean shouldSendCompatFakeFocus, String reason) {
        if (performResumeActivity(r, finalStateRequest, reason)) {
            final HostAdapter adapter = sActivityHost;
            if (adapter != null) {
                // Pinned ActivityThread makes the activity visible only after onResume. The host
                // adapter owns the fenced ViewRoot/window presentation in place of WindowManager.
                adapter.showActivity(r.activity);
                sPresentedTransactionActivity = r.activity;
            }
        }
    }
    /** AOSP ActivityThread.handleTopResumedActivityChanged/reportTopResumedActivityChanged. */
    @Override public void handleTopResumedActivityChanged(ActivityClientRecord r,
            boolean isTopResumedActivity, String reason) {
        if (r == null || r.activity == null) return;
        if (r.isTopResumedActivity == isTopResumedActivity) {
            Log.w(TAG, "Activity top position already set to onTop=" + isTopResumedActivity);
            return;
        }
        r.isTopResumedActivity = isTopResumedActivity;
        if (r.getLifecycleState()
                == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME
                && r.lastReportedTopResumedState != isTopResumedActivity) {
            r.lastReportedTopResumedState = isTopResumedActivity;
            r.activity.performTopResumedActivityChanged$artzig(isTopResumedActivity, reason);
        }
    }
    @Override public void handleStopActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, boolean finalStateRequest, String reason) {
        // Pinned performStopActivityInner pauses before asking the Activity for its description,
        // then performs the target-SDK-dependent stop/save ordering.
        if (r != null && r.activity != null && r.getLifecycleState()
                == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            performPauseActivity(r, false, reason, pendingActions);
        }
        final android.app.servertransaction.PendingTransactionActions.StopInfo stopInfo =
                new android.app.servertransaction.PendingTransactionActions.StopInfo();
        if (r != null && r.activity != null) {
            try {
                stopInfo.setDescription(r.activity.onCreateDescription());
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException("Unable to create description for activity "
                            + r.intent + ": " + e.toString(), e);
                }
            }
        }
        performStopActivityInner(r, true, reason);
        if (r != null) {
            // A final StopActivityItem makes the client invisible before its asynchronous stop
            // acknowledgement. If another activity was presented in the meantime, identity keeps
            // that newer activity intact.
            clearPresentedTransactionActivity(r.activity);
            stopInfo.setActivity(r);
            stopInfo.setState(r.state);
            stopInfo.setPersistentState(r.persistentState);
            pendingActions.setStopInfo(stopInfo);
        }
    }
    @Override public void reportStop(android.app.servertransaction.PendingTransactionActions pendingActions) {
        final android.app.servertransaction.PendingTransactionActions.StopInfo stopInfo =
                pendingActions.getStopInfo();
        if (stopInfo != null) mH.post(stopInfo);
    }
    /** AOSP ActivityThread.performRestartActivity: restart a stopped local activity, optionally
     * carrying it through start. Window visibility remains owned by the embedding parent. */
    @Override public void performRestartActivity(ActivityClientRecord r, boolean start) {
        if (r == null || r.activity == null || r.activity.mFinished) return;
        if (r.getLifecycleState() != android.app.servertransaction.ActivityLifecycleItem.ON_STOP) return;
        r.activity.performRestart(start);
        // AOSP leaves the record stopped when start=false; LocalActivityManager uses start=true.
        if (start) r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_START);
    }
    /** AOSP ActivityThread.reportRefresh: acknowledge the refresh transaction to system_server. */
    @Override public void reportRefresh(ActivityClientRecord r) {
        if (r != null) ActivityClient.getInstance().activityRefreshed(r.token);
    }
    @Override public void updatePendingActivityConfiguration(IBinder token,
            Configuration overrideConfig) {
        synchronized (mPendingOverrideConfigs) {
            final Configuration pendingOverrideConfig = mPendingOverrideConfigs.get(token);
            if (pendingOverrideConfig != null
                    && !pendingOverrideConfig.isOtherSeqNewer(overrideConfig)) {
                return;
            }
            mPendingOverrideConfigs.put(token, overrideConfig);
        }
    }
    /** Pinned AOSP ActivityThread.handleActivityConfigurationChanged, bounded at the hosted
     * ResourcesManager/Activity boundary. The hosted ViewRootImpl deliberately has no per-root
     * configuration callbacks; presentation resizing remains owned by HostAdapter. The server-side
     * relaunch decision has already selected this path, so a public delta is always reported. */
    @Override public void handleActivityConfigurationChanged(ActivityClientRecord r,
            Configuration overrideConfig, int displayId,
            android.window.ActivityWindowInfo activityWindowInfo) {
        final android.app.servertransaction.ClientTransactionListenerController controller =
                android.app.servertransaction.ClientTransactionListenerController.getInstance();
        controller.onContextConfigurationPreChanged(r.activity);
        try {
            handleActivityConfigurationChangedInner(
                    r, overrideConfig, displayId, activityWindowInfo);
        } finally {
            controller.onContextConfigurationPostChanged(r.activity);
        }
    }

    private void handleActivityConfigurationChangedInner(ActivityClientRecord r,
            Configuration overrideConfig, int displayId,
            android.window.ActivityWindowInfo activityWindowInfo) {
        synchronized (mPendingOverrideConfigs) {
            final Configuration pendingOverrideConfig = mPendingOverrideConfigs.get(r.token);
            if (overrideConfig.isOtherSeqNewer(pendingOverrideConfig)) {
                return;
            }
            mPendingOverrideConfigs.remove(r.token);
        }
        if (displayId == android.view.Display.INVALID_DISPLAY) {
            displayId = r.activity.getDisplayId();
        }
        if (displayId != android.view.Display.DEFAULT_DISPLAY) {
            throw new UnsupportedOperationException(
                    "Hosted activities cannot move to unavailable display " + displayId);
        }
        final boolean movedToDifferentDisplay = r.activity.getDisplayId() != displayId;
        if (r.overrideConfig != null && !r.overrideConfig.isOtherSeqNewer(overrideConfig)
                && !movedToDifferentDisplay) {
            return;
        }

        r.overrideConfig = overrideConfig;
        r.mActivityWindowInfo.set(activityWindowInfo);
        r.activityWindowInfo = r.mActivityWindowInfo;

        r.tmpConfig.setTo(ResourcesManager.getInstance().getConfiguration());
        r.tmpConfig.updateFrom(overrideConfig);
        final Configuration newConfig = new Configuration(r.tmpConfig);
        final boolean shouldReportChange = r.activity.mCurrentConfig.diffPublicOnly(newConfig) != 0;

        // AOSP requires token-scoped Resources to observe the new values before either callback.
        ResourcesManager.getInstance().updateResourcesForActivity(
                r.token, overrideConfig, displayId);

        if (movedToDifferentDisplay) {
            r.activity.dispatchMovedToDisplay(displayId, newConfig);
        }
        r.activity.mConfigChangeFlags = 0;
        if (shouldReportChange) {
            r.activity.mCalled = false;
            r.activity.mCurrentConfig = new Configuration(newConfig);
            r.activity.onConfigurationChanged(newConfig);
            if (!r.activity.mCalled) {
                throw new android.util.SuperNotCalledException("Activity "
                        + r.activity.getLocalClassName()
                        + " did not call through to super.onConfigurationChanged()");
            }
        }
        handleActivityWindowInfoChanged(r);
    }

    private void handleActivityWindowInfoChanged(ActivityClientRecord r) {
        if (r.mActivityWindowInfo.equals(r.mLastReportedActivityWindowInfo)) return;
        r.mLastReportedActivityWindowInfo.set(r.mActivityWindowInfo);
        android.app.servertransaction.ClientTransactionListenerController.getInstance()
                .onActivityWindowInfoChanged(r.token, r.mActivityWindowInfo);
    }
    @Override public void handleWindowContextInfoChanged(IBinder clientToken, android.window.WindowContextInfo info) { throw notHosted("handleWindowContextInfoChanged"); }
    @Override public void handleWindowContextWindowRemoval(IBinder clientToken) { throw notHosted("handleWindowContextWindowRemoval"); }
    /** Pinned AOSP ActivityThread.deliverResults, retaining process-entry preparation and the
     * virtual Activity dispatch that ActivityGroup uses for embedded child ids. */
    private void deliverResults(ActivityClientRecord r, List<ResultInfo> results, String reason) {
        for (ResultInfo result : results) {
            try {
                if (result.mData != null) {
                    result.mData.setExtrasClassLoader(r.activity.getClassLoader());
                    result.mData.prepareToEnterProcess(isProtectedComponent(r.activityInfo),
                            r.activity.getAttributionSource());
                }
                if (android.security.Flags.contentUriPermissionApis()) {
                    ComponentCaller caller = new ComponentCaller(r.token, result.mCallerToken);
                    r.activity.dispatchActivityResult(result.mResultWho, result.mRequestCode,
                            result.mResultCode, result.mData, caller, reason);
                } else {
                    r.activity.dispatchActivityResult(result.mResultWho, result.mRequestCode,
                            result.mResultCode, result.mData, reason);
                }
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException("Failure delivering result " + result
                            + " to activity " + r.intent + ": " + e, e);
                }
            }
        }
    }

    /** Pinned AOSP ActivityThread.handleSendResult. A resumed activity receives a temporary
     * pause/result/resume callback sequence without changing its transaction lifecycle state. */
    @Override public void handleSendResult(ActivityClientRecord r, List<ResultInfo> results,
            String reason) {
        final boolean resumed = r.getLifecycleState()
                == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME;
        if (resumed) {
            try {
                r.activity.mCalled = false;
                mInstrumentation.callActivityOnPause(r.activity);
                if (!r.activity.mCalled) {
                    throw new android.util.SuperNotCalledException(
                            "Activity " + r.intent.getComponent().toShortString()
                            + " did not call through to super.onPause()");
                }
            } catch (android.util.SuperNotCalledException e) {
                throw e;
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException("Unable to pause activity "
                            + r.intent.getComponent().toShortString() + ": " + e, e);
                }
            }
        }
        deliverResults(r, results, reason);
        if (resumed) r.activity.performResume();
    }
    /** AOSP ActivityThread.handleNewIntent/deliverNewIntents. The caller owns lifecycle moves. */
    @Override public void handleNewIntent(ActivityClientRecord r, List<com.android.internal.content.ReferrerIntent> intents) {
        if (r == null || r.activity == null || intents == null || intents.isEmpty()) return;
        for (com.android.internal.content.ReferrerIntent intent : intents) {
            intent.setExtrasClassLoader(r.activity.getClassLoader());
            intent.prepareToEnterProcess(isProtectedComponent(r.activityInfo),
                    r.activity.getAttributionSource());
            r.activity.mFragments.noteStateNotSaved();
            if (android.security.Flags.contentUriPermissionApis()) {
                final ComponentCaller caller = new ComponentCaller(r.token, intent.mCallerToken);
                mInstrumentation.callActivityOnNewIntent(r.activity, intent, caller);
            } else {
                mInstrumentation.callActivityOnNewIntent(r.activity, intent);
            }
        }
    }
    @Override public void handlePictureInPictureRequested(ActivityClientRecord r) { throw notHosted("handlePictureInPictureRequested"); }
    @Override public void handlePictureInPictureStateChanged(ActivityClientRecord r, PictureInPictureUiState pipState) { throw notHosted("handlePictureInPictureStateChanged"); }
    @Override public boolean isHandleSplashScreenExit(IBinder token) { return false; }
    @Override public void handleAttachSplashScreenView(ActivityClientRecord r, android.window.SplashScreenView.SplashScreenViewParcelable parcelable, android.view.SurfaceControl startingWindowLeash) { throw notHosted("handleAttachSplashScreenView"); }
    @Override public Activity handleLaunchActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, int deviceId, Intent customIntent) {
        applyProcessConfiguration(consumePendingConfiguration(null), false);
        final Activity activity = performLaunchActivity(r, customIntent);
        if (activity != null) {
            r.createdConfig = new Configuration(
                    ResourcesManager.getInstance().getConfiguration());
            if (!activity.mFinished && pendingActions != null) {
                pendingActions.setOldState(r.state);
                pendingActions.setRestoreInstanceState(true);
                pendingActions.setCallOnPostCreate(true);
            }
            handleActivityWindowInfoChanged(r);
        }
        return activity;
    }
    /** AOSP ActivityThread.handleStartActivity, with presentation owned by the local parent. */
    @Override public void handleStartActivity(ActivityClientRecord r, android.app.servertransaction.PendingTransactionActions pendingActions, ActivityOptions.SceneTransitionInfo sceneTransitionInfo) {
        if (r == null || r.activity == null || r.activity.mFinished) return;
        if (r.getLifecycleState() == android.app.servertransaction.ActivityLifecycleItem.ON_CREATE) {
            r.activity.performStart();
            r.setState(android.app.servertransaction.ActivityLifecycleItem.ON_START);
        }
        if (pendingActions != null && pendingActions.shouldRestoreInstanceState() && r.state != null) {
            r.activity.performRestoreInstanceState(r.state);
        }
        if (pendingActions != null && pendingActions.shouldCallOnPostCreate()) {
            r.activity.performPostCreate(r.state);
        }
    }
    /** A system-sent configuration (a ConfigurationChangeItem): the hosted system sends deltas in
     * Configuration.updateFrom form (only the changed fields defined), so merge into the process
     * configuration and apply it as a launcher-driven change (AOSP: ResourcesManager first, then
     * the activities and components). */
    @Override public void handleConfigurationChanged(Configuration config, int deviceId) {
        if (config == null) return;
        applyProcessConfiguration(consumePendingConfiguration(config), true);
    }
    /** Pinned AOSP relaunch coalescing. Window preservation is deliberately forced off at the
     * hosted WindowManager fence; a relaunched Activity always receives a fresh PhoneWindow. */
    @Override public ActivityClientRecord prepareRelaunchActivity(IBinder token,
            List<ResultInfo> pendingResults,
            List<com.android.internal.content.ReferrerIntent> pendingNewIntents,
            int configChanges, android.util.MergedConfiguration config, boolean preserveWindow,
            android.window.ActivityWindowInfo activityWindowInfo) {
        ActivityClientRecord target = null;
        boolean scheduleRelaunch = false;
        synchronized (mRelaunchingActivities) {
            for (int i = 0; i < mRelaunchingActivities.size(); i++) {
                final ActivityClientRecord candidate = mRelaunchingActivities.get(i);
                if (candidate.token == token) {
                    target = candidate;
                    if (pendingResults != null) {
                        if (target.pendingResults != null) {
                            target.pendingResults.addAll(pendingResults);
                        } else {
                            target.pendingResults = pendingResults;
                        }
                    }
                    if (pendingNewIntents != null) {
                        if (target.pendingIntents != null) {
                            target.pendingIntents.addAll(pendingNewIntents);
                        } else {
                            target.pendingIntents = pendingNewIntents;
                        }
                    }
                    break;
                }
            }
            if (target == null) {
                target = new ActivityClientRecord();
                target.token = token;
                target.pendingResults = pendingResults;
                target.pendingIntents = pendingNewIntents;
                // preserveWindow requires WindowManagerGlobal/ViewRoot reparenting which is not
                // part of the hosted boundary.
                target.mPreserveWindow = false;
                mRelaunchingActivities.add(target);
                scheduleRelaunch = true;
            }
            target.createdConfig = config.getGlobalConfiguration();
            target.overrideConfig = config.getOverrideConfiguration();
            target.pendingConfigChanges |= configChanges;
            target.mActivityWindowInfo.set(activityWindowInfo);
        }
        return scheduleRelaunch ? target : null;
    }

    @Override public void handleRelaunchActivity(ActivityClientRecord tmp,
            android.app.servertransaction.PendingTransactionActions pendingActions) {
        int configChanges = 0;
        synchronized (mRelaunchingActivities) {
            final IBinder token = tmp.token;
            tmp = null;
            for (int i = 0; i < mRelaunchingActivities.size(); i++) {
                final ActivityClientRecord candidate = mRelaunchingActivities.get(i);
                if (candidate.token == token) {
                    tmp = candidate;
                    configChanges |= candidate.pendingConfigChanges;
                    mRelaunchingActivities.remove(i--);
                }
            }
        }
        if (tmp == null) return;

        final Configuration current = ResourcesManager.getInstance().getConfiguration();
        Configuration newest = null;
        final Configuration pending = consumePendingConfiguration(null);
        if (pending != null && current.isOtherSeqNewer(pending)) {
            newest = pending;
        }
        if (tmp.createdConfig != null
                && (newest != null ? newest : current).isOtherSeqNewer(tmp.createdConfig)
                && (newest != null ? newest : current).diff(tmp.createdConfig) != 0) {
            newest = tmp.createdConfig;
        }
        if (newest != null) {
            // Do not enter HostedActivityRuntime's top-level relaunch policy from this separate
            // transaction-owned Activity stack.
            applyProcessConfiguration(newest, false);
        }

        final ActivityClientRecord r = mActivities.get(tmp.token);
        if (r == null || r.activity == null) return;

        r.activity.mConfigChangeFlags |= configChanges;
        r.activity.setChangingConfigurations$artzig(true);
        final Intent customIntent = r.activity.getIntent();
        if (r.getLifecycleState()
                == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            performPauseActivity(r, false, "handleRelaunchActivity", null);
        }
        performStopActivityInner(r, true, "handleRelaunchActivity");
        performDestroyActivity(r, false, true, "handleRelaunchActivity");

        r.activity = null;
        if (tmp.pendingResults != null) {
            if (r.pendingResults == null) r.pendingResults = tmp.pendingResults;
            else r.pendingResults.addAll(tmp.pendingResults);
        }
        if (tmp.pendingIntents != null) {
            if (r.pendingIntents == null) r.pendingIntents = tmp.pendingIntents;
            else r.pendingIntents.addAll(tmp.pendingIntents);
        }
        r.overrideConfig = tmp.overrideConfig;
        r.mActivityWindowInfo.set(tmp.mActivityWindowInfo);
        r.activityWindowInfo = r.mActivityWindowInfo;
        r.mPreserveWindow = false;
        handleLaunchActivity(r, pendingActions, Context.DEVICE_ID_DEFAULT, customIntent);
    }

    @Override public void reportRelaunch(ActivityClientRecord r) {
        ActivityClient.getInstance().activityRelaunched(r.token);
    }

    /** AOSP ActivityThread.ActivityClientRecord: the fields the servertransaction executor reads. */
    public static final class ActivityClientRecord {
        public IBinder token;
        public Activity activity;
        public Intent intent;
        public ActivityInfo activityInfo;
        public Bundle state;
        public android.os.PersistableBundle persistentState;
        public boolean isForward;
        public boolean isTopResumedActivity;
        boolean lastReportedTopResumedState;
        public LoadedApk packageInfo;
        public Activity parent;
        public String embeddedID;
        String referrer;
        public Activity.NonConfigurationInstances lastNonConfigurationInstances;
        public IBinder assistToken;
        public IBinder shareableActivityToken;
        public Configuration overrideConfig;
        Configuration createdConfig;
        List<ResultInfo> pendingResults;
        List<com.android.internal.content.ReferrerIntent> pendingIntents;
        int pendingConfigChanges;
        boolean mPreserveWindow;
        final Configuration tmpConfig = new Configuration();
        private int mLifecycleState = android.app.servertransaction.ActivityLifecycleItem.PRE_ON_CREATE;
        public android.window.ActivityWindowInfo activityWindowInfo;
        public final android.window.ActivityWindowInfo mActivityWindowInfo =
                new android.window.ActivityWindowInfo();
        final android.window.ActivityWindowInfo mLastReportedActivityWindowInfo =
                new android.window.ActivityWindowInfo();
        public ActivityClientRecord() {}
        public ActivityClientRecord(IBinder token, Intent intent, int ident, ActivityInfo info, Configuration overrideConfig, String referrer, com.android.internal.app.IVoiceInteractor voiceInteractor, Bundle state, android.os.PersistableBundle persistentState, List<ResultInfo> pendingResults, List<com.android.internal.content.ReferrerIntent> pendingNewIntents, ActivityOptions.SceneTransitionInfo sceneTransitionInfo, boolean isForward, ProfilerInfo profilerInfo, ClientTransactionHandler client, IBinder assistToken, IBinder shareableActivityToken, boolean launchedFromBubble, IBinder taskFragmentToken, IBinder initialCallerInfoAccessToken, android.window.ActivityWindowInfo activityWindowInfo) {
            this.token = token; this.intent = intent; this.activityInfo = info; this.state = state;
            this.persistentState = persistentState; this.isForward = isForward;
            this.referrer = referrer;
            this.pendingResults = pendingResults;
            this.pendingIntents = pendingNewIntents;
            this.assistToken = assistToken;
            this.shareableActivityToken = shareableActivityToken;
            this.overrideConfig = overrideConfig;
            this.activityWindowInfo = activityWindowInfo;
            if (activityWindowInfo != null) this.mActivityWindowInfo.set(activityWindowInfo);
        }
        public int getLifecycleState() { return mLifecycleState; }
        public void setState(int newLifecycleState) { mLifecycleState = newLifecycleState; }
        public android.window.ActivityWindowInfo getActivityWindowInfo() { return mActivityWindowInfo; }
    }

    // ------------------------------------------------------------ hosted activity seam (HostedActivityRuntime)

    /** Host-only presentation seam; lifecycle policy stays in HostedActivityRuntime. */
    private static volatile HostAdapter sActivityHost;
    private static Activity sPresentedTransactionActivity;
    private static void clearPresentedTransactionActivity(Activity activity) {
        if (activity == null || activity != sPresentedTransactionActivity) return;
        final HostAdapter adapter = sActivityHost;
        if (adapter != null) adapter.clearActivityWindows();
        sPresentedTransactionActivity = null;
    }
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
        sActivityHost = adapter;
        sPresentedTransactionActivity = null;
    }
    /** AOSP ContextImpl.createActivityContext for an activity this process launches (fresh token,
     * default display): the per-activity base context of ActivityThread.performLaunchActivity. */
    public ContextImpl createActivityContext(ActivityInfo info) {
        // A component without an APK behind it (host demos, smokes) lives in the system package.
        LoadedApk packageInfo = info.applicationInfo != null && info.applicationInfo.sourceDir != null
                ? getPackageInfoNoCheck(info.applicationInfo, mCompatibilityInfo)
                : getSystemContext().mPackageInfo;
        return createActivityContext(info, packageInfo, new android.os.Binder(), null);
    }
    private ContextImpl createActivityContext(ActivityInfo info, LoadedApk packageInfo,
            IBinder token, Configuration overrideConfig) {
        return ContextImpl.createActivityContext(this, packageInfo, info, token,
                android.view.Display.DEFAULT_DISPLAY, overrideConfig);
    }
    public static Activity launchMainActivity(String activityClassName) { return HostedActivityRuntime.launchMain(activityClassName); }
    /** Host launcher entry point. The launch crosses the same IActivityTaskManager transport as an
     * Android launcher, then pumps this main looper until the Launch+Resume transaction presents
     * the activity. HostedActivityRuntime remains only for isolated legacy framework smokes. */
    public static Activity launchMainActivityThroughManager(String activityClassName) {
        final HostAdapter adapter = sActivityHost;
        if (adapter == null) throw new IllegalStateException("activity host is not configured");
        final Context context = adapter.getActivityContext();
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context.getPackageName(), activityClassName));
        final int result = startActivityThroughManager(null, null, intent, -1);
        Instrumentation.checkStartActivityResult(result, intent);

        final long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        final android.os.MessageQueue queue = Looper.myQueue();
        while (sPresentedTransactionActivity == null
                && android.os.SystemClock.uptimeMillis() < deadline) {
            final android.os.Message message = queue.pollDueMessage$artzig();
            if (message != null) {
                message.getTarget().dispatchMessage(message);
            } else {
                android.os.SystemClock.sleep(1);
            }
        }
        if (sPresentedTransactionActivity == null) {
            throw new IllegalStateException("activity launch transaction timed out: " + intent);
        }
        return sPresentedTransactionActivity;
    }
    public static Activity currentActivity() {
        final Activity transactionActivity = sPresentedTransactionActivity;
        return transactionActivity != null ? transactionActivity : HostedActivityRuntime.currentActivity();
    }
    /** AOSP ActivityThread.sCurrentBroadcastIntent: the Intent a receiver on this thread is handling. */
    private static final ThreadLocal<Intent> sCurrentBroadcastIntent = new ThreadLocal<>();
    public static Intent getIntentBeingBroadcast() { return sCurrentBroadcastIntent.get(); }
    /** Set by the receiver dispatcher around onReceive (LoadedApk.ReceiverDispatcher.Args.run). */
    public static void setIntentBeingBroadcast$artzig(Intent intent) { sCurrentBroadcastIntent.set(intent); }
    public static int getManifestOrientation(String activityClassName) { return HostedActivityRuntime.manifestOrientation(activityClassName); }
    public static boolean applyConfigurationInProcess(Configuration config, int changes, int width, int height) {
        final ActivityThread thread = currentActivityThread();
        final Activity presented = sPresentedTransactionActivity;
        final ActivityClientRecord record = presented != null
                ? thread.mActivities.get(presented.getActivityToken()) : null;
        if (record != null && record.activity == presented) {
            return thread.applyConfigurationToTransactionActivity(
                    record, config, changes, width, height);
        }
        return HostedActivityRuntime.applyConfiguration(config, changes, width, height);
    }

    /** Host-compositor counterpart of AOSP ActivityTaskManager/ActivityThread configuration
     * dispatch for an activity launched through ClientTransaction. Resources have already been
     * updated by ApkHost; this preserves the remaining Application callback, handled-change
     * callback, or save/stop/destroy/create/start/restore/post-create/resume ordering. */
    private boolean applyConfigurationToTransactionActivity(ActivityClientRecord r,
            Configuration config, int changes, int width, int height) {
        if (changes != 0 && mInitialApplication != null) {
            mInitialApplication.onConfigurationChanged(config);
        }
        if (r == null || r.activity == null) return false;

        final int handledChanges = r.activityInfo != null
                ? r.activityInfo.getRealConfigChanged()
                : r.activity.getActivityInfo().getRealConfigChanged();
        if ((changes & ~handledChanges) == 0) {
            final HostAdapter adapter = sActivityHost;
            if (adapter != null) adapter.resizeActivityRoot(r.activity, width, height);
            if (changes != 0) {
                r.activity.mConfigChangeFlags = 0;
                r.activity.mCalled = false;
                r.activity.mCurrentConfig = new Configuration(config);
                r.activity.onConfigurationChanged(config);
                if (!r.activity.mCalled) {
                    throw new android.util.SuperNotCalledException("Activity "
                            + r.activity.getLocalClassName()
                            + " did not call through to super.onConfigurationChanged()");
                }
            }
            return false;
        }

        final Activity destroyed = r.activity;
        final int requestedOrientation = destroyed.getRequestedOrientation();
        r.activity.mConfigChangeFlags |= changes;
        r.activity.setChangingConfigurations$artzig(true);
        final Intent customIntent = r.activity.getIntent();
        if (r.getLifecycleState()
                == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME) {
            performPauseActivity(r, false, "host configuration change", null);
        }
        performStopActivityInner(r, true, "host configuration change");
        performDestroyActivity(r, false, true, "host configuration change");
        // WindowManager removes every window owned by the old activity during a relaunch. The
        // host adapter owns that boundary; clearing it prevents dialogs from the destroyed
        // instance remaining stacked underneath the replacement activity.
        clearPresentedTransactionActivity(destroyed);

        r.activity = null;
        r.overrideConfig = new Configuration();
        final android.app.servertransaction.PendingTransactionActions pendingActions =
                new android.app.servertransaction.PendingTransactionActions();
        final Activity replacement = handleLaunchActivity(
                r, pendingActions, Context.DEVICE_ID_DEFAULT, customIntent);
        if (replacement == null) {
            throw new IllegalStateException("configuration relaunch failed for " + customIntent);
        }
        // ActivityRecord owns this value on Android; retain it locally because the compositor
        // initiated this relaunch without a second server-side ActivityRecord transaction.
        replacement.setRequestedOrientation(requestedOrientation);
        handleStartActivity(r, pendingActions, null);
        handleResumeActivity(r, true, false, false, "host configuration change");
        return true;
    }
    public static void shutdownActivitiesInProcess() {
        HostedActivityRuntime.shutdown();
        sPresentedTransactionActivity = null;
        sActivityHost = null;
    }
    public static void startActivityInProcess(Intent intent) {
        if (sPresentedTransactionActivity != null) {
            final int result = startActivityThroughManager(
                    sPresentedTransactionActivity, null, intent, -1);
            Instrumentation.checkStartActivityResult(result, intent);
        } else if (HostedActivityRuntime.isConfigured()) HostedActivityRuntime.startActivity(intent);
        else Log.w(TAG, "startActivity dropped (no launcher): " + intent);
    }
    public static void startActivityInProcess(Activity caller, String resultWho, Intent intent,
            int requestCode) {
        final ActivityThread thread = currentActivityThread();
        final IBinder callerToken = caller != null ? caller.getActivityToken() : null;
        final ActivityClientRecord callerRecord = callerToken != null
                ? thread.mActivities.get(callerToken) : null;
        if (callerRecord != null && callerRecord.activity == caller) {
            final int result = startActivityThroughManager(caller, resultWho, intent, requestCode);
            Instrumentation.checkStartActivityResult(result, intent);
        } else if (HostedActivityRuntime.isConfigured()) {
            HostedActivityRuntime.startActivity(caller, resultWho, intent, requestCode);
        } else {
            Log.w(TAG, "startActivity dropped (no launcher): " + intent);
        }
    }
    private static int startActivityThroughManager(Activity caller, String resultWho,
            Intent intent, int requestCode) {
        final ActivityThread thread = currentActivityThread();
        final HostAdapter adapter = sActivityHost;
        final Context context = caller != null ? caller
                : adapter != null ? adapter.getActivityContext() : thread.getSystemContext();
        try {
            intent.migrateExtraStreamToClipData(context);
            intent.prepareToLeaveProcess(context);
            return ActivityTaskManager.getService().startActivity(
                    thread.getApplicationThread(), context.getOpPackageName(),
                    context.getAttributionTag(), intent,
                    intent.resolveTypeIfNeeded(context.getContentResolver()),
                    caller != null ? caller.getActivityToken() : null,
                    resultWho, requestCode, 0, null, null);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from activity task manager", e);
        }
    }
    public static int startActivitiesInProcess(Activity caller, Intent[] intents,
            Bundle options, int userId) {
        if (intents == null || intents.length == 0) {
            return ActivityManager.START_INTENT_NOT_RESOLVED;
        }
        final ActivityThread thread = currentActivityThread();
        final IBinder callerToken = caller != null ? caller.getActivityToken() : null;
        final ActivityClientRecord callerRecord = callerToken != null
                ? thread.mActivities.get(callerToken) : null;
        if (callerRecord == null || callerRecord.activity != caller) {
            for (Intent intent : intents) startActivityInProcess(caller, null, intent, -1);
            return ActivityManager.START_SUCCESS;
        }
        final String[] resolvedTypes = new String[intents.length];
        try {
            for (int i = 0; i < intents.length; i++) {
                intents[i].migrateExtraStreamToClipData(caller);
                intents[i].prepareToLeaveProcess(caller);
                resolvedTypes[i] = intents[i].resolveTypeIfNeeded(caller.getContentResolver());
            }
            return ActivityTaskManager.getService().startActivities(
                    thread.getApplicationThread(), caller.getOpPackageName(),
                    caller.getAttributionTag(), intents, resolvedTypes, callerToken, options,
                    userId);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from activity task manager", e);
        }
    }
    public static ComponentName resolveActivityInProcess(Intent intent) {
        return HostedActivityRuntime.isConfigured() ? HostedActivityRuntime.resolveActivity(intent) : null;
    }
    public static void finishActivityInProcess(Activity activity) {
        final ActivityThread thread = currentActivityThread();
        final ActivityClientRecord record = thread.mActivities.get(activity.getActivityToken());
        if (record != null && record.activity == activity) {
            ActivityClient.getInstance().finishActivity(activity.getActivityToken(),
                    activity.getResultCode$artzig(), activity.getResultData$artzig(), 0);
        } else if (HostedActivityRuntime.isConfigured()) {
            HostedActivityRuntime.finishActivity(activity);
        }
    }
    public static void recreateActivityInProcess(Activity activity) {
        final ActivityThread thread = currentActivityThread();
        final ActivityClientRecord r = activity != null
                ? thread.mActivities.get(activity.getActivityToken()) : null;
        if (r != null && r.activity == activity) {
            // AOSP Activity.recreate -> ActivityThread.scheduleRelaunchActivity. The Android
            // implementation posts RELAUNCH_ACTIVITY through H; this hosted thread performs the
            // same pause/save/stop/destroy/create/start/resume sequence synchronously because its
            // bounded Handler does not carry the private ActivityThread message protocol.
            final Configuration global = new Configuration(
                    ResourcesManager.getInstance().getConfiguration());
            final android.util.MergedConfiguration merged =
                    new android.util.MergedConfiguration(global,
                            r.overrideConfig != null ? r.overrideConfig : new Configuration());
            final android.window.ActivityWindowInfo windowInfo =
                    new android.window.ActivityWindowInfo(r.getActivityWindowInfo());
            final ActivityClientRecord relaunch = thread.prepareRelaunchActivity(
                    r.token, null, null, 0, merged, false, windowInfo);
            if (relaunch != null) {
                final android.app.servertransaction.PendingTransactionActions pendingActions =
                        new android.app.servertransaction.PendingTransactionActions();
                thread.handleRelaunchActivity(relaunch, pendingActions);
                final ActivityClientRecord replacement = thread.mActivities.get(r.token);
                thread.handleStartActivity(replacement, pendingActions, null);
                thread.handleResumeActivity(replacement, true, false, false,
                        "Activity.recreate");
                thread.reportRelaunch(replacement);
            }
        } else if (HostedActivityRuntime.isConfigured()) {
            HostedActivityRuntime.recreateActivity(activity);
        }
    }
    /** Registers this process with the activity manager (AOSP attachApplication). */
    public static void attachApplicationHosted(String packageName) { HostedServiceClient.attachApplication(packageName); }
}
