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
    public ContextImpl(Resources resources, String packageName, int themeResId) {
        mResources = resources;
        mPackageName = packageName;
        mTheme = resources.newTheme();
        if (themeResId != 0) mTheme.applyStyle(themeResId, true);
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
    private static final android.content.pm.PackageManager sPackageManager =
            new android.content.pm.PackageManager() {};
    @Override public android.content.pm.PackageManager getPackageManager() { return sPackageManager; }
    /**
     * Hosted adaptation of AOSP ContextImpl permission checks. PermissionManager
     * is a system_server service and is unavailable here, so PackageManager's
     * in-process installed-package state owns the equivalent decision.
     */
    @Override public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        if (uid != android.os.Process.myUid()) {
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
        return sPackageManager.checkPermission(permission, mPackageName);
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
            return sPackageManager.getApplicationInfo(mPackageName, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return new android.content.pm.ApplicationInfo();
        }
    }
    private ClassLoader mClassLoader;
    public void setClassLoaderOverride(ClassLoader cl) { mClassLoader = cl; }
    @Override public ClassLoader getClassLoader() {
        return mClassLoader != null ? mClassLoader : getClass().getClassLoader();
    }
    @Override public Context getApplicationContext() {
        Application app = ActivityThread.currentApplication();
        return app != null ? app : this;
    }
    @Override public android.view.Display getDisplay() { return android.view.ArtzigDisplay.get(); }
    @Override public android.view.Display getDisplayNoVerify() { return android.view.ArtzigDisplay.get(); }
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
        if (mContentResolver == null) mContentResolver = new android.content.ContentResolver() {};
        return mContentResolver;
    }
    private android.view.LayoutInflater mInflater;
    private android.view.WindowManagerImpl mWindowManager;
    private android.app.job.JobScheduler mJobScheduler;
    private android.hardware.input.InputManager mInputManager;
    private android.hardware.SensorManager mSensorManager;
    private android.content.pm.ShortcutManager mShortcutManager;
    private android.view.inputmethod.InputMethodManager mInputMethodManager;
    private android.view.autofill.AutofillManager mAutofillManager;
    private android.app.DownloadManager mDownloadManager;
    private android.app.UiModeManager mUiModeManager;
    private android.net.nsd.NsdManager mNsdManager;
    private Context mOuterContext;
    /** Real Android: ContextImpl.setOuterContext — the Activity/Application facade. */
    public void setOuterContext(Context outer) { mOuterContext = outer; mInflater = null; }
    public Context getOuterContext() { return mOuterContext != null ? mOuterContext : this; }
    @Override public Object getSystemService(String name) {
        switch (name) {
            case LAYOUT_INFLATER_SERVICE:
                if (mInflater == null) mInflater = new com.android.internal.policy.PhoneLayoutInflater(getOuterContext());
                return mInflater;
            case WINDOW_SERVICE:
                if (mWindowManager == null) mWindowManager = new android.view.WindowManagerImpl(this);
                return mWindowManager;
            case CLIPBOARD_SERVICE:
                return new android.content.ClipboardManager();
            case ACTIVITY_SERVICE:
                return new android.app.ActivityManager();
            case DOWNLOAD_SERVICE:
                if (mDownloadManager == null) {
                    mDownloadManager = new android.app.DownloadManager(getOuterContext());
                }
                return mDownloadManager;
            case AUDIO_SERVICE:
                return new android.media.AudioManager();
            case NOTIFICATION_SERVICE:
                return new android.app.NotificationManager();
            case ALARM_SERVICE:
                return new android.app.AlarmManager();
            case CONNECTIVITY_SERVICE:
                return new android.net.ConnectivityManager();
            case TELEPHONY_SERVICE:
                return new android.telephony.TelephonyManager();
            case INPUT_METHOD_SERVICE:
                if (mInputMethodManager == null) {
                    mInputMethodManager = android.view.inputmethod.InputMethodManager
                            .createStubInstance(getDisplayId(), getMainLooper());
                }
                return mInputMethodManager;
            case POWER_SERVICE:
                return new android.os.PowerManager();
            case USER_SERVICE:
                return new android.os.UserManager();
            case STORAGE_SERVICE:
                return new android.os.storage.StorageManager();
            case STORAGE_STATS_SERVICE:
                return new android.app.usage.StorageStatsManager();
            case UI_MODE_SERVICE:
                if (mUiModeManager == null) {
                    mUiModeManager = new android.app.UiModeManager(this);
                }
                return mUiModeManager;
            case NSD_SERVICE:
                if (mNsdManager == null) mNsdManager = new android.net.nsd.NsdManager(this);
                return mNsdManager;
            case BATTERY_SERVICE:
                return new android.os.BatteryManager();
            case INPUT_SERVICE:
                if (mInputManager == null) mInputManager = new android.hardware.input.InputManager(this);
                return mInputManager;
            case SENSOR_SERVICE:
                if (mSensorManager == null) mSensorManager = new android.hardware.SensorManager();
                return mSensorManager;
            case SHORTCUT_SERVICE:
                if (mShortcutManager == null) mShortcutManager = new android.content.pm.ShortcutManager();
                return mShortcutManager;
            case "media_metrics":
                return new android.media.metrics.MediaMetricsManager();
            case "usagestats":
                return new android.app.usage.UsageStatsManager();
            case WIFI_SERVICE:
                return new android.net.wifi.WifiManager();
            case JOB_SCHEDULER_SERVICE:
                if (mJobScheduler == null) mJobScheduler = new android.app.job.JobScheduler();
                return mJobScheduler;
            case VIBRATOR_SERVICE:
                return new android.os.Vibrator();
            case ACCESSIBILITY_SERVICE:
                return android.view.accessibility.AccessibilityManager.getInstance(this);
            case AUTOFILL_MANAGER_SERVICE:
                if (mAutofillManager == null) {
                    mAutofillManager = new android.view.autofill.AutofillManager(
                            getOuterContext(), null);
                }
                return mAutofillManager;
            default:
                return null;
        }
    }
    @Override public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == android.view.LayoutInflater.class) return LAYOUT_INFLATER_SERVICE;
        if (serviceClass == android.view.WindowManager.class) return WINDOW_SERVICE;
        if (serviceClass == android.app.UiModeManager.class) return UI_MODE_SERVICE;
        if (serviceClass == android.net.nsd.NsdManager.class) return NSD_SERVICE;
        if (serviceClass == android.os.BatteryManager.class) return BATTERY_SERVICE;
        if (serviceClass == android.app.job.JobScheduler.class) return JOB_SCHEDULER_SERVICE;
        if (serviceClass == android.net.wifi.WifiManager.class) return WIFI_SERVICE;
        if (serviceClass == android.media.metrics.MediaMetricsManager.class) return "media_metrics";
        if (serviceClass == android.app.usage.UsageStatsManager.class) return "usagestats";
        if (serviceClass == android.app.ActivityManager.class) return ACTIVITY_SERVICE;
        if (serviceClass == android.app.DownloadManager.class) return DOWNLOAD_SERVICE;
        if (serviceClass == android.app.NotificationManager.class) return NOTIFICATION_SERVICE;
        if (serviceClass == android.app.AlarmManager.class) return ALARM_SERVICE;
        if (serviceClass == android.media.AudioManager.class) return AUDIO_SERVICE;
        if (serviceClass == android.net.ConnectivityManager.class) return CONNECTIVITY_SERVICE;
        if (serviceClass == android.telephony.TelephonyManager.class) return TELEPHONY_SERVICE;
        if (serviceClass == android.os.PowerManager.class) return POWER_SERVICE;
        if (serviceClass == android.os.UserManager.class) return USER_SERVICE;
        if (serviceClass == android.os.storage.StorageManager.class) return STORAGE_SERVICE;
        if (serviceClass == android.app.usage.StorageStatsManager.class) return STORAGE_STATS_SERVICE;
        if (serviceClass == android.os.Vibrator.class) return VIBRATOR_SERVICE;
        if (serviceClass == android.view.accessibility.AccessibilityManager.class) return ACCESSIBILITY_SERVICE;
        if (serviceClass == android.content.ClipboardManager.class) return CLIPBOARD_SERVICE;
        if (serviceClass == android.hardware.input.InputManager.class) return INPUT_SERVICE;
        if (serviceClass == android.hardware.SensorManager.class) return SENSOR_SERVICE;
        if (serviceClass == android.content.pm.ShortcutManager.class) return SHORTCUT_SERVICE;
        if (serviceClass == android.view.inputmethod.InputMethodManager.class) return INPUT_METHOD_SERVICE;
        if (serviceClass == android.view.autofill.AutofillManager.class) return AUTOFILL_MANAGER_SERVICE;
        if (serviceClass == android.hardware.display.DisplayManager.class) return DISPLAY_SERVICE;
        return null;
    }
}
