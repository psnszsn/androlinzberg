package dev.artzig.system

import android.content.Context
import android.content.pm.PackageManagerInternal
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.server.LocalServices
import com.android.server.SystemService
import com.android.server.SystemServiceManager
import com.android.server.companion.virtual.VirtualDeviceManagerInternal
import com.android.server.pm.UserManagerInternal
import com.android.server.utils.TimingsTraceAndSlog

/**
 * Starts the services the system process publishes on the AOSP
 * `SystemServiceManager` lifecycle (source note: `SystemServer.startBootstrapServices`
 * / `startOtherServices` reduced to what this host provides). Each service is a
 * `com.android.server.SystemService`; `publishBinderService` registers it in the
 * session's broker registry. Hosts the package manager, activity manager
 * (services + broadcasts), and the reused AOSP clipboard service.
 */
internal object SystemServices {
    private const val TAG = "SystemServer"
    private external fun nativeCreateSensorService(): android.os.IBinder
    private external fun nativeCreateMediaExtractorService(): android.os.IBinder
    private external fun nativeCreateAudioPolicyService(): android.os.IBinder
    private external fun nativeCreateAudioFlingerService(): android.os.IBinder

    @JvmStatic
    fun start(context: Context, stockWindows: StockWindowServices, onBootComplete: () -> Unit) {
        com.android.server.HostedBootTasks.start()
        android.os.ServiceManager.addService(Context.INPUT_METHOD_SERVICE, HostedInputMethodManagerService(context))
        val manager = SystemServiceManager(context)
        LocalServices.addService(SystemServiceManager::class.java, manager)
        val userLifecycle = HostedUserLifecycle()
        // Seams the reused AOSP services consult (clipboard first): window focus,
        // URI grants, and the IUserManager binder.
        LocalServices.addService(
            VirtualDeviceManagerInternal::class.java,
            HostedVirtualDeviceManagerInternal(),
        )
        android.os.ServiceManager.addService(Context.NSD_SERVICE, HostedNsdService(context))

        val trace = TimingsTraceAndSlog()
        // Source note: SystemServer.startBootstrapServices starts the activity
        // manager (and its AppOpsService) and PlatformCompat before the package
        // manager; PackageManagerService.main then creates the user, permission
        // and app-filter services and scans the system image.
        android.os.ServiceManager.addService("apexservice", HostedApexService())
        // apexd's activation step: no APEX needs mounting on the host.
        android.os.SystemProperties.set("apexd.status", "activated")
        val platformCompat = com.android.server.compat.PlatformCompat(context)
        android.os.ServiceManager.addService(Context.PLATFORM_COMPAT_SERVICE, platformCompat)
        android.os.ServiceManager.addService(Context.PLATFORM_COMPAT_NATIVE_SERVICE,
            com.android.server.compat.PlatformCompatNative(platformCompat))
        val activityManager = HostedActivityManagerService(context)
        activityManager.userLifecycle = userLifecycle
        activityManager.platformCompat = platformCompat
        android.os.ServiceManager.addService(Context.ACTIVITY_SERVICE, activityManager, true,
            android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL or android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL or
                android.os.IServiceManager.DUMP_FLAG_PROTO)
        android.os.ServiceManager.addService("processinfo", activityManager.processInfo)
        // C++ PermissionCache uses android.os.IPermissionController over these same authorities.
        android.os.ServiceManager.addService("permission",
            HostedNativePermissionController(context, activityManager))
        // ActivityManagerService.start publishes ActivityManagerInternal before
        // the package manager caches it (BroadcastHelper, AppsFilter).
        val activityManagerAccess = HostActivityManagerInternalAccess(activityManager,
            { checkNotNull(activityManager.packageService) { "package manager is not started" } }, userLifecycle)
        val activityManagerInternal = Class.forName("android.app.HostedActivityManagerInternal")
            .getConstructor(HostActivityManagerInternalAccess::class.java)
            .newInstance(activityManagerAccess) as android.app.ActivityManagerInternal
        LocalServices.addService(android.app.ActivityManagerInternal::class.java, activityManagerInternal)
        StockPackageServices.startAccessChecking(manager)
        val appOps = com.android.server.appop.HostedAppOpsBootstrap.create(context)
        activityManager.appOps = appOps
        // SystemServer.startBootstrapServices starts PowerManagerService before
        // the package manager (PackageDexOptimizer holds a wake lock).
        val power = HostedPowerManagerService()
        android.os.ServiceManager.addService(Context.POWER_SERVICE, power)
        LocalServices.addService(android.os.PowerManagerInternal::class.java,
            Class.forName("android.os.HostedPowerManagerInternal")
                .getConstructor(HostedPowerManagerService::class.java)
                .newInstance(power) as android.os.PowerManagerInternal)
        android.os.ServiceManager.addService(Context.THERMAL_SERVICE, HostedThermalService())
        val packages = StockPackageServices.startBootstrap(context, manager, platformCompat)
        val packageManager = packages.packages
        // SystemServer.startOtherServices: user-aware services follow the
        // package manager, which creates UserManagerService.
        android.os.ServiceManager.addService(Context.COMPANION_DEVICE_SERVICE, HostedCompanionDeviceService(context))
        android.os.ServiceManager.addService(Context.VIRTUAL_DEVICE_SERVICE, HostedVirtualDeviceService(context))
        android.os.ServiceManager.addService(Context.SENSOR_PRIVACY_SERVICE,
            HostedSensorPrivacyService(context))
        val processLauncher = HostedZygoteService(packageManager, activityManager::dispatchProcessExit)
        activityManager.processLauncher = processLauncher.serviceLauncher()
        activityManager.packageService = packageManager
        android.os.ServiceManager.addService(Context.WIFI_SERVICE,
            HostedWifiService(HostedPermissions(packageManager)))
        // Publish before services construct SystemSensorManager and wait for its native Binder.
        android.os.ServiceManager.addService("sensorservice", nativeCreateSensorService())
        android.os.ServiceManager.addService(Context.FINGERPRINT_SERVICE,
            HostedFingerprintService(HostedPermissions(packageManager), appOps))
        // SystemServer.startBootstrapServices: UriGrantsManagerService before
        // ActivityTaskManagerService, which caches its local service.
        manager.startService(com.android.server.uri.UriGrantsManagerService.Lifecycle::class.java)
        stockWindows.startTasks(manager, activityManager)
        android.os.ServiceManager.addService("hosted_installer", HostedInstallerService(context, activityManager))
        if (dev.artzig.testing.ProcessInspection.enabled) {
            val inspection = HostedInspectionRegistry(activityManager)
            android.os.ServiceManager.addService(dev.artzig.testing.ProcessInspection.SERVICE, inspection)
            android.os.ServiceManager.addService("dev.artzig.testing.diagnostics", inspection.sessionDiagnostics)
        }
        // Observer registry and the settings provider, registered before any
        // service that reads settings during startup.
        android.os.ServiceManager.addService("content", HostedContentService())
        // Stock stats-manager caches pull callbacks while the native statsd daemon is absent.
        android.os.ServiceManager.addService("statsmanager", com.android.server.stats.StatsManagerService(context))
        val settingsInfo = android.content.pm.ProviderInfo()
        settingsInfo.packageName = "android"
        settingsInfo.name = SettingsProvider::class.java.name
        settingsInfo.authority = "settings"
        settingsInfo.exported = true
        // The real AOSP ComponentInfo parcels applicationInfo.
        settingsInfo.applicationInfo = android.content.pm.ApplicationInfo().apply {
            packageName = "android"
            processName = "android"
        }
        val settingsProvider = SettingsProvider()
        settingsProvider.attachInfo(context, settingsInfo)
        activityManager.addSystemProvider(
            android.app.ContentProviderHolder(settingsInfo).also { it.provider = settingsProvider.getIContentProvider() },
        )
        // The system process is an attached process of its own activity manager
        // (ActivityManagerService.setSystemProcess) so system services bind app
        // services through the real Context, as JobServiceContext does.
        // SystemServer initializes the stock package-attribute cache before setSystemProcess.
        com.android.internal.policy.AttributeCache.init(context)
        activityManager.attachApplication(android.app.ActivityThread.currentActivityThread().applicationThread, 0)
        // Managers over hosted services, phase 1: notification, alarm, job scheduler.
        // Registered before any app can attach, and the notification service
        // first: the activity manager posts foreground-service notifications and
        // the job scheduler posts user-initiated job notifications through it.
        val notifications = HostedNotificationManagerService()
        notifications.packageService = packageManager
        activityManager.notificationService = notifications
        android.os.ServiceManager.addService(Context.NOTIFICATION_SERVICE, notifications)
        val alarms = HostedAlarmManagerService(context)
        alarms.packageService = packageManager
        android.os.ServiceManager.addService(Context.ALARM_SERVICE, alarms)
        val jobs = HostedJobSchedulerService(context)
        jobs.packageService = packageManager
        jobs.notificationService = notifications
        android.os.ServiceManager.addService(Context.JOB_SCHEDULER_SERVICE, jobs)
        // Managers over hosted services, phase 2: power (+ thermal), vibrator, battery
        // (properties, sticky broadcast, battery stats), usage and storage stats, device idle.
        manager.startService(com.android.server.power.hint.HintManagerService::class.java)
        android.os.ServiceManager.addService(Context.VIBRATOR_MANAGER_SERVICE, HostedVibratorManagerService())
        val battery = HostedBatteryService(activityManager)
        activityManager.batteryService = battery
        LocalServices.addService(android.os.BatteryManagerInternal::class.java,
            Class.forName("android.os.HostedBatteryManagerInternal")
                .getConstructor(HostedBatteryService::class.java)
                .newInstance(battery) as android.os.BatteryManagerInternal)
        android.os.ServiceManager.addService("batteryproperties", battery.registrar)
        android.os.ServiceManager.addService(android.os.BatteryStats.SERVICE_NAME, battery.batteryStats)
        android.os.ServiceManager.addService(Context.CONNECTIVITY_SERVICE, HostedConnectivityService(context, packageManager))
        jobs.startNetworkTracking(checkNotNull(context.getSystemService(android.net.ConnectivityManager::class.java)))
        android.os.ServiceManager.addService(Context.NETWORK_STATS_SERVICE, HostedNetworkStatsService(context = context))
        android.os.ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, HostedNetworkPolicyService(context))
        val usageStats = HostedUsageStatsService(stockActivities = true)
        usageStats.packageService = packageManager
        android.os.ServiceManager.addService(Context.USAGE_STATS_SERVICE, usageStats)
        val usageLocal = Class.forName("android.app.usage.HostedUsageStatsManagerInternal")
            .getConstructor(HostedUsageStatsService::class.java)
            .newInstance(usageStats) as android.app.usage.UsageStatsManagerInternal
        LocalServices.addService(android.app.usage.UsageStatsManagerInternal::class.java, usageLocal)
        stockWindows.setUsageStats(usageLocal)
        val storageStats = HostedStorageStatsService()
        storageStats.packageService = packageManager
        android.os.ServiceManager.addService(Context.STORAGE_STATS_SERVICE, storageStats)
        // SystemServer "StartStorageManagerService": stock StorageManagerService
        // over the session's vold and storaged stand-ins, which it connects to
        // by name on start.
        android.os.ServiceManager.addService("vold", HostedVold())
        android.os.ServiceManager.addService("storaged", HostedStoraged())
        // The stock class is package-private; SystemServiceManager loads it by name.
        manager.startService("com.android.server.StorageManagerService\$Lifecycle")
        // SystemServer.startOtherServices: AppHibernationService and ART Service
        // after the core services (usage stats, job scheduler, storage).
        StockPackageServices.startOther(context, packages)
        android.os.ServiceManager.addService(
            Context.DEVICE_POLICY_SERVICE,
            HostedDevicePolicyManagerService(),
        )
        android.os.ServiceManager.addService(Context.STATUS_BAR_SERVICE, HostedStatusBarService())
        android.os.ServiceManager.addService(Context.INCIDENT_SERVICE, HostedIncidentManagerService())
        android.os.ServiceManager.addService(Context.INCIDENT_COMPANION_SERVICE, HostedIncidentCompanionService())
        android.os.ServiceManager.addService(Context.DEVICE_IDLE_CONTROLLER, HostedDeviceIdleController())
        // Native audio services behind stock libaudioclient: media.audio_policy selects the
        // host output, media.audio_flinger owns tracks and renders through PipeWire.
        android.os.ServiceManager.addService("media.audio_policy", nativeCreateAudioPolicyService())
        android.os.ServiceManager.addService("media.audio_flinger", nativeCreateAudioFlingerService())
        android.os.ServiceManager.addService(Context.AUDIO_SERVICE, HostedAudioService(android.os.PermissionEnforcer.fromContext(context), context))
        android.os.ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, HostedMediaRouterService(context, packageManager))
        val mediaSessions = HostedMediaSessionService()
        mediaSessions.packageService = packageManager
        android.os.ServiceManager.addService(Context.MEDIA_SESSION_SERVICE, mediaSessions)
        android.os.ServiceManager.addService(Context.MEDIA_METRICS_SERVICE, HostedMediaMetricsService())
        android.os.ServiceManager.addService("media.extractor", nativeCreateMediaExtractorService())
        // Phase 4: location (no providers), lights (none), color display (no transforms).
        // Original native sensor clients now use the system sensor service.
        val location = HostedLocationManagerService()
        android.os.ServiceManager.addService(Context.LOCATION_SERVICE, location)
        android.os.ServiceManager.addService(Context.LIGHTS_SERVICE, HostedLightsService())
        // ActivityRecord attaches its per-app color transform to this stock
        // local service; Binder and LocalServices must share its controller.
        manager.startService(com.android.server.display.color.ColorDisplayService::class.java)
        // Phase 5: telephony and telecom for a device without a radio (no SIM, no modem,
        // no subscriptions, no dialer). The phone count is TelephonyManager's active modem
        // count, exactly as TelephonyRegistry and SubscriptionManagerService compute it.
        val telephonyPermissions = HostedTelephonyPermissions(packageManager)
        val modemCount = android.telephony.TelephonyManager.from(context).activeModemCount
        android.os.ServiceManager.addService(Context.TELEPHONY_SERVICE, HostedTelephonyService(telephonyPermissions))
        android.os.ServiceManager.addService("isub", HostedSubscriptionService(telephonyPermissions, modemCount))
        android.os.ServiceManager.addService("iphonesubinfo", HostedPhoneSubInfoService(telephonyPermissions))
        android.os.ServiceManager.addService(Context.CARRIER_CONFIG_SERVICE, HostedCarrierConfigLoader(telephonyPermissions))
        android.os.ServiceManager.addService("telephony.registry", HostedTelephonyRegistry(telephonyPermissions, modemCount))
        android.os.ServiceManager.addService(Context.TELECOM_SERVICE, HostedTelecomService(context, telephonyPermissions, packageManager))
        // Phase 6: the policy long tail (UI mode, keyguard's trust and lock-settings services,
        // search, dropbox, overlays, domain verification, wallpaper, credentials, shortcuts,
        // launcher apps, app widgets) for a device with no launcher, lock screen or providers.
        val hostedPermissions = HostedPermissions(packageManager)
        manager.startService(com.android.server.dreams.DreamManagerService::class.java)
        manager.startService("com.android.server.UiModeManagerService")
        // ATMS owns package overrides, configuration delivery and persistence.
        manager.startService(com.android.server.grammaticalinflection.GrammaticalInflectionService::class.java)
        // SystemServer StartLocaleManagerService: reuse ATMS package configuration
        // updates, persistence, broadcasts and the stock locale backup observers.
        manager.startService(com.android.server.locales.LocaleManagerService::class.java)
        // SystemServer.startOtherServices: LOCK_SETTINGS_SERVICE_CLASS, over
        // gatekeeperd and keystore2.
        manager.startService("com.android.server.locksettings.LockSettingsService\$Lifecycle")
        // SystemServer "StartTrustManager", which reads LockSettings' state.
        manager.startService(com.android.server.trust.TrustManagerService::class.java)
        android.os.ServiceManager.addService(Context.SEARCH_SERVICE, HostedSearchManagerService(context, packageManager))
        android.os.ServiceManager.addService(Context.DROPBOX_SERVICE, HostedDropBoxManagerService(context, hostedPermissions))
        android.os.ServiceManager.addService(Context.OVERLAY_SERVICE, HostedOverlayManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.WALLPAPER_SERVICE, HostedWallpaperManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.CREDENTIAL_SERVICE, HostedCredentialManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.SHORTCUT_SERVICE, HostedShortcutService(context, hostedPermissions))
        val launcherApps = HostedLauncherAppsService(context, packageManager, hostedPermissions)
        android.os.ServiceManager.addService(Context.LAUNCHER_APPS_SERVICE, launcherApps)
        LocalServices.addService(HostedLauncherAppsService::class.java, launcherApps)
        android.os.ServiceManager.addService(Context.APPWIDGET_SERVICE, HostedAppWidgetService(context, hostedPermissions))
        stockWindows.startWindows(manager, activityManager)
        android.os.ServiceManager.addService(Context.SAFETY_CENTER_SERVICE, HostedSafetyCenterService())
        // Stock AccessibilityManagerService: window and interaction-connection
        // tracking for every app, and the system-server half of UiAutomation. As in
        // SystemServer.startOtherServices it follows the window manager, whose
        // accessibility controller, power and settings it reads when constructed.
        manager.startService(com.android.server.accessibility.AccessibilityManagerService.Lifecycle::class.java)
        // Reused AOSP clipboard service (publishes "clipboard" itself).
        HostedClipboardMonitor.enable()
        manager.startService(com.android.server.clipboard.ClipboardService::class.java)
        manager.startService(com.android.server.NetworkScoreService.Lifecycle::class.java)
        // SystemServer "StartDeviceMonitor": UserManagerService.createUser refuses
        // a new user while DeviceStorageMonitorInternal reports low storage.
        manager.startService(com.android.server.storage.DeviceStorageMonitorService::class.java)
        manager.startService(com.android.server.texttospeech.TextToSpeechManagerService::class.java)
        // SystemServer registers compatibility observers after the activity and
        // content services can receive package broadcasts and settings changes.
        platformCompat.registerPackageReceiver(context)
        platformCompat.registerContentObserver()
        // SystemServer "MakeLockSettingsServiceReady", then the phase DevicePolicy
        // and other lock-screen clients wait for.
        com.android.internal.widget.ILockSettings.Stub.asInterface(
            android.os.ServiceManager.getService("lock_settings")).systemReady()
        manager.startBootPhase(trace, SystemService.PHASE_LOCK_SETTINGS_READY)
        manager.preSystemReady()
        // SystemServer "StartPermissionPolicyService": the launch policy and the
        // per-user permission/app-op synchronization. The PermissionController
        // stand-in answers its default-grant request at once.
        manager.startService(com.android.server.policy.PermissionPolicyService::class.java)
        appOps.systemReady()
        manager.startBootPhase(trace, SystemService.PHASE_SYSTEM_SERVICES_READY)
        LocalServices.getService(com.android.server.uri.UriGrantsManagerInternal::class.java).onSystemReady()
        // Pinned AMS.systemReady marks its state before the phase callback.
        activityManagerAccess.setSystemReady()
        stockWindows.systemReady()
        // SystemServer: PackageManagerService.systemReady follows WindowManagerService.systemReady.
        StockPackageServices.systemReady(packages)
        manager.startBootPhase(trace, SystemService.PHASE_ACTIVITY_MANAGER_READY)
        com.android.server.webkit.HostedWebViewUpdateService(context, hostedPermissions,
            processLauncher::selectWebViewProvider).start()
        manager.startBootPhase(trace, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
        // SystemServer "LockSettingsThirdPartyAppsStarted", before AMS starts the
        // system user: synthetic-password work deferred until weaver could exist.
        LocalServices.getService(com.android.internal.widget.LockSettingsInternal::class.java)
            .onThirdPartyAppsStarted()
        val userManager = com.android.server.pm.UserManagerService.getInstance()
        val userManagerInternal = LocalServices.getService(com.android.server.pm.UserManagerInternal::class.java)
        userLifecycle.start(object : HostedUserLifecycle.Services {
            override fun beforeStart(userId: Int) = userManager.onBeforeStartUser(userId)
            override fun beforeUnlock(userId: Int) = userManager.onBeforeUnlockUser(userId)
            override fun stateChanged(userId: Int, state: Int) = userManagerInternal.setUserState(userId, state)
            override fun starting(userId: Int) {
                manager.onUserStarting(trace, userId)
            }
            override fun unlocking(userId: Int) { manager.onUserUnlocking(userId) }
            override fun unlocked(userId: Int) { manager.onUserUnlocked(userId) }
        })
        val completionHandler = android.os.Handler(android.os.Looper.getMainLooper())
        activityManager.finishBooting {
            check(completionHandler.post {
                activityManager.booting = false
                activityManager.booted = true
                // Pinned AMS.ensureBootCompleted sets these flags before asking
                // ATMS to enable screen/input. Host boot has no initial home
                // activity whose idle callback would otherwise perform this step.
                stockWindows.enableScreenAfterBoot(activityManager.booted)
                manager.startBootPhase(trace, SystemService.PHASE_BOOT_COMPLETED)
                // BatteryService publishes its sticky broadcast once the broadcast queue is up.
                battery.start()
                stockWindows.verifyStartup()
                // AMS.finishBooting publishes these after PHASE_BOOT_COMPLETED.
                // Native daemons (notably keystore2) wait on the standard property.
                android.os.SystemProperties.set("sys.boot_completed", "1")
                android.os.SystemProperties.set("dev.bootcomplete", "1")
                android.os.SystemProperties.set("sys.azk.boot_completed", "1")
                Log.i(TAG, "system services started for " + context.packageName)
                // The session's preinstalled packages (`pm install` before an
                // instrumentation run) are present when the session reports ready.
                val preinstalled = System.getenv("AZK_SESSION_INSTALL")?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
                val ready = { awaitPrimaryStorage(context, completionHandler, onBootComplete) }
                if (preinstalled.isEmpty()) ready() else kotlin.concurrent.thread(name = "session-install") {
                    val failure = runCatching {
                        for (apk in preinstalled) Log.i(TAG, "preinstalled " + StockPackageServices.installApk(context, java.io.File(apk)))
                    }.exceptionOrNull()
                    // A failed preinstall is a failed boot: rethrow on the main
                    // thread, so the system process dies with the install error
                    // and the session reports it instead of waiting for ready.
                    if (failure == null) ready()
                    else check(completionHandler.post { throw IllegalStateException("Session preinstall failed", failure) })
                }
            })
        }
        stockWindows.bootCompleted()
    }

    private const val PRIMARY_STORAGE_TIMEOUT_MS = 20_000L
    private const val PRIMARY_STORAGE_POLL_MS = 100L

    /**
     * A process started after the session reports ready sees a booted device,
     * including the system user's primary emulated volume. Stock
     * StorageManagerService mounts it asynchronously after user start (vold,
     * then the MediaProvider's ExternalStorageService) and reports it unmounted
     * until its own unlock handling completes. The state is read through
     * IStorageManager directly: StorageManager's client cache may hold the
     * answer from before the unlock. Runs on [handler]'s looper.
     */
    private fun awaitPrimaryStorage(context: Context, handler: android.os.Handler, then: () -> Unit) {
        val service = android.os.storage.IStorageManager.Stub.asInterface(
            android.os.ServiceManager.getService("mount"))
        val deadline = android.os.SystemClock.uptimeMillis() + PRIMARY_STORAGE_TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                val primary = service.getVolumeList(UserHandle.USER_SYSTEM, context.opPackageName, 0)
                    .firstOrNull { it.isPrimary }
                if (primary?.state == android.os.Environment.MEDIA_MOUNTED) {
                    Log.i(TAG, "primary storage mounted at " + primary.pathFile)
                    then()
                } else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                    throw IllegalStateException("primary storage not mounted after " +
                        PRIMARY_STORAGE_TIMEOUT_MS + " ms: " + (primary?.state ?: "no primary volume"))
                } else {
                    handler.postDelayed(this, PRIMARY_STORAGE_POLL_MS)
                }
            }
        }
        check(handler.post(poll))
    }

}
