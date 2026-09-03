package dev.artzig.system

import android.content.Context
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
        override fun getUserInfo(userId: Int): UserInfo? =
            UserManager.get(context).users.firstOrNull { it.id == userId }
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
        // The system process has its own (headless) logical display and no input
        // devices beyond the virtual keyboard; both names are per-process.
        android.os.ServiceManager.addProcessLocalService(Context.DISPLAY_SERVICE, HostedDisplayManagerService())
        android.os.ServiceManager.addProcessLocalService(Context.INPUT_SERVICE, HostedInputManagerService())
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
        android.os.ServiceManager.addService("permission_checker", HostedPermissionCheckerService(packageManager))
        LocalServices.addService(
            android.content.pm.PackageManagerInternal::class.java,
            object : android.content.pm.PackageManagerInternal() {
                override fun isSameApp(packageName: String?, flags: Long, uid: Int, userId: Int): Boolean {
                    if (uid == android.os.Process.SYSTEM_UID) return true
                    // A uid with no registered package cannot be verified (test
                    // peers and system contexts); only a claim that contradicts
                    // a registered uid is rejected.
                    val packages = packageManager.getPackagesForUid(uid)
                    return packages == null || packages.isEmpty() || packages.contains(packageName)
                }
            },
        )
        val activityManager = HostedActivityManagerService()
        activityManager.processLauncher = sessionProcessLauncher()
        activityManager.packageService = packageManager
        appOps.packageService = packageManager
        android.os.ServiceManager.addService(Context.ACTIVITY_SERVICE, activityManager)
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
