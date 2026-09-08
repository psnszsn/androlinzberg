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
    private external fun nativeCreateMediaExtractorService(): android.os.IBinder

    /** Single hosted user; `SystemServiceManager` asks for it on user start. */
    private class HostedUserManagerInternal(
        private val context: Context,
        private val lifecycle: HostedUserLifecycle,
    ) : UserManagerInternal() {
        private fun users(): MutableList<UserInfo> = UserManager.get(context).users

        override fun getUserInfo(userId: Int): UserInfo? =
            users().firstOrNull { it.id == userId }

        override fun setDevicePolicyUserRestrictions(
            originatingUserId: Int,
            global: android.os.Bundle?,
            local: com.android.server.pm.RestrictionsSet?,
            isDeviceOwner: Boolean,
        ) {}
        override fun setUserRestriction(userId: Int, key: String, value: Boolean) {}
        override fun getUserRestriction(userId: Int, key: String): Boolean = false
        override fun addUserRestrictionsListener(listener: UserRestrictionsListener) {}
        override fun removeUserRestrictionsListener(listener: UserRestrictionsListener) {}
        override fun addUserLifecycleListener(listener: UserLifecycleListener) {}
        override fun removeUserLifecycleListener(listener: UserLifecycleListener) {}
        override fun setDeviceManaged(isManaged: Boolean) {}
        override fun isDeviceManaged(): Boolean = false
        override fun setUserManaged(userId: Int, isManaged: Boolean) {}
        override fun isUserManaged(userId: Int): Boolean = false
        override fun setUserIcon(userId: Int, bitmap: android.graphics.Bitmap) {}
        override fun setForceEphemeralUsers(forceEphemeralUsers: Boolean) {}
        override fun removeAllUsers() {}
        override fun onEphemeralUserStop(userId: Int) {}
        override fun createUserEvenWhenDisallowed(
            name: String?,
            userType: String,
            flags: Int,
            disallowedPackages: Array<out String>?,
            token: Any?,
        ): UserInfo = throw UnsupportedOperationException("the hosted runtime has one fixed user")
        override fun removeUserEvenWhenDisallowed(userId: Int): Boolean = false
        override fun isUserUnlockingOrUnlocked(userId: Int): Boolean = lifecycle.isUnlockingOrUnlocked(userId)
        override fun isUserUnlocked(userId: Int): Boolean = lifecycle.isUnlocked(userId)
        override fun isUserRunning(userId: Int): Boolean = lifecycle.isRunning(userId)
        override fun isUserInitialized(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun exists(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun setUserState(userId: Int, userState: Int) {}
        override fun removeUserState(userId: Int) {}
        override fun getUserIds(): IntArray = intArrayOf(UserHandle.USER_SYSTEM)
        override fun getUsers(excludeDying: Boolean): MutableList<UserInfo> = users()
        override fun getUsers(
            excludePartial: Boolean,
            excludeDying: Boolean,
            excludePreCreated: Boolean,
        ): MutableList<UserInfo> = users()
        override fun getProfileIds(userId: Int, enabledOnly: Boolean): IntArray =
            if (userId == UserHandle.USER_SYSTEM) intArrayOf(userId) else intArrayOf()
        override fun getProfileIdsExcludingHidden(userId: Int, enabledOnly: Boolean): IntArray =
            getProfileIds(userId, enabledOnly)
        override fun isProfileAccessible(
            callingUserId: Int,
            targetUserId: Int,
            debugMsg: String,
            throwSecurityException: Boolean,
        ): Boolean = callingUserId == UserHandle.USER_SYSTEM && targetUserId == UserHandle.USER_SYSTEM
        override fun getProfileParentId(userId: Int): Int = UserHandle.USER_SYSTEM
        override fun isSettingRestrictedForUser(
            setting: String,
            userId: Int,
            value: String?,
            callingUid: Int,
        ): Boolean = false
        override fun hasUserRestriction(restriction: String, userId: Int): Boolean = false
        override fun getUserInfos(): Array<UserInfo> = users().toTypedArray()
        override fun getLauncherUserInfo(userId: Int): android.content.pm.LauncherUserInfo? = null
        override fun setDefaultCrossProfileIntentFilters(parentUserId: Int, profileUserId: Int) {}
        override fun shouldIgnorePrepareStorageErrors(userId: Int): Boolean = true
        override fun getUserProperties(userId: Int): android.content.pm.UserProperties? = null
        override fun assignUserToDisplayOnStart(
            userId: Int,
            profileGroupId: Int,
            userStartMode: Int,
            displayId: Int,
        ): Int = USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE
        override fun assignUserToExtraDisplay(userId: Int, displayId: Int): Boolean =
            userId == UserHandle.USER_SYSTEM
        override fun unassignUserFromDisplayOnStop(userId: Int) {}
        override fun unassignUserFromExtraDisplay(userId: Int, displayId: Int): Boolean = false
        override fun isUserVisible(userId: Int): Boolean = lifecycle.isRunning(userId)
        override fun isUserVisible(userId: Int, displayId: Int): Boolean =
            displayId == 0 && lifecycle.isRunning(userId)
        override fun isVisibleBackgroundFullUser(userId: Int): Boolean = false
        override fun getMainDisplayAssignedToUser(userId: Int): Int = 0
        override fun getDisplaysAssignedToUser(userId: Int): IntArray? =
            if (userId == UserHandle.USER_SYSTEM) intArrayOf(0) else null
        override fun getUserAssignedToDisplay(displayId: Int): Int = UserHandle.USER_SYSTEM
        override fun addUserVisibilityListener(listener: UserVisibilityListener) {}
        override fun removeUserVisibilityListener(listener: UserVisibilityListener) {}
        override fun onSystemUserVisibilityChanged(visible: Boolean) {}
        override fun getUserTypesForStatsd(userIds: IntArray): IntArray = IntArray(userIds.size)
        override fun getMainUserId(): Int = UserHandle.USER_SYSTEM
        override fun getBootUser(waitUntilSet: Boolean): Int = UserHandle.USER_SYSTEM
        override fun getCommunalProfileId(): Int = UserHandle.USER_NULL
        override fun getSupervisingProfileId(): Int = UserHandle.USER_NULL
    }

    /** App-process hosts this session spawned, destroyed when the system process exits. */
    private val spawnedProcesses = java.util.concurrent.CopyOnWriteArrayList<Process>()

    @JvmStatic
    fun start(context: Context, stockWindows: StockWindowServices?, onBootComplete: () -> Unit) {
        com.android.server.HostedBootTasks.start()
        // Auto-spawned service hosts must not outlive the session. Each host
        // also exits on broker disconnect (HostedBinderRuntime), but destroy
        // any survivors when the system process itself goes down.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                for (process in spawnedProcesses) {
                    if (process.isAlive) process.destroy()
                }
            },
        )
        android.os.ServiceManager.addService(Context.INPUT_METHOD_SERVICE, HostedInputMethodManagerService(context))
        android.os.ServiceManager.addService(HostedWindowManagerService.SURFACE_SYNC_SERVICE,
            HostedWindowManagerService(com.android.server.wm.HostedSurfaceSyncGroups()))
        android.os.ServiceManager.addService(
            Context.ACCESSIBILITY_SERVICE,
            HostedAccessibilityManagerService(context),
        )
        val manager = SystemServiceManager(context)
        LocalServices.addService(SystemServiceManager::class.java, manager)
        val userLifecycle = HostedUserLifecycle()
        LocalServices.addService(UserManagerInternal::class.java, HostedUserManagerInternal(context, userLifecycle))
        // Seams the reused AOSP services consult (clipboard first): window focus,
        // URI grants, and the IUserManager binder.
        if (stockWindows == null) LocalServices.addService(
            com.android.server.wm.WindowManagerInternal::class.java,
            Class.forName("com.android.server.wm.HostedLegacyWindowManagerInternal")
                .getConstructor(java.util.function.IntPredicate::class.java)
                .newInstance(java.util.function.IntPredicate { true }) as com.android.server.wm.WindowManagerInternal,
        )
        LocalServices.addService(
            VirtualDeviceManagerInternal::class.java,
            HostedVirtualDeviceManagerInternal(),
        )
        // Identity/policy services on the real AIDL surfaces, session-global
        // (any process may ask about users or app-ops).
        android.os.ServiceManager.addService(Context.USER_SERVICE, HostedUserManagerService(userLifecycle))
        android.os.ServiceManager.addService(Context.NSD_SERVICE, HostedNsdService(context))

        val trace = TimingsTraceAndSlog()
        // Bootstrap services (source note: SystemServer.startBootstrapServices
        // starts the package manager before the activity manager). The
        // platform package "android" is framework-res.apk parsed by the real
        // AOSP parser — its manifest is the session's permission source.
        val packageManager = HostedPackageManagerService(desktopWindows = stockWindows != null,
            reservePackageUid = dev.artzig.BinderSession::reservePackageUid)
        android.os.ServiceManager.addService("package", packageManager)
        val permissionPolicy = HostedWindowPermissionPolicy(context, packageManager)
        LocalServices.addService(com.android.server.policy.PermissionPolicyInternal::class.java, permissionPolicy)
        LocalServices.addService(
            com.android.server.pm.permission.LegacyPermissionManagerInternal::class.java,
            HostedLegacyPermissionManagerInternal(packageManager),
        )
        val packageManagerInternal = Class.forName(
            "android.content.pm.HostedPackageManagerInternal",
        ).getConstructor(HostedPackageManagerService::class.java)
            .newInstance(packageManager) as PackageManagerInternal
        LocalServices.addService(PackageManagerInternal::class.java, packageManagerInternal)
        android.os.ServiceManager.addService("apexservice", HostedApexService())
        com.android.server.pm.HostedApexPackages.initialize()
        val platformCompat = com.android.server.compat.PlatformCompat(context)
        android.os.ServiceManager.addService(Context.PLATFORM_COMPAT_SERVICE, platformCompat)
        android.os.ServiceManager.addService(Context.PLATFORM_COMPAT_NATIVE_SERVICE,
            com.android.server.compat.PlatformCompatNative(platformCompat))
        val activityManager = HostedActivityManagerService(context)
        activityManager.platformCompat = platformCompat
        activityManager.processLauncher = sessionProcessLauncher(activityManager)
        activityManager.packageService = packageManager
        android.os.ServiceManager.addService(Context.ACTIVITY_SERVICE, activityManager)
        // C++ PermissionCache uses android.os.IPermissionController over these same authorities.
        android.os.ServiceManager.addService("permission",
            HostedNativePermissionController(context, activityManager))
        android.os.ServiceManager.addService(Context.WIFI_SERVICE,
            HostedWifiService(HostedPermissions(packageManager)))
        android.os.ServiceManager.addService(Context.COMPANION_DEVICE_SERVICE, HostedCompanionDeviceService(context))
        android.os.ServiceManager.addService(Context.VIRTUAL_DEVICE_SERVICE, HostedVirtualDeviceService(context))
        val activityManagerAccess = HostActivityManagerInternalAccess(activityManager, packageManager, userLifecycle)
        val activityManagerInternal = Class.forName("android.app.HostedActivityManagerInternal")
            .getConstructor(HostActivityManagerInternalAccess::class.java)
            .newInstance(activityManagerAccess) as android.app.ActivityManagerInternal
        LocalServices.addService(android.app.ActivityManagerInternal::class.java, activityManagerInternal)
        com.android.server.LocalManagerRegistry.addManager(
            com.android.server.pm.PackageManagerLocal::class.java,
            HostedPackageManagerLocal(packageManager))
        android.os.ServiceManager.addService(Context.SENSOR_PRIVACY_SERVICE,
            HostedSensorPrivacyService(context))
        val appOps = com.android.server.appop.HostedAppOpsBootstrap.create(context)
        activityManager.appOps = appOps
        LocalServices.addService(com.android.server.pm.permission.PermissionManagerServiceInterface::class.java,
            HostedPermissionPolicy(packageManager))
        com.android.server.pm.permission.PermissionManagerService.create(context, android.util.ArrayMap())
        android.os.ServiceManager.addService(Context.FINGERPRINT_SERVICE,
            HostedFingerprintService(HostedPermissions(packageManager), appOps))
        // PMS creates permission services before scanning its packages.
        // PackageParser2 asks PermissionManager for split permissions even for
        // framework-res and system-image APKs; publishing later loses that input.
        packageManager.installPlatform(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")) { "ARTZIG_FRAMEWORK_RES is not set" },
        )
        // Pinned PMS scans system-image APKs before publishing packages. The host
        // application bundle places them next to res/, with ordinary package UIDs.
        val imageApps = java.io.File(java.io.File(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))).parentFile.parentFile, "apps")
        imageApps.listFiles()?.filter { it.isFile && it.extension == "apk" }
            ?.sortedBy { it.name }?.forEach { packageManager.installSystemPackage(it.canonicalPath) }
        if (stockWindows != null) {
            stockWindows.startTasks(manager)
        } else {
            val activityTaskManager = HostedActivityTaskManagerService(
                context,
                activityManager,
                packageManager,
            )
            activityManager.activityTaskManager = activityTaskManager
            android.os.ServiceManager.addService(Context.ACTIVITY_TASK_SERVICE, activityTaskManager)
        }
        android.os.ServiceManager.addService("hosted_installer", HostedInstallerService(packageManager, activityManager))
        if (dev.artzig.testing.ProcessInspection.enabled) {
            android.os.ServiceManager.addService(dev.artzig.testing.ProcessInspection.SERVICE,
                HostedInspectionRegistry(activityManager, { sequence -> stockWindows?.testInputIdle(sequence) == true }) { token, uid, pid ->
                    stockWindows?.taskForObservedWindow(token, uid, pid)
                })
        }
        // Observer registry and the settings provider, registered before any
        // service that reads settings during startup.
        android.os.ServiceManager.addService("content", HostedContentService())
        // Stock stats-manager caches pull callbacks while the native statsd daemon is absent.
        android.os.ServiceManager.addService("statsmanager", com.android.server.stats.StatsManagerService(context))
        manager.startService(com.android.server.uri.UriGrantsManagerService.Lifecycle::class.java)
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
        val power = HostedPowerManagerService()
        android.os.ServiceManager.addService(Context.POWER_SERVICE, power)
        LocalServices.addService(android.os.PowerManagerInternal::class.java,
            Class.forName("android.os.HostedPowerManagerInternal")
                .getConstructor(HostedPowerManagerService::class.java)
                .newInstance(power) as android.os.PowerManagerInternal)
        android.os.ServiceManager.addService(Context.THERMAL_SERVICE, HostedThermalService())
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
        val usageStats = HostedUsageStatsService(stockActivities = stockWindows != null)
        usageStats.packageService = packageManager
        if (stockWindows == null) activityManager.processObserver = { packageName, _, foreground ->
            usageStats.reportProcessState(packageName, foreground)
        }
        android.os.ServiceManager.addService(Context.USAGE_STATS_SERVICE, usageStats)
        if (stockWindows != null) {
            val usageLocal = Class.forName("android.app.usage.HostedUsageStatsManagerInternal")
                .getConstructor(HostedUsageStatsService::class.java)
                .newInstance(usageStats) as android.app.usage.UsageStatsManagerInternal
            LocalServices.addService(android.app.usage.UsageStatsManagerInternal::class.java, usageLocal)
            stockWindows.setUsageStats(usageLocal)
        }
        val storageStats = HostedStorageStatsService()
        storageStats.packageService = packageManager
        android.os.ServiceManager.addService(Context.STORAGE_STATS_SERVICE, storageStats)
        // AOSP StorageManager uses the historical ServiceManager name "mount";
        // Context.STORAGE_SERVICE is the Context wrapper name, not the transport.
        android.os.ServiceManager.addService("mount", HostedStorageManagerService(packageManager))
        android.os.ServiceManager.addService(
            Context.DEVICE_POLICY_SERVICE,
            HostedDevicePolicyManagerService(),
        )
        android.os.ServiceManager.addService(Context.STATUS_BAR_SERVICE, HostedStatusBarService())
        android.os.ServiceManager.addService(Context.INCIDENT_SERVICE, HostedIncidentManagerService())
        android.os.ServiceManager.addService(Context.INCIDENT_COMPANION_SERVICE, HostedIncidentCompanionService())
        android.os.ServiceManager.addService(Context.DEVICE_IDLE_CONTROLLER, HostedDeviceIdleController())
        // Audio capability/session policy and media services; rendering stays in the native backend.
        android.os.ServiceManager.addService(HostedAudioIds.SERVICE, HostedAudioIdService())
        android.os.ServiceManager.addService(Context.AUDIO_SERVICE, HostedAudioService(android.os.PermissionEnforcer.fromContext(context), context))
        android.os.ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, HostedMediaRouterService(context, packageManager))
        val mediaSessions = HostedMediaSessionService()
        mediaSessions.packageService = packageManager
        android.os.ServiceManager.addService(Context.MEDIA_SESSION_SERVICE, mediaSessions)
        android.os.ServiceManager.addService(Context.MEDIA_METRICS_SERVICE, HostedMediaMetricsService())
        android.os.ServiceManager.addService("media.extractor", nativeCreateMediaExtractorService())
        // Phase 4: location (no providers), lights (none), color display (no transforms);
        // sensors are the in-process SystemSensorManager seam, input-device managers ride
        // the hosted input service.
        val location = HostedLocationManagerService()
        location.packageService = packageManager
        android.os.ServiceManager.addService(Context.LOCATION_SERVICE, location)
        android.os.ServiceManager.addService(Context.LIGHTS_SERVICE, HostedLightsService())
        if (stockWindows != null) {
            // ActivityRecord attaches its per-app color transform to this stock
            // local service; Binder and LocalServices must share its controller.
            manager.startService(com.android.server.display.color.ColorDisplayService::class.java)
        } else {
            android.os.ServiceManager.addService(Context.COLOR_DISPLAY_SERVICE, HostedColorDisplayService())
        }
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
        if (stockWindows != null) {
            manager.startService(com.android.server.dreams.DreamManagerService::class.java)
            manager.startService("com.android.server.UiModeManagerService")
            // ATMS owns package overrides, configuration delivery and persistence.
            manager.startService(com.android.server.grammaticalinflection.GrammaticalInflectionService::class.java)
        } else {
            android.os.ServiceManager.addService(Context.UI_MODE_SERVICE, HostedUiModeManagerService(context, activityManager, hostedPermissions))
            val grammaticalInflection = HostedGrammaticalInflectionService(activityManager, hostedPermissions)
            android.os.ServiceManager.addService(Context.GRAMMATICAL_INFLECTION_SERVICE, grammaticalInflection)
            LocalServices.addService(com.android.server.grammaticalinflection.GrammaticalInflectionManagerInternal::class.java,
                grammaticalInflection.localService)
        }
        android.os.ServiceManager.addService(Context.TRUST_SERVICE, HostedTrustManagerService(hostedPermissions))
        android.os.ServiceManager.addService("lock_settings", HostedLockSettingsService(hostedPermissions))
        android.os.ServiceManager.addService(Context.SEARCH_SERVICE, HostedSearchManagerService(context, packageManager))
        android.os.ServiceManager.addService(Context.DROPBOX_SERVICE, HostedDropBoxManagerService(context, hostedPermissions))
        android.os.ServiceManager.addService(Context.OVERLAY_SERVICE, HostedOverlayManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.DOMAIN_VERIFICATION_SERVICE, HostedDomainVerificationService(packageManager, hostedPermissions))
        android.os.ServiceManager.addService(Context.WALLPAPER_SERVICE, HostedWallpaperManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.CREDENTIAL_SERVICE, HostedCredentialManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.SHORTCUT_SERVICE, HostedShortcutService(context, hostedPermissions))
        val launcherApps = HostedLauncherAppsService(context, packageManager, hostedPermissions)
        android.os.ServiceManager.addService(Context.LAUNCHER_APPS_SERVICE, launcherApps)
        LocalServices.addService(HostedLauncherAppsService::class.java, launcherApps)
        android.os.ServiceManager.addService(Context.APPWIDGET_SERVICE, HostedAppWidgetService(context, hostedPermissions))
        stockWindows?.startWindows(manager, activityManager)
        // Reused AOSP clipboard service (publishes "clipboard" itself).
        manager.startService(com.android.server.clipboard.ClipboardService::class.java)
        manager.startService(com.android.server.NetworkScoreService.Lifecycle::class.java)
        manager.startService(com.android.server.texttospeech.TextToSpeechManagerService::class.java)
        // SystemServer registers compatibility observers after the activity and
        // content services can receive package broadcasts and settings changes.
        platformCompat.registerPackageReceiver(context)
        platformCompat.registerContentObserver()
        manager.preSystemReady()
        appOps.systemReady()
        manager.startBootPhase(trace, SystemService.PHASE_SYSTEM_SERVICES_READY)
        LocalServices.getService(com.android.server.uri.UriGrantsManagerInternal::class.java).onSystemReady()
        // Pinned AMS.systemReady marks its state before the phase callback.
        activityManagerAccess.setSystemReady()
        stockWindows?.systemReady()
        manager.startBootPhase(trace, SystemService.PHASE_ACTIVITY_MANAGER_READY)
        com.android.server.webkit.HostedWebViewUpdateService(context, hostedPermissions).start()
        manager.startBootPhase(trace, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
        userLifecycle.start(object : HostedUserLifecycle.Services {
            override fun starting(userId: Int) {
                manager.onUserStarting(trace, userId)
                permissionPolicy.startUser(userId)
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
                stockWindows?.enableScreenAfterBoot(activityManager.booted)
                manager.startBootPhase(trace, SystemService.PHASE_BOOT_COMPLETED)
                // BatteryService publishes its sticky broadcast once the broadcast queue is up.
                battery.start()
                stockWindows?.verifyStartup()
                android.os.SystemProperties.set("sys.azk.boot_completed", "1")
                Log.i(TAG, "system services started for " + context.packageName)
                onBootComplete()
            })
        }
        if (stockWindows != null) stockWindows.bootCompleted()
        else activityManager.bootAnimationComplete() // Legacy presentation has no boot animation.
    }

    /**
     * Spawns `<launcher> --join <session-dir> --app-process <apk>` so a
     * service in a package with no running process gets one. The launcher and
     * session directory come from the environment the launcher gave us.
     */
    private fun sessionProcessLauncher(activityManager: HostedActivityManagerService): HostedProcessStarts.Launcher? {
        val sessionDir = System.getenv("AZK_BINDER_SESSION_DIR") ?: return null
        val launcher = System.getenv("AZK_LAUNCHER_EXE") ?: return null
        return HostedProcessStarts.Launcher { request, onExit ->
            val (packageName, processName) = request.key
            try {
                val builder = ProcessBuilder(launcher, "--join", sessionDir, "--app-process", request.apkPath)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                // Never hand the system identity to an app process: with the
                // system cookie an APK could reconnect as uid 1000 and claim
                // the system service names.
                builder.environment().remove("ARTZIG_SYSTEM_DATA_DIR")
                builder.environment().remove("AZK_BINDER_SYSTEM_COOKIE")
                builder.environment()["AZK_BINDER_PACKAGE"] = packageName
                builder.environment()["AZK_APP_PROCESS_NAME"] = processName
                builder.environment()["AZK_APP_START_SEQUENCE"] = request.sequence.toString()
                val child = builder.start()
                spawnedProcesses.add(child)
                try {
                    Thread({
                        child.waitFor()
                        spawnedProcesses.remove(child)
                        activityManager.dispatchProcessExit(onExit)
                    }, "app-exit-${request.sequence}").apply { isDaemon = true; start() }
                } catch (failure: Throwable) {
                    child.destroy()
                    spawnedProcesses.remove(child)
                    throw failure
                }
                Log.i(TAG, "spawning $processName start=${request.sequence}")
                object : HostedProcessStarts.Child {
                    override fun terminate() { child.destroy() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot spawn process for " + packageName, t)
                null
            }
        }
    }
}
