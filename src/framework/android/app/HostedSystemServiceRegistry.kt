package android.app

import android.content.Context

/**
 * Table of system-service fetchers for [ContextImpl.getSystemService].
 *
 * Source note: follows `android.app.SystemServiceRegistry` — `registerService`
 * with a name, the manager class, and a fetcher; `CachedServiceFetcher`
 * instances get one slot per ContextImpl so per-context managers are created
 * once. The AOSP class itself registers ~100 managers this host does not
 * compile, so the table is hosted while keeping the AOSP shape.
 */
object HostedSystemServiceRegistry {
    /** Creates the service on demand; may cache per context or globally. */
    private fun interface ServiceFetcher<T> {
        fun getService(ctx: ContextImpl): T
    }

    private class CachedServiceFetcher<T>(private val create: (ContextImpl) -> T) : ServiceFetcher<T> {
        val slot = nextSlot++

        @Suppress("UNCHECKED_CAST")
        override fun getService(ctx: ContextImpl): T {
            synchronized(ctx.serviceCacheLock()) {
                val cache = ctx.serviceCache(slotCount)
                cache[slot]?.let { return it as T }
                val service = create(ctx)
                cache[slot] = service
                return service
            }
        }
    }

    /** One instance per fetch; matches the arms that did `new` on every call. */
    private class NewServiceFetcher<T>(private val create: (ContextImpl) -> T) : ServiceFetcher<T> {
        override fun getService(ctx: ContextImpl): T = create(ctx)
    }

    private var nextSlot = 0
    private val fetchers = HashMap<String, ServiceFetcher<*>>()
    private val names = HashMap<Class<*>, String>()
    private var inflaterSlot = -1

    @JvmStatic val slotCount: Int
        get() = nextSlot

    private fun <T> register(name: String, clazz: Class<T>, fetcher: ServiceFetcher<T>) {
        fetchers[name] = fetcher
        names[clazz] = name
    }

    private fun <T> cached(name: String, clazz: Class<T>, create: (ContextImpl) -> T) =
        register(name, clazz, CachedServiceFetcher(create))

    private fun <T> fresh(name: String, clazz: Class<T>, create: (ContextImpl) -> T) =
        register(name, clazz, NewServiceFetcher(create))

    @JvmStatic
    fun getSystemService(ctx: ContextImpl, name: String): Any? = fetchers[name]?.getService(ctx)

    @JvmStatic
    fun getSystemServiceName(serviceClass: Class<*>): String? = names[serviceClass]

    /** The outer context changed; drop the inflater built against the old one. */
    @JvmStatic
    fun clearInflater(ctx: ContextImpl) {
        if (inflaterSlot < 0) return
        synchronized(ctx.serviceCacheLock()) { ctx.serviceCache(slotCount)[inflaterSlot] = null }
    }

