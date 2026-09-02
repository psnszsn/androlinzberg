/* artzig host adaptation of android.app.SystemServiceRegistry (AOSP static API:
 * getSystemService, getSystemServiceName, getSystemServiceClassName,
 * createServiceCache, the ServiceFetcher kinds and registerService, which the
 * real ContextImpl uses). The AOSP class cannot be compiled directly: its
 * static initializer names ~190 manager classes and framework initializers
 * whose services do not exist on this host. This table registers the managers
 * the hosted services back, with the AOSP creation expressions where the
 * manager is the real class. */
package android.app;

import android.content.Context;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.ArrayMap;
import android.util.Slog;

public final class SystemServiceRegistry {
    private static final String TAG = "SystemServiceRegistry";

    private static final ArrayMap<Class<?>, String> SYSTEM_SERVICE_NAMES = new ArrayMap<>();
    private static final ArrayMap<String, ServiceFetcher<?>> SYSTEM_SERVICE_FETCHERS = new ArrayMap<>();
    private static final ArrayMap<String, String> SYSTEM_SERVICE_CLASS_NAMES = new ArrayMap<>();
    private static int sServiceCacheSize;

    private SystemServiceRegistry() {}

    /** AOSP ServiceFetcher: creates the service on demand. */
    static abstract interface ServiceFetcher<T> {
        T getService(ContextImpl ctx);
    }

    /** AOSP CachedServiceFetcher: one instance per ContextImpl (its service cache slot). */
    static abstract class CachedServiceFetcher<T> implements ServiceFetcher<T> {
        private final int mCacheIndex;
        CachedServiceFetcher() { mCacheIndex = sServiceCacheSize++; }
        @Override @SuppressWarnings("unchecked")
        public final T getService(ContextImpl ctx) {
            final Object[] cache = ctx.mServiceCache;
            synchronized (cache) {
                T service = (T) cache[mCacheIndex];
                if (service != null) return service;
            }
            T service = null;
            try {
                service = createService(ctx);
            } catch (ServiceNotFoundException e) {
                onServiceNotFound(e);
            }
            synchronized (cache) {
                if (cache[mCacheIndex] == null) cache[mCacheIndex] = service;
                return (T) cache[mCacheIndex];
            }
        }
        public abstract T createService(ContextImpl ctx) throws ServiceNotFoundException;
    }

