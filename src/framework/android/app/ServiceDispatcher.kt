package android.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * Bridges a `ServiceConnection` to the activity manager's `IServiceConnection`.
 *
 * Source note: follows `android.app.LoadedApk.ServiceDispatcher` — the inner
 * `IServiceConnection.Stub`, main-handler delivery of `doConnected`/`doDeath`,
 * a `DeathMonitor` linked to the service binder for `onServiceDisconnected`,
 * `dead -> onBindingDied`, `null -> onNullBinding`, and `mForgotten` after
 * unbind. Dispatchers are cached per `ServiceConnection` identity so
 * `unbindService(conn)` finds the same stub.
 */
internal class ServiceDispatcher(
    private val connection: ServiceConnection,
    private val handler: Handler,
) {
    val stub: IServiceConnection.Stub = InnerConnection()
    @Volatile private var forgotten = false

    /** One live binding per service component: a ServiceConnection may be bound
     * to several components at once (LoadedApk.ServiceDispatcher keeps mActiveConnections). */
    private class Active(val service: IBinder, val monitor: DeathMonitor)
    private val active = HashMap<ComponentName, Active>()

    private inner class InnerConnection : IServiceConnection.Stub() {
        override fun connected(name: ComponentName?, service: IBinder?, dead: Boolean) {
            handler.post { doConnected(name, service, dead) }
        }
    }

    private inner class DeathMonitor(val name: ComponentName, val service: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() {
            handler.post { doDeath(name, service) }
        }
    }

    fun forget() {
        forgotten = true
        synchronized(this) {
            for (entry in active.values) {
                try {
                    entry.service.unlinkToDeath(entry.monitor, 0)
                } catch (e: Exception) {
                }
            }
            active.clear()
        }
    }

    private fun doConnected(name: ComponentName?, service: IBinder?, dead: Boolean) {
        if (forgotten || name == null) return
        var replaced: IBinder? = null
        synchronized(this) {
            val existing = active[name]
            if (existing != null && existing.service === service) return // already have it
            if (service != null) {
                if (existing != null) {
                    try {
                        existing.service.unlinkToDeath(existing.monitor, 0)
                    } catch (e: Exception) {
                    }
                    replaced = existing.service
                }
                val monitor = DeathMonitor(name, service)
                try {
                    service.linkToDeath(monitor, 0)
                    active[name] = Active(service, monitor)
                } catch (e: RemoteException) {
                    // The service died before we could link; deliver death below.
                    active.remove(name)
                    connection.onServiceDisconnected(name)
                    return
                }
            } else if (existing != null) {
                try {
                    existing.service.unlinkToDeath(existing.monitor, 0)
                } catch (e: Exception) {
                }
                active.remove(name)
                replaced = existing.service
            }
        }
        // AOSP LoadedApk.ServiceDispatcher.doConnected: any old binding is
        // disconnected here, so the callback fires no matter whether the
        // activity manager's dead-notify or the binder death arrives first
        // (whichever runs first also unlinks the monitor, keeping it single).
        if (replaced != null && replaced !== service) {
            connection.onServiceDisconnected(name)
        }
        if (dead) {
            connection.onBindingDied(name)
            return
        }
        if (service != null) {
            connection.onServiceConnected(name, service)
        } else {
            connection.onNullBinding(name)
        }
    }

    private fun doDeath(name: ComponentName, service: IBinder) {
        synchronized(this) {
            val existing = active[name] ?: return
            if (existing.service !== service) return
            active.remove(name)
        }
        if (!forgotten) connection.onServiceDisconnected(name)
    }

    companion object {
        private const val TAG = "ServiceDispatcher"
        private val dispatchers = java.util.IdentityHashMap<ServiceConnection, ServiceDispatcher>()

        /** One dispatcher per ServiceConnection identity, as LoadedApk keeps per context. */
        @JvmStatic
        fun forConnection(connection: ServiceConnection, context: Context): ServiceDispatcher =
            synchronized(dispatchers) {
                dispatchers.getOrPut(connection) {
                    ServiceDispatcher(connection, Handler(android.os.Looper.getMainLooper()))
                }
            }

        @JvmStatic
        fun forget(connection: ServiceConnection): ServiceDispatcher? = synchronized(dispatchers) {
            dispatchers.remove(connection)?.also { it.forget() }
        }
    }
}