    init {
        val inflater = CachedServiceFetcher<android.view.LayoutInflater> { ctx ->
            com.android.internal.policy.PhoneLayoutInflater(ctx.outerContext)
        }
        inflaterSlot = inflater.slot
        register(Context.LAYOUT_INFLATER_SERVICE, android.view.LayoutInflater::class.java, inflater)
        cached(Context.WINDOW_SERVICE, android.view.WindowManager::class.java) { ctx ->
            android.view.WindowManagerImpl(ctx)
        }
        cached(Context.CLIPBOARD_SERVICE, android.content.ClipboardManager::class.java) { ctx ->
            android.content.ClipboardManager(ctx.outerContext, android.os.Handler(ctx.mainLooper))
        }
        fresh(Context.APP_OPS_SERVICE, android.app.AppOpsManager::class.java) { android.app.AppOpsManager() }
        fresh(Context.KEYGUARD_SERVICE, android.app.KeyguardManager::class.java) { android.app.KeyguardManager() }
        cached(
            Context.TEXT_CLASSIFICATION_SERVICE,
            android.view.textclassifier.TextClassificationManager::class.java,
        ) { ctx -> android.view.textclassifier.TextClassificationManager(ctx.outerContext) }
        fresh(Context.ACTIVITY_SERVICE, ActivityManager::class.java) { ActivityManager() }
        cached(Context.DOWNLOAD_SERVICE, DownloadManager::class.java) { ctx -> DownloadManager(ctx.outerContext) }
        fresh(Context.AUDIO_SERVICE, android.media.AudioManager::class.java) { android.media.AudioManager() }
        fresh(Context.NOTIFICATION_SERVICE, NotificationManager::class.java) { NotificationManager() }
        fresh(Context.ALARM_SERVICE, AlarmManager::class.java) { AlarmManager() }
        fresh(Context.CONNECTIVITY_SERVICE, android.net.ConnectivityManager::class.java) {
            android.net.ConnectivityManager()
        }
        fresh(Context.TELEPHONY_SERVICE, android.telephony.TelephonyManager::class.java) {
            android.telephony.TelephonyManager()
        }
        cached(Context.INPUT_METHOD_SERVICE, android.view.inputmethod.InputMethodManager::class.java) { ctx ->
            android.view.inputmethod.InputMethodManager.createStubInstance(ctx.displayId, ctx.mainLooper)
        }
        fresh(Context.POWER_SERVICE, android.os.PowerManager::class.java) { android.os.PowerManager() }
        fresh(Context.USER_SERVICE, android.os.UserManager::class.java) { android.os.UserManager() }
        fresh(Context.STORAGE_SERVICE, android.os.storage.StorageManager::class.java) {
            android.os.storage.StorageManager()
        }
        fresh(Context.STORAGE_STATS_SERVICE, android.app.usage.StorageStatsManager::class.java) {
            android.app.usage.StorageStatsManager()
        }
        cached(Context.UI_MODE_SERVICE, UiModeManager::class.java) { ctx -> UiModeManager(ctx) }
        cached(Context.NSD_SERVICE, android.net.nsd.NsdManager::class.java) { ctx -> android.net.nsd.NsdManager(ctx) }
        fresh(Context.BATTERY_SERVICE, android.os.BatteryManager::class.java) { android.os.BatteryManager() }
        cached(Context.INPUT_SERVICE, android.hardware.input.InputManager::class.java) { ctx ->
            android.hardware.input.InputManager(ctx)
        }
        cached(Context.DISPLAY_SERVICE, android.hardware.display.DisplayManager::class.java) { ctx ->
            android.hardware.display.DisplayManager(ctx)
        }
        cached(Context.SENSOR_SERVICE, android.hardware.SensorManager::class.java) {
            android.hardware.SensorManager()
        }
        cached(Context.SHORTCUT_SERVICE, android.content.pm.ShortcutManager::class.java) {
            android.content.pm.ShortcutManager()
        }
        fresh("media_metrics", android.media.metrics.MediaMetricsManager::class.java) {
            android.media.metrics.MediaMetricsManager()
        }
        fresh("usagestats", android.app.usage.UsageStatsManager::class.java) {
            android.app.usage.UsageStatsManager()
        }
        fresh(Context.WIFI_SERVICE, android.net.wifi.WifiManager::class.java) { android.net.wifi.WifiManager() }
        cached(Context.JOB_SCHEDULER_SERVICE, android.app.job.JobScheduler::class.java) {
            android.app.job.JobScheduler()
        }
        fresh(Context.VIBRATOR_SERVICE, android.os.Vibrator::class.java) { android.os.Vibrator() }
        fresh(Context.ACCESSIBILITY_SERVICE, android.view.accessibility.AccessibilityManager::class.java) { ctx ->
            android.view.accessibility.AccessibilityManager.getInstance(ctx)
        }
        cached(Context.AUTOFILL_MANAGER_SERVICE, android.view.autofill.AutofillManager::class.java) { ctx ->
            android.view.autofill.AutofillManager(ctx.outerContext, null)
        }
        // ApplicationPackageManager delegates (permission APIs, badging).
        fresh("permission", android.permission.PermissionManager::class.java) {
            android.permission.PermissionManager()
        }
        fresh("permission_controller", android.permission.PermissionControllerManager::class.java) {
            android.permission.PermissionControllerManager()
        }
        fresh("device_policy", android.app.admin.DevicePolicyManager::class.java) {
            android.app.admin.DevicePolicyManager()
        }
    }
}