    /** AOSP StaticServiceFetcher: one instance per process. */
    static abstract class StaticServiceFetcher<T> implements ServiceFetcher<T> {
        private T mCachedInstance;
        @Override public final T getService(ContextImpl ctx) {
            synchronized (StaticServiceFetcher.this) {
                if (mCachedInstance == null) {
                    try {
                        mCachedInstance = createService();
                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);
                    }
                }
                return mCachedInstance;
            }
        }
        public abstract T createService() throws ServiceNotFoundException;
    }

    /** One instance per fetch (AOSP registrations that `new` on every call). */
    static abstract class NewServiceFetcher<T> implements ServiceFetcher<T> {
        @Override public final T getService(ContextImpl ctx) {
            try {
                return createService(ctx);
            } catch (ServiceNotFoundException e) {
                onServiceNotFound(e);
                return null;
            }
        }
        public abstract T createService(ContextImpl ctx) throws ServiceNotFoundException;
    }

    static {
        registerService(Context.LAYOUT_INFLATER_SERVICE, android.view.LayoutInflater.class,
                new CachedServiceFetcher<android.view.LayoutInflater>() {
            @Override public android.view.LayoutInflater createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new com.android.internal.policy.PhoneLayoutInflater(ctx.getOuterContext());
            }});
        registerService(Context.WINDOW_SERVICE, android.view.WindowManager.class,
                new CachedServiceFetcher<android.view.WindowManager>() {
            @Override public android.view.WindowManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.view.WindowManagerImpl(ctx);
            }});
        registerService(Context.CLIPBOARD_SERVICE, android.content.ClipboardManager.class,
                new CachedServiceFetcher<android.content.ClipboardManager>() {
            @Override public android.content.ClipboardManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.content.ClipboardManager(ctx.getOuterContext(), new android.os.Handler(ctx.getMainLooper()));
            }});
        registerService(Context.APP_OPS_SERVICE, AppOpsManager.class, new NewServiceFetcher<AppOpsManager>() {
            @Override public AppOpsManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new AppOpsManager(); }});
        registerService(Context.KEYGUARD_SERVICE, KeyguardManager.class, new NewServiceFetcher<KeyguardManager>() {
            @Override public KeyguardManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new KeyguardManager(); }});
        registerService(Context.TEXT_CLASSIFICATION_SERVICE, android.view.textclassifier.TextClassificationManager.class,
                new CachedServiceFetcher<android.view.textclassifier.TextClassificationManager>() {
            @Override public android.view.textclassifier.TextClassificationManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.view.textclassifier.TextClassificationManager(ctx.getOuterContext());
            }});
        registerService(Context.ACTIVITY_SERVICE, ActivityManager.class, new NewServiceFetcher<ActivityManager>() {
            @Override public ActivityManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new ActivityManager(); }});
        registerService(Context.DOWNLOAD_SERVICE, DownloadManager.class, new CachedServiceFetcher<DownloadManager>() {
            @Override public DownloadManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new DownloadManager(ctx.getOuterContext()); }});
        registerService(Context.AUDIO_SERVICE, android.media.AudioManager.class, new NewServiceFetcher<android.media.AudioManager>() {
            @Override public android.media.AudioManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.media.AudioManager(); }});
        registerService(Context.NOTIFICATION_SERVICE, NotificationManager.class, new NewServiceFetcher<NotificationManager>() {
            @Override public NotificationManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new NotificationManager(); }});
        registerService(Context.ALARM_SERVICE, AlarmManager.class, new NewServiceFetcher<AlarmManager>() {
            @Override public AlarmManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new AlarmManager(); }});
        registerService(Context.CONNECTIVITY_SERVICE, android.net.ConnectivityManager.class, new NewServiceFetcher<android.net.ConnectivityManager>() {
            @Override public android.net.ConnectivityManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.ConnectivityManager(); }});
        registerService(Context.TELEPHONY_SERVICE, android.telephony.TelephonyManager.class, new NewServiceFetcher<android.telephony.TelephonyManager>() {
            @Override public android.telephony.TelephonyManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.telephony.TelephonyManager(); }});
        registerService(Context.INPUT_METHOD_SERVICE, android.view.inputmethod.InputMethodManager.class,
                new CachedServiceFetcher<android.view.inputmethod.InputMethodManager>() {
            @Override public android.view.inputmethod.InputMethodManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return android.view.inputmethod.InputMethodManager.createStubInstance(ctx.getDisplayId(), ctx.getMainLooper());
            }});
        registerService(Context.POWER_SERVICE, android.os.PowerManager.class, new NewServiceFetcher<android.os.PowerManager>() {
            @Override public android.os.PowerManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.PowerManager(); }});
        registerService(Context.USER_SERVICE, android.os.UserManager.class, new NewServiceFetcher<android.os.UserManager>() {
            @Override public android.os.UserManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.UserManager(); }});
        registerService(Context.STORAGE_SERVICE, android.os.storage.StorageManager.class, new NewServiceFetcher<android.os.storage.StorageManager>() {
            @Override public android.os.storage.StorageManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.storage.StorageManager(); }});
        registerService(Context.STORAGE_STATS_SERVICE, android.app.usage.StorageStatsManager.class, new NewServiceFetcher<android.app.usage.StorageStatsManager>() {
            @Override public android.app.usage.StorageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.app.usage.StorageStatsManager(); }});
        registerService(Context.UI_MODE_SERVICE, UiModeManager.class, new CachedServiceFetcher<UiModeManager>() {
            @Override public UiModeManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new UiModeManager(ctx); }});
        registerService(Context.NSD_SERVICE, android.net.nsd.NsdManager.class, new CachedServiceFetcher<android.net.nsd.NsdManager>() {
            @Override public android.net.nsd.NsdManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.nsd.NsdManager(ctx); }});
        registerService(Context.BATTERY_SERVICE, android.os.BatteryManager.class, new NewServiceFetcher<android.os.BatteryManager>() {
            @Override public android.os.BatteryManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.BatteryManager(); }});
        registerService(Context.INPUT_SERVICE, android.hardware.input.InputManager.class, new CachedServiceFetcher<android.hardware.input.InputManager>() {
            @Override public android.hardware.input.InputManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.hardware.input.InputManager(ctx); }});
        registerService(Context.DISPLAY_SERVICE, android.hardware.display.DisplayManager.class, new CachedServiceFetcher<android.hardware.display.DisplayManager>() {
            @Override public android.hardware.display.DisplayManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.hardware.display.DisplayManager(ctx); }});
        registerService(Context.SENSOR_SERVICE, android.hardware.SensorManager.class, new CachedServiceFetcher<android.hardware.SensorManager>() {
            @Override public android.hardware.SensorManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.hardware.SensorManager(); }});
        registerService(Context.SHORTCUT_SERVICE, android.content.pm.ShortcutManager.class, new CachedServiceFetcher<android.content.pm.ShortcutManager>() {
            @Override public android.content.pm.ShortcutManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.content.pm.ShortcutManager(); }});
        registerService(Context.MEDIA_METRICS_SERVICE, android.media.metrics.MediaMetricsManager.class, new NewServiceFetcher<android.media.metrics.MediaMetricsManager>() {
            @Override public android.media.metrics.MediaMetricsManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.media.metrics.MediaMetricsManager(); }});
        registerService(Context.USAGE_STATS_SERVICE, android.app.usage.UsageStatsManager.class, new NewServiceFetcher<android.app.usage.UsageStatsManager>() {
            @Override public android.app.usage.UsageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.app.usage.UsageStatsManager(); }});
        registerService(Context.WIFI_SERVICE, android.net.wifi.WifiManager.class, new NewServiceFetcher<android.net.wifi.WifiManager>() {
            @Override public android.net.wifi.WifiManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.wifi.WifiManager(); }});
        registerService(Context.JOB_SCHEDULER_SERVICE, android.app.job.JobScheduler.class, new CachedServiceFetcher<android.app.job.JobScheduler>() {
            @Override public android.app.job.JobScheduler createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.app.job.JobScheduler(); }});
        registerService(Context.VIBRATOR_SERVICE, android.os.Vibrator.class, new NewServiceFetcher<android.os.Vibrator>() {
            @Override public android.os.Vibrator createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.Vibrator(); }});
        registerService(Context.ACCESSIBILITY_SERVICE, android.view.accessibility.AccessibilityManager.class,
                new NewServiceFetcher<android.view.accessibility.AccessibilityManager>() {
            @Override public android.view.accessibility.AccessibilityManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return android.view.accessibility.AccessibilityManager.getInstance(ctx);
            }});
        registerService(Context.AUTOFILL_MANAGER_SERVICE, android.view.autofill.AutofillManager.class,
                new CachedServiceFetcher<android.view.autofill.AutofillManager>() {
            @Override public android.view.autofill.AutofillManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.view.autofill.AutofillManager(ctx.getOuterContext(), null);
            }});
        // ApplicationPackageManager delegates (permission APIs, badging).
        registerService(Context.PERMISSION_CHECKER_SERVICE, android.permission.PermissionCheckerManager.class,
                new CachedServiceFetcher<android.permission.PermissionCheckerManager>() {
            @Override public android.permission.PermissionCheckerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.permission.PermissionCheckerManager(ctx.getOuterContext());
            }});
        registerService(Context.PERMISSION_SERVICE, android.permission.PermissionManager.class, new NewServiceFetcher<android.permission.PermissionManager>() {
            @Override public android.permission.PermissionManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.permission.PermissionManager(); }});
        registerService(Context.PERMISSION_CONTROLLER_SERVICE, android.permission.PermissionControllerManager.class,
                new NewServiceFetcher<android.permission.PermissionControllerManager>() {
            @Override public android.permission.PermissionControllerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.permission.PermissionControllerManager();
            }});
        registerService(Context.DEVICE_POLICY_SERVICE, android.app.admin.DevicePolicyManager.class, new NewServiceFetcher<android.app.admin.DevicePolicyManager>() {
            @Override public android.app.admin.DevicePolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.app.admin.DevicePolicyManager(); }});
    }

    /** AOSP createServiceCache: the per-ContextImpl slot array for CachedServiceFetchers. */
    public static Object[] createServiceCache() { return new Object[sServiceCacheSize]; }

    public static Object getSystemService(ContextImpl ctx, String name) {
        if (name == null) return null;
        final ServiceFetcher<?> fetcher = SYSTEM_SERVICE_FETCHERS.get(name);
        return fetcher != null ? fetcher.getService(ctx) : null;
    }

    public static String getSystemServiceName(Class<?> serviceClass) {
        return serviceClass != null ? SYSTEM_SERVICE_NAMES.get(serviceClass) : null;
    }

    public static String getSystemServiceClassName(String name) {
        return name != null ? SYSTEM_SERVICE_CLASS_NAMES.get(name) : null;
    }

    private static <T> void registerService(String serviceName, Class<T> serviceClass, ServiceFetcher<T> serviceFetcher) {
        SYSTEM_SERVICE_NAMES.put(serviceClass, serviceName);
        SYSTEM_SERVICE_FETCHERS.put(serviceName, serviceFetcher);
        SYSTEM_SERVICE_CLASS_NAMES.put(serviceName, serviceClass.getSimpleName());
    }

    static void onServiceNotFound(ServiceNotFoundException e) {
        Slog.wtf(TAG, e.getMessage(), e);
    }
}
