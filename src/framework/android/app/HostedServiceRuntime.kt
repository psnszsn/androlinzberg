package android.app

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.artzig.system.IHostedActivityManager

/**
 * Token-keyed executor for the activity manager's service callbacks in this
 * process.
 *
 * Source note: follows `ActivityThread.handleCreateService`,
 * `handleBindService`, `handleUnbindService`, `handleServiceArgs`, and
 * `handleStopService` (vendored `android.app.ActivityThread`): instantiate via
 * the APK class loader, `attach`, `onCreate`; `onBind`/`onRebind` publish the
 * binder back through `publishService`; `onUnbind` returning true reports
 * `unbindFinished`; results report through `serviceDoneExecuting`. Service
 * records and connection bookkeeping live in
 * `dev.artzig.system.HostedActivityManagerService`, not here.
 */
object HostedServiceRuntime {
    private const val TAG = "HostedServiceRuntime"
    private const val SERVICE_DONE_EXECUTING_ANON = 0
    private const val SERVICE_DONE_EXECUTING_START = 1
    private const val SERVICE_DONE_EXECUTING_STOP = 2

    private class ServiceState(val service: Service, val name: ComponentName)

    private var context: android.content.Context? = null
    private var classLoader: ClassLoader? = null
    private val services = LinkedHashMap<IBinder, ServiceState>()

    @JvmStatic
    fun configure(processContext: android.content.Context?, apkClassLoader: ClassLoader?) {
        check(services.isEmpty()) { "cannot replace the service host while services are running" }
        context = processContext
        classLoader = apkClassLoader
    }

    @JvmStatic
    fun isConfigured(): Boolean = context != null && classLoader != null

    private fun activityManager(): IHostedActivityManager? = HostedServiceClient.activityManagerOrNull()

    @JvmStatic
    fun handleCreateService(token: IBinder, name: ComponentName) {
        if (services.containsKey(token)) return
        try {
            val service = checkNotNull(classLoader)
                .loadClass(name.className)
                .getDeclaredConstructor()
                .newInstance() as Service
            service.`attach$artzig`(checkNotNull(context), name.className)
            service.onCreate()
            services[token] = ServiceState(service, name)
            Log.i(TAG, "service created: " + name.className)
            activityManager()?.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_ANON, 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "unable to create service " + name.className, t)
        }
    }

    @JvmStatic
    fun handleBindService(token: IBinder, intent: Intent, rebind: Boolean) {
        val state = services[token] ?: run {
            // Creation failed earlier; publish a null binder so waiting
            // connections get onNullBinding instead of hanging forever.
            Log.w(TAG, "bind for a service that was never created")
            publishQuietly(token, intent, null)
            return
        }
        try {
            if (!rebind) {
                val binder = state.service.onBind(intent)
                activityManager()?.publishService(token, intent, binder)
            } else {
                state.service.onRebind(intent)
                activityManager()?.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_ANON, 0, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onBind failed: " + state.name, t)
            if (!rebind) publishQuietly(token, intent, null)
        }
    }

    private fun publishQuietly(token: IBinder, intent: Intent, binder: IBinder?) {
        try {
            activityManager()?.publishService(token, intent, binder)
        } catch (e: android.os.RemoteException) {
        }
    }

    @JvmStatic
    fun handleUnbindService(token: IBinder, intent: Intent) {
        val state = services[token] ?: return
        try {
            val doRebind = state.service.onUnbind(intent)
            if (doRebind) {
                activityManager()?.unbindFinished(token, intent)
            } else {
                activityManager()?.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_ANON, 0, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onUnbind failed: " + state.name, t)
        }
    }

    @JvmStatic
    fun handleServiceArgs(token: IBinder, startId: Int, flags: Int, args: Intent?) {
        val state = services[token] ?: return
        try {
            val res = state.service.onStartCommand(args, flags, startId)
            activityManager()?.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_START, startId, res)
        } catch (t: Throwable) {
            Log.w(TAG, "onStartCommand failed: " + state.name, t)
        }
    }

    @JvmStatic
    fun handleStopService(token: IBinder) {
        val state = services.remove(token) ?: return
        try {
            state.service.onDestroy()
        } catch (t: Throwable) {
            Log.w(TAG, "service onDestroy failed: " + state.name, t)
        }
        Log.i(TAG, "service stopped: " + state.name.className)
        activityManager()?.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_STOP, 0, 0)
    }

    @JvmStatic
    fun shutdown() {
        val running = services.values.toList()
        services.clear()
        running.forEach { state ->
            try {
                state.service.onDestroy()
            } catch (throwable: Throwable) {
                Log.w(TAG, "service shutdown failed: " + state.name, throwable)
            }
        }
        context = null
        classLoader = null
    }
}
