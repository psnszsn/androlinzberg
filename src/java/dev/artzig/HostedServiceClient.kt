package dev.artzig

import android.app.*

import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import dev.artzig.system.IHostedInstaller

/** Host package announcement and binding bootstrap around stock IApplicationThread.
 * Providers, components and lifecycle dispatch belong to the unchanged ActivityThread. */
object HostedServiceClient {
    private const val TAG = "HostedServiceClient"

    @Volatile private var activityManager: IActivityManager? = null
    @Volatile private var installer: IHostedInstaller? = null
    @Volatile private var attached = false

    /** This process's IApplicationThread (AOSP ActivityThread.getApplicationThread). */
    @JvmStatic
    fun applicationThread(): IApplicationThread = ActivityThread.currentActivityThread().applicationThread

    @JvmStatic
    fun activityManagerOrNull(): IActivityManager? {
        activityManager?.let { return it }
        synchronized(this) {
            activityManager?.let { return it }
            val binder = ServiceManager.waitForService(Context.ACTIVITY_SERVICE) ?: run {
                Log.w(TAG, "activity manager unavailable")
                return null
            }
            val proxy = IActivityManager.Stub.asInterface(binder)
            activityManager = proxy
            return proxy
        }
    }

    /** The host bootstrap service (APK install, process-package announcement). */
    @JvmStatic
    fun installerOrNull(): IHostedInstaller? {
        installer?.let { return it }
        synchronized(this) {
            installer?.let { return it }
            val binder = ServiceManager.waitForService("hosted_installer") ?: return null
            val proxy = IHostedInstaller.Stub.asInterface(binder)
            installer = proxy
            return proxy
        }
    }

    /** Registers this process with the activity manager as a running process
     * of the given (already installed) package: announce the package (no
     * zygote knows it), then AOSP attachApplication. */
    @JvmStatic
    @JvmOverloads
    fun attachApplication(packageName: String, processName: String = packageName): Application {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "application attachment must run on the main looper"
        }
        if (attached) return checkNotNull(ActivityThread.currentApplication())
        HostContexts.prepareApplicationEnvironment(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        val am = checkNotNull(activityManagerOrNull()) { "activity manager unavailable" }
        try {
            checkNotNull(installerOrNull()).bindProcessPackage(packageName, processName,
                android.content.res.Configuration(ResourcesManager.getInstance().configuration))
            if (ActivityThread.currentActivityThread() == null) {
                HostedActivityProcess.attachApplication()
            } else {
                // In-process tools may already have an initialized system thread. Register
                // its stock transport without attaching the thread a second time.
                val thread = applicationThread()
                com.android.internal.os.RuntimeInit.setApplicationObject(thread.asBinder())
                am.attachApplication(thread, 0)
            }
            attached = true
        } catch (e: RemoteException) {
            throw e.rethrowFromSystemServer()
        }
        // The host starts its event loop after bootstrap. Dispatch the stock
        // bindApplication callback before the launcher can submit activity work.
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        val queue = android.os.Looper.myQueue()
        while (ActivityThread.currentPackageName() != packageName &&
            android.os.SystemClock.uptimeMillis() < deadline) {
            val message = queue.`pollDueMessage$artzig`()
            if (message == null) android.os.SystemClock.sleep(1)
            else try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        check(ActivityThread.currentPackageName() == packageName) { "application binding timed out: $packageName" }
        return checkNotNull(ActivityThread.currentApplication())
    }

    /** Cross-uid permission state, answered by the session's package manager
     * (AOSP shape: PermissionManager.checkUidPermission). */
    @JvmStatic
    fun checkUidPermission(permission: String, uid: Int): Int = try {
        ActivityThread.getPackageManager().checkUidPermission(permission, uid)
    } catch (e: RemoteException) {
        Log.w(TAG, "checkUidPermission unreachable for " + permission, e)
        android.content.pm.PackageManager.PERMISSION_DENIED
    }

}
