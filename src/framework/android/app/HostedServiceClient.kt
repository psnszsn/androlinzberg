package android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import dev.artzig.system.IHostedInstaller

/**
 * This process's client side of the activity manager: the real
 * `IActivityManager` proxy, the process's `IApplicationThread`, and the
 * `ServiceConnection` dispatch (via [ServiceDispatcher]).
 *
 * Source note: the `ContextImpl.bindServiceCommon` / `startService` path in
 * AOSP, reduced to the hosted interface.
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
    fun attachApplication(packageName: String, processName: String = packageName) {
        if (attached) return
        val am = activityManagerOrNull() ?: return
        try {
            installerOrNull()?.bindProcessPackage(packageName, processName)
            am.attachApplication(thread, 0)
            attached = true
        } catch (e: RemoteException) {
            Log.w(TAG, "attachApplication failed", e)
        }
    }

    /** Publishes this process's installed providers (AOSP ordering: attach ->
     * install -> publish -> Application.onCreate; the application runtime
     * calls this between install and onCreate). */
    @JvmStatic
    fun publishInstalledProviders() {
        val am = activityManagerOrNull() ?: return
        val holders = HostedApplicationRuntime.installedProviderHolders()
        if (holders.isEmpty()) return
        try {
            am.publishContentProviders(thread, holders)
        } catch (e: RemoteException) {
            Log.w(TAG, "publishContentProviders failed", e)
        }
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
        HostedApplicationRuntime.localProvider(authority)?.let { return it.getIContentProvider() }
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
        HostedApplicationRuntime.localProvider(authority)?.let { return it.getIContentProvider() }
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

    @JvmStatic
    fun startService(intent: Intent): ComponentName? {
        val am = activityManagerOrNull() ?: return null
        return try {
            am.startService(thread, intent, null, false, ActivityThread.currentPackageName(), null, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "startService failed", e)
            null
        }
    }

    @JvmStatic
    fun stopService(intent: Intent): Boolean {
        val am = activityManagerOrNull() ?: return false
        return try {
            am.stopService(thread, intent, null, 0) != 0
        } catch (e: RemoteException) {
            false
        }
    }

    @JvmStatic
    fun bindService(context: Context, intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val am = activityManagerOrNull() ?: return false
        val dispatcher = ServiceDispatcher.forConnection(connection, context)
        return try {
            val res = am.bindServiceInstance(
                thread, null, intent, null, dispatcher.stub, flags.toLong(), null, ActivityThread.currentPackageName(), 0,
            )
            if (res < 0) throw SecurityException("Not allowed to bind to service " + intent)
            res != 0
        } catch (e: RemoteException) {
            Log.w(TAG, "bindService failed", e)
            false
        }
    }

    @JvmStatic
    fun unbindService(connection: ServiceConnection) {
        val dispatcher = ServiceDispatcher.forget(connection) ?: return
        val am = activityManagerOrNull() ?: return
        try {
            am.unbindService(dispatcher.stub)
        } catch (e: RemoteException) {
        }
    }

    // ---------------------------------------------------------- broadcasts

    /** Null receiver is the sticky-query form; returns the first matching sticky. */
    @JvmStatic
    fun registerReceiver(
        receiver: android.content.IIntentReceiver?,
        filter: android.content.IntentFilter,
        broadcastPermission: String?,
        flags: Int,
    ): Intent? {
        val am = activityManagerOrNull() ?: return null
        return try {
            am.registerReceiverWithFeature(
                thread, ActivityThread.currentPackageName(), null, null, receiver, filter, broadcastPermission, 0, flags,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "registerReceiver failed", e)
            null
        }
    }

    @JvmStatic
    fun unregisterReceiver(receiver: android.content.IIntentReceiver) {
        val am = activityManagerOrNull() ?: return
        try {
            am.unregisterReceiver(receiver)
        } catch (e: RemoteException) {
        }
    }

    @JvmStatic
    fun broadcastIntent(intent: Intent, requiredPermission: String?, ordered: Boolean, sticky: Boolean) {
        val am = activityManagerOrNull() ?: return
        try {
            am.broadcastIntentWithFeature(
                thread, null, intent, null, null, 0, null, null,
                requiredPermission?.let { arrayOf<String?>(it) }, null, null, AppOpsManager.OP_NONE, null, ordered, sticky, 0,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "broadcastIntent failed", e)
        }
    }

    @JvmStatic
    fun finishReceiver(
        who: android.content.IIntentReceiver,
        resultCode: Int,
        resultData: String?,
        resultExtras: android.os.Bundle?,
        abortBroadcast: Boolean,
    ) {
        val am = activityManagerOrNull() ?: return
        try {
            am.finishReceiver(who.asBinder(), resultCode, resultData, resultExtras, abortBroadcast, 0)
        } catch (e: RemoteException) {
        }
    }

    @JvmStatic
    fun removeStickyBroadcast(intent: Intent) {
        val am = activityManagerOrNull() ?: return
        try {
            am.unbroadcastIntent(thread, intent, 0)
        } catch (e: RemoteException) {
        }
    }
}
