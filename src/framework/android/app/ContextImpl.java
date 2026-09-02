/* artzig shim: android.app.ContextImpl — concrete Context over the real Resources
 * stack (framework-res.apk [+ app apk later]). Hand-written platform glue (ATL pattern). */
package android.app;
import android.content.Context;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.HostedResources;
import android.content.res.Resources;
import android.util.DisplayMetrics;
public class ContextImpl extends Context {
    private final Resources mResources;
    private final Resources.Theme mTheme;
    private final String mPackageName;
    private boolean mIsConfigurationBasedContext;
    // ApplicationPackageManager reaches these (AOSP field names): the hosted
    // process's single ActivityThread and its one loaded APK.
    final ActivityThread mMainThread = ActivityThread.currentActivityThread();
    /** Null until a context is built from a LoadedApk (createAppContext). */
    final LoadedApk mPackageInfo;
    public ContextImpl(Resources resources, String packageName, int themeResId) {
        this(resources, packageName, themeResId, null);
    }
    ContextImpl(Resources resources, String packageName, int themeResId, LoadedApk packageInfo) {
        mPackageInfo = packageInfo;
        mResources = resources;
        mPackageName = packageName;
        mTheme = resources.newTheme();
        if (themeResId != 0) mTheme.applyStyle(themeResId, true);
    }
    /** AOSP ContextImpl.createAppContext(ActivityThread, LoadedApk): the hosted
     * context over the LoadedApk's resources (real ResourcesManager) until the
     * real ContextImpl lands. */
    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo) {
        return new ContextImpl(packageInfo.getResources(), packageInfo.getPackageName(), 0, packageInfo);
    }
    /** Boot-time factory: framework-res only (app apk added when APKs load, M4). */
    public static ContextImpl createSystemContext(String frameworkResPath, int width, int height,
            int scale120, int themeResId) throws java.io.IOException {
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkResPath);
        ApkAssets apk = ApkAssets.loadFromPath(frameworkResPath, ApkAssets.PROPERTY_SYSTEM);
        AssetManager am = new AssetManager.Builder().addApkAssets(apk).build();
        DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        float density = scale120 / 120f;
        int densityDpi = Math.round(160f * density);
        android.view.ArtzigDisplay.configure(width, height, densityDpi);
        dm.widthPixels = dm.noncompatWidthPixels = width;
        dm.heightPixels = dm.noncompatHeightPixels = height;
        dm.densityDpi = dm.noncompatDensityDpi = densityDpi;
        dm.density = dm.noncompatDensity = density;
        dm.noncompatScaledDensity = dm.density;
        dm.xdpi = dm.noncompatXdpi = densityDpi;
        dm.ydpi = dm.noncompatYdpi = densityDpi;
        Configuration config = new Configuration();
        config.setToDefaults();
        config.densityDpi = densityDpi;
        config.screenWidthDp = (int) (width / dm.density);
        config.screenHeightDp = (int) (height / dm.density);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.orientation = width >= height
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        Resources res = HostedResources.create(am, dm, config, null);
        return new ContextImpl(res, "android", themeResId);
    }
    @Override public Resources getResources() { return mResources; }
    @Override public AssetManager getAssets() { return mResources.getAssets(); }
    @Override public Resources.Theme getTheme() { return mTheme; }
    @Override public String getPackageName() { return mPackageName; }
    /** The real AOSP client over the session's IPackageManager (AOSP shape:
     * one ApplicationPackageManager per ContextImpl). */
    private volatile ApplicationPackageManager mPackageManager;
    @Override public android.content.pm.PackageManager getPackageManager() {
        ApplicationPackageManager pm = mPackageManager;
        if (pm == null) {
            pm = new ApplicationPackageManager(this, ActivityThread.getPackageManager());
            mPackageManager = pm;
        }
        return pm;
    }
    /**
     * Hosted adaptation of AOSP ContextImpl permission checks. PermissionManager
     * is a system_server service and is unavailable here, so PackageManager's
     * in-process installed-package state owns the equivalent decision.
     */
    /** Follows AOSP PermissionManagerService.checkPermission: root and system are granted everything. */
    @Override public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        if (uid == android.os.Process.ROOT_UID || uid == android.os.Process.SYSTEM_UID) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        if (uid == android.os.Process.myUid()) {
            return getPackageManager().checkPermission(permission, mPackageName);
        }
        try {
            return ActivityThread.getPackageManager().checkUidPermission(permission, uid);
        } catch (android.os.RemoteException e) {
            android.util.Log.w("ContextImpl", "permission " + permission + " for uid " + uid
                    + " denied: package manager unreachable", e);
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
    }
    @Override public int checkPermission(String permission, int pid, int uid, Object callerToken) {
        return checkPermission(permission, pid, uid);
    }
    @Override public int checkCallingPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        if (android.os.Binder.getCallingPid() == android.os.Process.myPid()) {
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
        return checkPermission(permission, android.os.Binder.getCallingPid(),
                android.os.Binder.getCallingUid());
    }
    @Override public int checkCallingOrSelfPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        return checkPermission(permission, android.os.Binder.getCallingPid(),
                android.os.Binder.getCallingUid());
    }
    @Override public int checkSelfPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        return checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid());
    }
    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        if (checkPermission(permission, pid, uid)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException((message != null ? message + ": " : "")
                    + "uid " + uid + " does not have " + permission);
        }
    }
    @Override public void enforceCallingOrSelfPermission(String permission, String message) {
        if (checkCallingOrSelfPermission(permission)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException((message != null ? message + ": " : "")
                    + "permission " + permission + " denied");
        }
    }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
        try {
            return getPackageManager().getApplicationInfo(mPackageName, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return new android.content.pm.ApplicationInfo();
        }
    }
    private ClassLoader mClassLoader;
    public void setClassLoaderOverride(ClassLoader cl) { mClassLoader = cl; }
    /** AOSP ContextImpl.getDataDir: the LoadedApk's per-package data dir. */
    @Override public java.io.File getDataDir() {
        return mPackageInfo != null ? mPackageInfo.getDataDirFile() : super.getDataDir();
    }
    @Override public ClassLoader getClassLoader() {
        return mClassLoader != null ? mClassLoader : getClass().getClassLoader();
    }
    @Override public Context getApplicationContext() {
        Application app = ActivityThread.currentApplication();
        return app != null ? app : this;
    }
    /** AOSP ContextImpl.getDisplay: through DisplayManager against the hosted display service. */
    @Override public android.view.Display getDisplay() {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display display = dm != null ? dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) : null;
        return display != null ? display : android.view.ArtzigDisplay.get();
    }
    @Override public android.view.Display getDisplayNoVerify() { return getDisplay(); }
    /**
     * Hosted adaptation of AOSP ContextImpl.createConfigurationContext. ResourcesManager is not
     * available, so the host creates a ResourcesImpl over the same assets and merged configuration.
     */
    @Override public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) {
            throw new IllegalArgumentException("overrideConfiguration must not be null");
        }
        Configuration merged = new Configuration(mResources.getConfiguration());
        merged.updateFrom(overrideConfiguration);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(mResources.getDisplayMetrics());
        Resources configuredResources = HostedResources.create(
                mResources.getAssets(), metrics, merged, getClassLoader());
        ContextImpl configured = new ContextImpl(configuredResources, mPackageName, 0);
        configured.mTheme.setTo(mTheme);
        configured.mClassLoader = mClassLoader;
        configured.mIsConfigurationBasedContext = true;
        return configured;
    }
    @Override public boolean isConfigurationContext() { return mIsConfigurationBasedContext; }
    private android.content.ContentResolver mContentResolver;
    @Override public android.content.ContentResolver getContentResolver() {
        if (mContentResolver == null) mContentResolver = new ApplicationContentResolver(this);
        return mContentResolver;
    }
    /** AOSP ContextImpl.ApplicationContentResolver: the process's ActivityThread
     * owns provider connections (hosted: through the session activity manager). */
    private static final class ApplicationContentResolver extends android.content.ContentResolver {
        private final ActivityThread mMainThread;
        ApplicationContentResolver(android.content.Context context) {
            super(context);
            mMainThread = ActivityThread.currentActivityThread();
        }
        @Override protected android.content.IContentProvider acquireProvider(android.content.Context context, String auth) {
            return mMainThread.acquireProvider(context, android.content.ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), true);
        }
        @Override protected android.content.IContentProvider acquireExistingProvider(android.content.Context context, String auth) {
            return mMainThread.acquireExistingProvider(context, android.content.ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), true);
        }
        @Override public boolean releaseProvider(android.content.IContentProvider provider) {
            return mMainThread.releaseProvider(provider, true);
        }
        @Override protected android.content.IContentProvider acquireUnstableProvider(android.content.Context c, String auth) {
            return mMainThread.acquireProvider(c, android.content.ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), false);
        }
        @Override public boolean releaseUnstableProvider(android.content.IContentProvider icp) {
            return mMainThread.releaseProvider(icp, false);
        }
        @Override public void unstableProviderDied(android.content.IContentProvider icp) {
            mMainThread.handleUnstableProviderDied(icp.asBinder(), true);
        }
        @Override public void appNotRespondingViaProvider(android.content.IContentProvider icp) {
            mMainThread.appNotRespondingViaProvider(icp.asBinder());
        }
        private int resolveUserIdFromAuthority(String auth) {
            return android.content.ContentProvider.getUserIdFromAuthority(auth, getUserId());
        }
    }
    /** Per-context service cache; slots are assigned by HostedSystemServiceRegistry. */
    private Object[] mServiceCache = new Object[0];
    private final Object mServiceCacheLock = new Object();
    Object serviceCacheLock() { return mServiceCacheLock; }
    Object[] serviceCache(int slots) {
        if (mServiceCache.length < slots) mServiceCache = java.util.Arrays.copyOf(mServiceCache, slots);
        return mServiceCache;
    }
    private Context mOuterContext;
    /** Real Android: ContextImpl.setOuterContext — the Activity/Application facade. */
    public void setOuterContext(Context outer) { mOuterContext = outer; HostedSystemServiceRegistry.clearInflater(this); }
    public Context getOuterContext() { return mOuterContext != null ? mOuterContext : this; }
    @Override public Object getSystemService(String name) {
        return HostedSystemServiceRegistry.getSystemService(this, name);
    }
    @Override public String getSystemServiceName(Class<?> serviceClass) {
        return HostedSystemServiceRegistry.getSystemServiceName(serviceClass);
    }
}
