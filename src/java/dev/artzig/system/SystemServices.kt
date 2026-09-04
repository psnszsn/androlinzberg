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

    /** Single hosted user; `SystemServiceManager` asks for it on user start. */
    private class HostedUserManagerInternal(private val context: Context) : UserManagerInternal() {
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
        override fun isUserUnlockingOrUnlocked(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun isUserUnlocked(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun isUserRunning(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
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
        override fun isUserVisible(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun isUserVisible(userId: Int, displayId: Int): Boolean = userId == UserHandle.USER_SYSTEM
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

    /** Every hosted window is its own focused Wayland toplevel. */
    private class HostedWindowManagerInternal : com.android.server.wm.WindowManagerInternal() {
        override fun isUidFocused(uid: Int): Boolean = true
        override fun getTopFocusedDisplayId(): Int = 0
    }

    /** URI grants are not enforced between hosted processes. */
    private class HostedUriGrantsManagerInternal : com.android.server.uri.UriGrantsManagerInternal {
        override fun newUriPermissionOwner(name: String?): android.os.IBinder = android.os.Binder()
        override fun checkGrantUriPermission(
            callingUid: Int,
            targetPkg: String?,
            uri: android.net.Uri?,
            modeFlags: Int,
            userId: Int,
        ): Int = callingUid
        override fun revokeUriPermissionFromOwner(
            owner: android.os.IBinder?,
            uri: android.net.Uri?,
            mode: Int,
            userId: Int,
        ) {}
    }

    /** App-process hosts this session spawned, destroyed when the system process exits. */
    private val spawnedProcesses = java.util.concurrent.CopyOnWriteArrayList<Process>()

    @JvmStatic
    fun start(context: Context) {
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
        android.os.ServiceManager.addService(Context.INPUT_METHOD_SERVICE, HostedInputMethodManagerService())
        val manager = SystemServiceManager(context)
        LocalServices.addService(SystemServiceManager::class.java, manager)
        LocalServices.addService(UserManagerInternal::class.java, HostedUserManagerInternal(context))
        // Seams the reused AOSP services consult (clipboard first): window focus,
        // URI grants, and the IUserManager binder.
        LocalServices.addService(
            com.android.server.wm.WindowManagerInternal::class.java,
            HostedWindowManagerInternal(),
        )
        LocalServices.addService(
            com.android.server.uri.UriGrantsManagerInternal::class.java,
            HostedUriGrantsManagerInternal(),
        )
        // Identity/policy services on the real AIDL surfaces, session-global
        // (any process may ask about users or app-ops).
        android.os.ServiceManager.addService(Context.USER_SERVICE, HostedUserManagerService())
        val appOps = HostedAppOpsService()
        android.os.ServiceManager.addService("appops", appOps)

        val trace = TimingsTraceAndSlog()
        // Bootstrap services (source note: SystemServer.startBootstrapServices
        // starts the package manager before the activity manager). The
        // platform package "android" is framework-res.apk parsed by the real
        // AOSP parser — its manifest is the session's permission source.
        val packageManager = HostedPackageManagerService()
        packageManager.installPlatform(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")) { "ARTZIG_FRAMEWORK_RES is not set" },
        )
        android.os.ServiceManager.addService("package", packageManager)
        val packageManagerInternal = Class.forName(
            "android.content.pm.HostedPackageManagerInternal",
        ).getConstructor(HostedPackageManagerService::class.java)
            .newInstance(packageManager) as PackageManagerInternal
        LocalServices.addService(PackageManagerInternal::class.java, packageManagerInternal)
        android.os.ServiceManager.addService("permission_checker", HostedPermissionCheckerService(packageManager))
        android.os.ServiceManager.addService(
            Context.PLATFORM_COMPAT_SERVICE,
            HostedPlatformCompatService(),
        )
        val activityManager = HostedActivityManagerService()
        activityManager.processLauncher = sessionProcessLauncher()
        activityManager.packageService = packageManager
        appOps.packageService = packageManager
        android.os.ServiceManager.addService(Context.ACTIVITY_SERVICE, activityManager)
        val activityTaskManager = HostedActivityTaskManagerService(
            context,
            activityManager,
            packageManager,
        )
        activityManager.activityTaskManager = activityTaskManager
        android.os.ServiceManager.addService(Context.ACTIVITY_TASK_SERVICE, activityTaskManager)
        android.os.ServiceManager.addService("hosted_installer", HostedInstallerService(packageManager, activityManager))
        // Reused services resolve users through ActivityManagerInternal.
        LocalServices.addService(
            android.app.ActivityManagerInternal::class.java,
            object : android.app.ActivityManagerInternal() {
                // android 16: process-state query; hosted apps run foreground.
                override fun getUidProcessState(uid: Int): Int =
                    2 // ActivityManager.PROCESS_STATE_TOP (@hide, not in the hosted surface)

                override fun handleIncomingUser(
                    callingPid: Int,
                    callingUid: Int,
                    userId: Int,
                    allowAll: Boolean,
                    allowMode: Int,
                    name: String?,
                    callerPackage: String?,
                ): Int = UserHandle.USER_SYSTEM
            },
        )
        // Observer registry and the settings provider, registered before any
        // service that reads settings during startup.
        android.os.ServiceManager.addService("content", HostedContentService())
        android.os.ServiceManager.addService("uri_grants", HostedUriGrantsService())
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
        android.os.ServiceManager.addService(Context.POWER_SERVICE, HostedPowerManagerService())
        android.os.ServiceManager.addService(Context.THERMAL_SERVICE, HostedThermalService())
        android.os.ServiceManager.addService(Context.VIBRATOR_MANAGER_SERVICE, HostedVibratorManagerService())
        val battery = HostedBatteryService(activityManager)
        android.os.ServiceManager.addService("batteryproperties", battery.registrar)
        android.os.ServiceManager.addService(android.os.BatteryStats.SERVICE_NAME, battery.batteryStats)
        val usageStats = HostedUsageStatsService()
        usageStats.packageService = packageManager
        activityManager.processObserver = { packageName, _, foreground -> usageStats.reportProcessState(packageName, foreground) }
        android.os.ServiceManager.addService(Context.USAGE_STATS_SERVICE, usageStats)
        val storageStats = HostedStorageStatsService()
        storageStats.packageService = packageManager
        android.os.ServiceManager.addService(Context.STORAGE_STATS_SERVICE, storageStats)
        // AOSP StorageManager uses the historical ServiceManager name "mount";
        // Context.STORAGE_SERVICE is the Context wrapper name, not the transport.
        android.os.ServiceManager.addService("mount", HostedStorageManagerService(packageManager))
        android.os.ServiceManager.addService(Context.STATUS_BAR_SERVICE, HostedStatusBarService())
        android.os.ServiceManager.addService(Context.INCIDENT_SERVICE, HostedIncidentManagerService())
        android.os.ServiceManager.addService(Context.INCIDENT_COMPANION_SERVICE, HostedIncidentCompanionService())
        android.os.ServiceManager.addService(Context.DEVICE_IDLE_CONTROLLER, HostedDeviceIdleController())
        // Phase 3: media sessions and media metrics (the audio service stays pending, see docs/stubs.md).
        val mediaSessions = HostedMediaSessionService()
        mediaSessions.packageService = packageManager
        android.os.ServiceManager.addService(Context.MEDIA_SESSION_SERVICE, mediaSessions)
        android.os.ServiceManager.addService(Context.MEDIA_METRICS_SERVICE, HostedMediaMetricsService())
        // Phase 4: location (no providers), lights (none), color display (no transforms);
        // sensors are the in-process SystemSensorManager seam, input-device managers ride
        // the hosted input service.
        val location = HostedLocationManagerService()
        location.packageService = packageManager
        android.os.ServiceManager.addService(Context.LOCATION_SERVICE, location)
        android.os.ServiceManager.addService(Context.LIGHTS_SERVICE, HostedLightsService())
        android.os.ServiceManager.addService(Context.COLOR_DISPLAY_SERVICE, HostedColorDisplayService())
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
        android.os.ServiceManager.addService(Context.UI_MODE_SERVICE, HostedUiModeManagerService(context, activityManager, hostedPermissions))
        android.os.ServiceManager.addService(
            Context.GRAMMATICAL_INFLECTION_SERVICE,
            HostedGrammaticalInflectionService(activityManager, hostedPermissions),
        )
        android.os.ServiceManager.addService(Context.TRUST_SERVICE, HostedTrustManagerService(hostedPermissions))
        android.os.ServiceManager.addService("lock_settings", HostedLockSettingsService(hostedPermissions))
        android.os.ServiceManager.addService(Context.SEARCH_SERVICE, HostedSearchManagerService(context, packageManager))
        android.os.ServiceManager.addService(Context.DROPBOX_SERVICE, HostedDropBoxManagerService(context, hostedPermissions))
        android.os.ServiceManager.addService(Context.OVERLAY_SERVICE, HostedOverlayManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.DOMAIN_VERIFICATION_SERVICE, HostedDomainVerificationService(packageManager, hostedPermissions))
        android.os.ServiceManager.addService(Context.WALLPAPER_SERVICE, HostedWallpaperManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.CREDENTIAL_SERVICE, HostedCredentialManagerService(hostedPermissions))
        android.os.ServiceManager.addService(Context.SHORTCUT_SERVICE, HostedShortcutService(context, hostedPermissions))
        android.os.ServiceManager.addService(Context.LAUNCHER_APPS_SERVICE, HostedLauncherAppsService(context, packageManager, hostedPermissions))
        android.os.ServiceManager.addService(Context.APPWIDGET_SERVICE, HostedAppWidgetService(context, hostedPermissions))
        // Reused AOSP clipboard service (publishes "clipboard" itself).
        manager.startService(com.android.server.clipboard.ClipboardService::class.java)
        manager.preSystemReady()
        manager.startBootPhase(trace, SystemService.PHASE_SYSTEM_SERVICES_READY)
        manager.startBootPhase(trace, SystemService.PHASE_ACTIVITY_MANAGER_READY)
        manager.startBootPhase(trace, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
        manager.startBootPhase(trace, SystemService.PHASE_BOOT_COMPLETED)
        manager.onUserStarting(trace, UserHandle.USER_SYSTEM)
        // BatteryService publishes its sticky broadcast once the broadcast queue is up.
        battery.start()
        // Session-wide marker: app processes (system-test) observe a property
        // set by the system process through the shared area.
        android.os.SystemProperties.set("sys.azk.boot_completed", "1")
        Log.i(TAG, "system services started for " + context.packageName)
    }

    /**
     * Spawns `<launcher> --join <session-dir> --app-process <apk>` so a
     * service in a package with no running process gets one. The launcher and
     * session directory come from the environment the launcher gave us.
     */
    private fun sessionProcessLauncher(): HostedActivityManagerService.ProcessLauncher? {
        val sessionDir = System.getenv("AZK_BINDER_SESSION_DIR") ?: return null
        val launcher = System.getenv("AZK_LAUNCHER_EXE") ?: return null
        return HostedActivityManagerService.ProcessLauncher { packageName, apkPath ->
            try {
                val builder = ProcessBuilder(launcher, "--join", sessionDir, "--app-process", apkPath)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                // Never hand the system identity to an app process: with the
                // system cookie an APK could reconnect as uid 1000 and claim
                // the system service names.
                builder.environment().remove("AZK_BINDER_SYSTEM_COOKIE")
                spawnedProcesses.add(builder.start())
                Log.i(TAG, "spawning process for " + packageName)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "cannot spawn process for " + packageName, t)
                false
            }
        }
    }
}
