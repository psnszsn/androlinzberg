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

    /** The single-user answers the reused services ask IUserManager for. */
    private class HostedUserManagerService(private val context: Context) :
        android.os.Binder(), android.os.IUserManager {
        override fun isUserRunning(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
        override fun getProfiles(userId: Int, enabledOnly: Boolean): List<UserInfo> =
            UserManager.get(context).users
        override fun hasUserRestriction(restrictionKey: String?, userId: Int): Boolean = false
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
        android.os.ServiceManager.addProcessLocalService(
            Context.USER_SERVICE,
            HostedUserManagerService(context),
        )

        val trace = TimingsTraceAndSlog()
        // Bootstrap services (source note: SystemServer.startBootstrapServices
        // starts the package manager before the activity manager).
        val packageManager = HostedPackageManagerService()
        android.os.ServiceManager.addService("package", packageManager)
        LocalServices.addService(
            android.content.pm.PackageManagerInternal::class.java,
            object : android.content.pm.PackageManagerInternal() {
                override fun isSameApp(packageName: String?, flags: Long, uid: Int, userId: Int): Boolean {
                    if (uid == android.os.Process.SYSTEM_UID) return true
                    // A uid with no registered package cannot be verified (test
                    // peers and system contexts); only a claim that contradicts
                    // a registered uid is rejected.
                    val packages = packageManager.getPackagesForUid(uid)
                    return packages.isEmpty() || packages.contains(packageName)
                }
            },
        )
        val activityManager = HostedActivityManagerService()
        activityManager.processLauncher = sessionProcessLauncher()
        activityManager.packageService = packageManager
        android.os.ServiceManager.addService(Context.ACTIVITY_SERVICE, activityManager)
        // Reused AOSP clipboard service (publishes "clipboard" itself).
        manager.startService(com.android.server.clipboard.ClipboardService::class.java)
        manager.preSystemReady()
        manager.startBootPhase(trace, SystemService.PHASE_SYSTEM_SERVICES_READY)
        manager.startBootPhase(trace, SystemService.PHASE_ACTIVITY_MANAGER_READY)
        manager.startBootPhase(trace, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
        manager.startBootPhase(trace, SystemService.PHASE_BOOT_COMPLETED)
        manager.onUserStarting(trace, UserHandle.USER_SYSTEM)
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
