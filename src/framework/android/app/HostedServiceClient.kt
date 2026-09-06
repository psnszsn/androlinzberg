package android.app

import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import dev.artzig.system.IHostedInstaller

/**
 * This process's client side of the session's activity manager: the real
 * `IActivityManager` proxy, the process's `IApplicationThread`, the host
 * bootstrap (installer, attach), and the per-authority provider connections
 * the hosted ActivityThread.acquireProvider family rides on. Components,
 * receivers and broadcasts go through the real ContextImpl directly.
 */
object HostedServiceClient {
    private const val TAG = "HostedServiceClient"

    private val thread = HostedApplicationThread()
    @Volatile private var activityManager: IActivityManager? = null
    @Volatile private var installer: IHostedInstaller? = null
    @Volatile private var attached = false

    /** This process's IApplicationThread (AOSP ActivityThread.getApplicationThread). */
    @JvmStatic
    fun applicationThread(): IApplicationThread = thread

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
        // ActivityThread.attach installs the token before contacting AMS so
        // RuntimeInit can attribute even a failure during application binding.
        com.android.internal.os.RuntimeInit.setApplicationObject(thread.asBinder())
        val am = checkNotNull(activityManagerOrNull()) { "activity manager unavailable" }
        try {
            checkNotNull(installerOrNull()).bindProcessPackage(packageName, processName,
                android.content.res.Configuration(ResourcesManager.getInstance().configuration))
            am.attachApplication(thread, 0)
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
            else message.target.dispatchMessage(message)
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

    private val providerLock = Any()
    private val providerCache = HashMap<String, android.content.IContentProvider>()

    /** AOSP ActivityThread.acquireExistingProvider: only what this process already holds. */
    @JvmStatic
    fun existingContentProvider(authority: String): android.content.IContentProvider? {
        ActivityThread.currentActivityThread().localProvider(authority)?.let { return it.getIContentProvider() }
        synchronized(providerLock) { return providerCache[authority] }
    }

    /** AOSP ActivityThread.handleUnstableProviderDied: drop the dead connection so
     * the next acquire goes back to the activity manager. */
    @JvmStatic
    fun forgetContentProvider(binder: android.os.IBinder) {
        synchronized(providerLock) {
            providerCache.entries.removeIf { it.value.asBinder() == binder }
        }
    }

    /**
     * Authority -> provider connection (source note: ActivityThread.acquireProvider):
     * this process's own installed providers first, then the per-process cache,
     * then the activity manager (which spawns the owning package on demand).
     * Death evicts the cache entry so a re-acquire respawns.
     */
    @JvmStatic
    fun acquireContentProvider(context: Context, authority: String): android.content.IContentProvider? {
        ActivityThread.currentActivityThread().localProvider(authority)?.let { return it.getIContentProvider() }
        synchronized(providerLock) { providerCache[authority]?.let { return it } }
        val am = activityManagerOrNull() ?: return null
        val holder = try {
            am.getContentProvider(thread, context.packageName, authority, 0, true)
        } catch (e: RemoteException) {
            Log.w(TAG, "getContentProvider failed for " + authority, e)
            null
        } ?: return null
        // The holder carries the real provider transport (ContentProviderNative
        // hands back the local Transport when the binder lives in this process).
        val proxy = holder.provider ?: return null
        val binder = proxy.asBinder()
        synchronized(providerLock) {
            providerCache[authority]?.let { return it }
            providerCache[authority] = proxy
        }
        try {
            binder.linkToDeath(
                { synchronized(providerLock) { providerCache.remove(authority) } },
                0,
            )
        } catch (e: RemoteException) {
            synchronized(providerLock) { providerCache.remove(authority) }
        }
        return proxy
    }
}
