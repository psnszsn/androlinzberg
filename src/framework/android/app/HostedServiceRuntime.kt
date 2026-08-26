package android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * In-process adaptation of ActivityThread service creation and lifecycle.
 * AMS normally owns service records and scheduling; the hosted process has no
 * AMS, so Context and the APK classloader form the replacement seam.
 */
object HostedServiceRuntime {
    private const val TAG = "HostedServiceRuntime"

    private data class Record(
        val service: Service,
        var binder: IBinder? = null,
        var bound: Boolean = false,
        var startId: Int = 0,
        val connections: MutableList<ServiceConnection> = mutableListOf(),
    )

    private var context: Context? = null
    private var classLoader: ClassLoader? = null
    private val records = linkedMapOf<String, Record>()

    @JvmStatic
    fun configure(processContext: Context?, apkClassLoader: ClassLoader?) {
        check(records.isEmpty()) { "cannot replace the service host while services are running" }
        context = processContext
        classLoader = apkClassLoader
    }

    @JvmStatic
    fun isConfigured(): Boolean = context != null && classLoader != null

    @JvmStatic
    fun startService(intent: Intent): ComponentName? {
        val component = resolve(intent) ?: run {
            Log.w(TAG, "startService could not resolve $intent")
            return null
        }
        return try {
            val record = serviceFor(component)
            record.startId += 1
            record.service.onStartCommand(intent, 0, record.startId)
            component
        } catch (throwable: Throwable) {
            Log.w(TAG, "startService failed: $component", throwable)
            null
        }
    }

    @JvmStatic
    fun stopService(intent: Intent): Boolean {
        val component = resolve(intent) ?: return false
        val record = records.remove(component.className) ?: return false
        try {
            record.service.onDestroy()
        } catch (throwable: Throwable) {
            Log.w(TAG, "service onDestroy failed: $component", throwable)
        }
        Log.i(TAG, "service stopped: ${component.className}")
        return true
    }

    @JvmStatic
    fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val component = resolve(intent) ?: run {
            Log.w(TAG, "bindService could not resolve $intent")
            return false
        }
        return try {
            val record = serviceFor(component)
            if (!record.bound) {
                record.binder = record.service.onBind(intent)
                record.bound = true
            }
            record.connections += connection
            val binder = record.binder
            Handler(Looper.getMainLooper()).post {
                connection.onServiceConnected(component, binder)
            }
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "bindService failed: $component", throwable)
            false
        }
    }

    @JvmStatic
    fun unbindService(connection: ServiceConnection) {
        records.values.forEach { it.connections.remove(connection) }
    }

    @JvmStatic
    fun shutdown() {
        val running = records.values.toList()
        records.clear()
        running.forEach { record ->
            try {
                record.service.onDestroy()
            } catch (throwable: Throwable) {
                Log.w(TAG, "service shutdown failed: ${record.service.javaClass.name}", throwable)
            }
            record.connections.clear()
            record.binder = null
        }
        context = null
        classLoader = null
    }

    private fun serviceFor(component: ComponentName): Record =
        records.getOrPut(component.className) {
            val service = checkNotNull(classLoader)
                .loadClass(component.className)
                .getDeclaredConstructor()
                .newInstance() as Service
            service.`attach$artzig`(checkNotNull(context), component.className)
            service.onCreate()
            Log.i(TAG, "service created: ${component.className}")
            Record(service)
        }

    private fun resolve(intent: Intent): ComponentName? {
        intent.component?.let { return it }
        val processContext = context ?: return null
        val resolved = processContext.packageManager.resolveService(intent, 0)?.serviceInfo
            ?: return null
        return ComponentName(resolved.packageName, resolved.name)
    }
}
