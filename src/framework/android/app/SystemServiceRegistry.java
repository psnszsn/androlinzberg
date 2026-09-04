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
        registerService(Context.KEYGUARD_SERVICE, KeyguardManager.class, new CachedServiceFetcher<KeyguardManager>() {
            @Override public KeyguardManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new KeyguardManager(ctx); }});
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
        // AOSP creation expressions: the real managers over the hosted
        // INotificationManager / IAlarmManager services.
        registerService(Context.NOTIFICATION_SERVICE, NotificationManager.class,
                new CachedServiceFetcher<NotificationManager>() {
            @Override public NotificationManager createService(ContextImpl ctx) {
                final Context outerContext = ctx.getOuterContext();
                return new NotificationManager(
                    new android.view.ContextThemeWrapper(outerContext,
                            android.content.res.Resources.selectSystemTheme(0,
                                    outerContext.getApplicationInfo().targetSdkVersion,
                                    com.android.internal.R.style.Theme_Dialog,
                                    com.android.internal.R.style.Theme_Holo_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Light_Dialog))
                );
            }});
        registerService(Context.ALARM_SERVICE, AlarmManager.class,
                new CachedServiceFetcher<AlarmManager>() {
            @Override public AlarmManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.ALARM_SERVICE);
                IAlarmManager service = IAlarmManager.Stub.asInterface(b);
                return new AlarmManager(service, ctx);
            }});
        registerService(Context.CONNECTIVITY_SERVICE, android.net.ConnectivityManager.class, new NewServiceFetcher<android.net.ConnectivityManager>() {
            @Override public android.net.ConnectivityManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.ConnectivityManager(); }});
        // Telephony and telecom (AOSP: ActivityThread installs the TelephonyServiceManager,
        // SystemServiceRegistry's static block runs TelephonyFrameworkInitializer and registers
        // the registry, telecom and MMS managers). The managers resolve "phone", "isub",
        // "iphonesubinfo", "carrier_config", "telephony.registry", "telecom" and "imms" through
        // ServiceManager; the hosted services answer as a device without a radio.
        android.telephony.TelephonyFrameworkInitializer.setTelephonyServiceManager(new android.os.TelephonyServiceManager());
        android.telephony.TelephonyFrameworkInitializer.registerServiceWrappers();
        registerService(Context.TELEPHONY_REGISTRY_SERVICE, android.telephony.TelephonyRegistryManager.class,
                new CachedServiceFetcher<android.telephony.TelephonyRegistryManager>() {
            @Override public android.telephony.TelephonyRegistryManager createService(ContextImpl ctx) {
                return new android.telephony.TelephonyRegistryManager(ctx);
            }});
        registerService(Context.TELECOM_SERVICE, android.telecom.TelecomManager.class,
                new CachedServiceFetcher<android.telecom.TelecomManager>() {
            @Override public android.telecom.TelecomManager createService(ContextImpl ctx) {
                return new android.telecom.TelecomManager(ctx.getOuterContext());
            }});
        registerService(Context.MMS_SERVICE, android.telephony.MmsManager.class,
                new CachedServiceFetcher<android.telephony.MmsManager>() {
            @Override public android.telephony.MmsManager createService(ContextImpl ctx) {
                return new android.telephony.MmsManager(ctx.getOuterContext());
            }});
        registerService(Context.INPUT_METHOD_SERVICE, android.view.inputmethod.InputMethodManager.class,
                new ServiceFetcher<android.view.inputmethod.InputMethodManager>() {
            @Override public android.view.inputmethod.InputMethodManager getService(ContextImpl ctx) {
                return android.view.inputmethod.InputMethodManager.forContext(ctx.getOuterContext());
            }});
        registerService(Context.POWER_SERVICE, android.os.PowerManager.class,
                new CachedServiceFetcher<android.os.PowerManager>() {
            @Override public android.os.PowerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder powerBinder = android.os.ServiceManager.getServiceOrThrow(Context.POWER_SERVICE);
                android.os.IPowerManager powerService = android.os.IPowerManager.Stub.asInterface(powerBinder);
                android.os.IBinder thermalBinder = android.os.ServiceManager.getServiceOrThrow(Context.THERMAL_SERVICE);
                android.os.IThermalService thermalService = android.os.IThermalService.Stub.asInterface(thermalBinder);
                return new android.os.PowerManager(ctx.getOuterContext(), powerService, thermalService,
                        ctx.mMainThread.getHandler());
            }});
        registerService(Context.USER_SERVICE, android.os.UserManager.class, new CachedServiceFetcher<android.os.UserManager>() {
            @Override public android.os.UserManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder binder = android.os.ServiceManager.getServiceOrThrow(Context.USER_SERVICE);
                return new android.os.UserManager(ctx, android.os.IUserManager.Stub.asInterface(binder));
            }});
        registerService(Context.STORAGE_SERVICE, android.os.storage.StorageManager.class, new NewServiceFetcher<android.os.storage.StorageManager>() {
            @Override public android.os.storage.StorageManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.os.storage.StorageManager(); }});
        registerService(Context.STATUS_BAR_SERVICE, StatusBarManager.class,
                new CachedServiceFetcher<StatusBarManager>() {
            @Override public StatusBarManager createService(ContextImpl ctx) {
                return new StatusBarManager(ctx.getOuterContext());
            }});
        registerService(Context.STORAGE_STATS_SERVICE, android.app.usage.StorageStatsManager.class,
                new CachedServiceFetcher<android.app.usage.StorageStatsManager>() {
            @Override public android.app.usage.StorageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.app.usage.IStorageStatsManager service = android.app.usage.IStorageStatsManager.Stub.asInterface(
                        android.os.ServiceManager.getServiceOrThrow(Context.STORAGE_STATS_SERVICE));
                return new android.app.usage.StorageStatsManager(ctx, service);
            }});
        registerService(Context.UI_MODE_SERVICE, UiModeManager.class, new CachedServiceFetcher<UiModeManager>() {
            @Override public UiModeManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new UiModeManager(ctx.getOuterContext()); }});
        registerService(Context.GRAMMATICAL_INFLECTION_SERVICE, GrammaticalInflectionManager.class,
                new CachedServiceFetcher<GrammaticalInflectionManager>() {
            @Override public GrammaticalInflectionManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                return new GrammaticalInflectionManager(ctx,
                        IGrammaticalInflectionManager.Stub.asInterface(
                                android.os.ServiceManager.getServiceOrThrow(
                                        Context.GRAMMATICAL_INFLECTION_SERVICE)));
            }});
        registerService(Context.NSD_SERVICE, android.net.nsd.NsdManager.class, new CachedServiceFetcher<android.net.nsd.NsdManager>() {
            @Override public android.net.nsd.NsdManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.nsd.NsdManager(ctx); }});
        registerService(Context.BATTERY_SERVICE, android.os.BatteryManager.class,
                new CachedServiceFetcher<android.os.BatteryManager>() {
            @Override public android.os.BatteryManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                com.android.internal.app.IBatteryStats stats = com.android.internal.app.IBatteryStats.Stub.asInterface(
                        android.os.ServiceManager.getServiceOrThrow(android.os.BatteryStats.SERVICE_NAME));
                android.os.IBatteryPropertiesRegistrar registrar = android.os.IBatteryPropertiesRegistrar.Stub
                        .asInterface(android.os.ServiceManager.getServiceOrThrow("batteryproperties"));
                return new android.os.BatteryManager(ctx, stats, registrar);
            }});
        registerService(Context.INPUT_SERVICE, android.hardware.input.InputManager.class, new CachedServiceFetcher<android.hardware.input.InputManager>() {
            @Override public android.hardware.input.InputManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.hardware.input.InputManager(ctx); }});
        registerService(Context.DISPLAY_SERVICE, android.hardware.display.DisplayManager.class, new CachedServiceFetcher<android.hardware.display.DisplayManager>() {
            @Override public android.hardware.display.DisplayManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.hardware.display.DisplayManager(ctx); }});
        registerService(Context.SENSOR_SERVICE, android.hardware.SensorManager.class, new CachedServiceFetcher<android.hardware.SensorManager>() {
            @Override public android.hardware.SensorManager createService(ContextImpl ctx) {
                return new android.hardware.SystemSensorManager(ctx.getOuterContext(), ctx.mMainThread.getHandler().getLooper());
            }});
        registerService(Context.LIGHTS_SERVICE, android.hardware.lights.LightsManager.class, new CachedServiceFetcher<android.hardware.lights.LightsManager>() {
            @Override public android.hardware.lights.LightsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.hardware.lights.SystemLightsManager(ctx);
            }});
        registerService(Context.COLOR_DISPLAY_SERVICE, android.hardware.display.ColorDisplayManager.class, new CachedServiceFetcher<android.hardware.display.ColorDisplayManager>() {
            @Override public android.hardware.display.ColorDisplayManager createService(ContextImpl ctx) {
                return new android.hardware.display.ColorDisplayManager();
            }});
        registerService(Context.LOCATION_SERVICE, android.location.LocationManager.class, new CachedServiceFetcher<android.location.LocationManager>() {
            @Override public android.location.LocationManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.LOCATION_SERVICE);
                return new android.location.LocationManager(ctx, android.location.ILocationManager.Stub.asInterface(b));
            }});
        registerService(Context.SHORTCUT_SERVICE, android.content.pm.ShortcutManager.class, new CachedServiceFetcher<android.content.pm.ShortcutManager>() {
            @Override public android.content.pm.ShortcutManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.SHORTCUT_SERVICE);
                return new android.content.pm.ShortcutManager(ctx, android.content.pm.IShortcutService.Stub.asInterface(b));
            }});
        registerService(Context.LAUNCHER_APPS_SERVICE, android.content.pm.LauncherApps.class, new CachedServiceFetcher<android.content.pm.LauncherApps>() {
            @Override public android.content.pm.LauncherApps createService(ContextImpl ctx) { return new android.content.pm.LauncherApps(ctx); }});
        registerService(Context.SEARCH_SERVICE, android.app.SearchManager.class, new CachedServiceFetcher<android.app.SearchManager>() {
            @Override public android.app.SearchManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.app.SearchManager(ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});
        registerService(Context.DROPBOX_SERVICE, android.os.DropBoxManager.class, new CachedServiceFetcher<android.os.DropBoxManager>() {
            @Override public android.os.DropBoxManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.DROPBOX_SERVICE);
                return new android.os.DropBoxManager(ctx, com.android.internal.os.IDropBoxManagerService.Stub.asInterface(b));
            }});
        registerService(Context.OVERLAY_SERVICE, android.content.om.OverlayManager.class, new CachedServiceFetcher<android.content.om.OverlayManager>() {
            @Override public android.content.om.OverlayManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                final android.os.IBinder b = (android.compat.Compatibility.isChangeEnabled(android.content.om.OverlayManager.SELF_TARGETING_OVERLAY))
                        ? android.os.ServiceManager.getService(Context.OVERLAY_SERVICE)
                        : android.os.ServiceManager.getServiceOrThrow(Context.OVERLAY_SERVICE);
                return new android.content.om.OverlayManager(ctx, android.content.om.IOverlayManager.Stub.asInterface(b));
            }});
        registerService(Context.DOMAIN_VERIFICATION_SERVICE, android.content.pm.verify.domain.DomainVerificationManager.class,
                new CachedServiceFetcher<android.content.pm.verify.domain.DomainVerificationManager>() {
            @Override public android.content.pm.verify.domain.DomainVerificationManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.DOMAIN_VERIFICATION_SERVICE);
                return new android.content.pm.verify.domain.DomainVerificationManager(ctx,
                        android.content.pm.verify.domain.IDomainVerificationManager.Stub.asInterface(b));
            }});
        registerService(Context.WALLPAPER_SERVICE, android.app.WallpaperManager.class, new CachedServiceFetcher<android.app.WallpaperManager>() {
            @Override public android.app.WallpaperManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                final android.os.IBinder b = android.os.ServiceManager.getService(Context.WALLPAPER_SERVICE);
                if (b == null) {
                    android.content.pm.ApplicationInfo appInfo = ctx.getApplicationInfo();
                    if (appInfo.targetSdkVersion >= android.os.Build.VERSION_CODES.P
                            && appInfo.isInstantApp()) {
                        throw new ServiceNotFoundException(Context.WALLPAPER_SERVICE);
                    }
                    final boolean enabled = ctx.getResources().getBoolean(com.android.internal.R.bool.config_enableWallpaperService);
                    if (!enabled) {
                        throw new ServiceNotFoundException(Context.WALLPAPER_SERVICE);
                    }
                }
                android.app.IWallpaperManager service = android.app.IWallpaperManager.Stub.asInterface(b);
                return new android.app.WallpaperManager(service, ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});
        registerService(Context.CREDENTIAL_SERVICE, android.credentials.CredentialManager.class, new CachedServiceFetcher<android.credentials.CredentialManager>() {
            @Override public android.credentials.CredentialManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getService(Context.CREDENTIAL_SERVICE);
                android.credentials.ICredentialManager service = android.credentials.ICredentialManager.Stub.asInterface(b);
                if (service != null) {
                    return new android.credentials.CredentialManager(ctx.getOuterContext(), service);
                }
                return null;
            }});
        registerService(Context.APPWIDGET_SERVICE, android.appwidget.AppWidgetManager.class, new CachedServiceFetcher<android.appwidget.AppWidgetManager>() {
            @Override public android.appwidget.AppWidgetManager createService(ContextImpl ctx) {
                android.os.IBinder b = android.os.ServiceManager.getService(Context.APPWIDGET_SERVICE);
                return b == null ? null : new android.appwidget.AppWidgetManager(ctx, com.android.internal.appwidget.IAppWidgetService.Stub.asInterface(b));
            }});
        // MediaFrameworkPlatformInitializer / MediaFrameworkInitializer: the real MediaSessionManager
        // over the hosted ISessionManager, MediaMetricsManager over the hosted IMediaMetricsManager.
        // AOSP: the media framework initializer registers MediaSessionManager and owns the
        // MediaServiceManager the manager resolves its binder through.
        android.media.MediaFrameworkPlatformInitializer.setMediaServiceManager(new android.media.MediaServiceManager());
        android.media.MediaFrameworkPlatformInitializer.registerServiceWrappers();
        registerService(Context.MEDIA_METRICS_SERVICE, android.media.metrics.MediaMetricsManager.class,
                new CachedServiceFetcher<android.media.metrics.MediaMetricsManager>() {
            @Override public android.media.metrics.MediaMetricsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder iBinder = android.os.ServiceManager.getServiceOrThrow(Context.MEDIA_METRICS_SERVICE);
                android.media.metrics.IMediaMetricsManager service = android.media.metrics.IMediaMetricsManager.Stub.asInterface(iBinder);
                return new android.media.metrics.MediaMetricsManager(service, ctx.getUserId());
            }});
        registerService(Context.USAGE_STATS_SERVICE, android.app.usage.UsageStatsManager.class,
                new CachedServiceFetcher<android.app.usage.UsageStatsManager>() {
            @Override public android.app.usage.UsageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder iBinder = android.os.ServiceManager.getServiceOrThrow(Context.USAGE_STATS_SERVICE);
                android.app.usage.IUsageStatsManager service = android.app.usage.IUsageStatsManager.Stub.asInterface(iBinder);
                return new android.app.usage.UsageStatsManager(ctx.getOuterContext(), service);
            }});
        registerService(Context.WIFI_SERVICE, android.net.wifi.WifiManager.class, new NewServiceFetcher<android.net.wifi.WifiManager>() {
            @Override public android.net.wifi.WifiManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.net.wifi.WifiManager(); }});
        // JobSchedulerFrameworkInitializer.registerServiceWrappers: the real JobSchedulerImpl
        // over the hosted IJobScheduler.
        registerService(Context.JOB_SCHEDULER_SERVICE, android.app.job.JobScheduler.class, new CachedServiceFetcher<android.app.job.JobScheduler>() {
            @Override public android.app.job.JobScheduler createService(ContextImpl ctx) throws ServiceNotFoundException {
                android.os.IBinder b = android.os.ServiceManager.getServiceOrThrow(Context.JOB_SCHEDULER_SERVICE);
                return new JobSchedulerImpl(ctx.getOuterContext(), android.app.job.IJobScheduler.Stub.asInterface(b));
            }});
        registerService(Context.VIBRATOR_MANAGER_SERVICE, android.os.VibratorManager.class,
                new CachedServiceFetcher<android.os.VibratorManager>() {
            @Override public android.os.VibratorManager createService(ContextImpl ctx) { return new android.os.SystemVibratorManager(ctx); }});
        registerService(Context.VIBRATOR_SERVICE, android.os.Vibrator.class,
                new CachedServiceFetcher<android.os.Vibrator>() {
            @Override public android.os.Vibrator createService(ContextImpl ctx) { return new android.os.SystemVibrator(ctx); }});
        // DeviceIdleFrameworkInitializer.registerServiceWrappers.
        registerService(Context.DEVICE_IDLE_CONTROLLER, android.os.DeviceIdleManager.class,
                new CachedServiceFetcher<android.os.DeviceIdleManager>() {
            @Override public android.os.DeviceIdleManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new android.os.DeviceIdleManager(ctx.getOuterContext(),
                        android.os.IDeviceIdleController.Stub.asInterface(android.os.ServiceManager.getServiceOrThrow(Context.DEVICE_IDLE_CONTROLLER)));
            }});
        registerService(Context.POWER_EXEMPTION_SERVICE, android.os.PowerExemptionManager.class,
                new CachedServiceFetcher<android.os.PowerExemptionManager>() {
            @Override public android.os.PowerExemptionManager createService(ContextImpl ctx) { return new android.os.PowerExemptionManager(ctx.getOuterContext()); }});
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
        registerService(Context.PERMISSION_ENFORCER_SERVICE, android.os.PermissionEnforcer.class,
                new CachedServiceFetcher<android.os.PermissionEnforcer>() {
            @Override public android.os.PermissionEnforcer createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                return new android.os.PermissionEnforcer(ctx.getOuterContext());
            }});
        registerService(Context.PERMISSION_SERVICE, android.permission.PermissionManager.class, new NewServiceFetcher<android.permission.PermissionManager>() {
            @Override public android.permission.PermissionManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.permission.PermissionManager(); }});
        registerService(Context.PERMISSION_CONTROLLER_SERVICE, android.permission.PermissionControllerManager.class,
                new CachedServiceFetcher<android.permission.PermissionControllerManager>() {
            @Override public android.permission.PermissionControllerManager createService(ContextImpl ctx) {
                return new android.permission.PermissionControllerManager(
                        ctx.getOuterContext(), ctx.getMainThreadHandler());
            }});
        registerService(Context.DEVICE_POLICY_SERVICE, android.app.admin.DevicePolicyManager.class, new NewServiceFetcher<android.app.admin.DevicePolicyManager>() {
            @Override public android.app.admin.DevicePolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException { return new android.app.admin.DevicePolicyManager(); }});
    }

    /** AOSP ContextAwareServiceProducerWithBinder / WithoutBinder (the framework initializers' API). */
    public interface ContextAwareServiceProducerWithBinder<TServiceClass> {
        TServiceClass createService(Context ctx, android.os.IBinder service);
    }

    public interface ContextAwareServiceProducerWithoutBinder<TServiceClass> {
        TServiceClass createService(Context ctx);
    }

    public static <TServiceClass> void registerContextAwareService(String serviceName,
            Class<TServiceClass> serviceWrapperClass,
            ContextAwareServiceProducerWithBinder<TServiceClass> serviceProducer) {
        registerService(serviceName, serviceWrapperClass, new CachedServiceFetcher<TServiceClass>() {
            @Override public TServiceClass createService(ContextImpl ctx) throws ServiceNotFoundException {
                return serviceProducer.createService(ctx.getOuterContext(),
                        android.os.ServiceManager.getServiceOrThrow(serviceName));
            }});
    }

    public static <TServiceClass> void registerContextAwareService(String serviceName,
            Class<TServiceClass> serviceWrapperClass,
            ContextAwareServiceProducerWithoutBinder<TServiceClass> serviceProducer) {
        registerService(serviceName, serviceWrapperClass, new CachedServiceFetcher<TServiceClass>() {
            @Override public TServiceClass createService(ContextImpl ctx) {
                return serviceProducer.createService(ctx.getOuterContext());
            }});
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
