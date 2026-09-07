package dev.artzig.system

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.AdvertisingRequest
import android.net.nsd.DiscoveryRequest
import android.net.nsd.INsdManager
import android.net.nsd.INsdManagerCallback
import android.net.nsd.INsdServiceConnector
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import dev.artzig.HostedNsdBackend
import dev.artzig.AvahiHostedNsdBackend
import dev.artzig.copy
import java.io.Closeable
import java.net.NetworkInterface
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Follows pinned NsdService's connector, request maps and callback ordering.
 * Its Android mDNS socket/advertiser stack is unavailable here. Avahi owns the
 * multicast connection; the original NsdManager owns application listeners,
 * executors, validation, per-network tracking and stale callback rejection.
 */
internal class HostedNsdService(private val context: Context,
    private val backendFactory: () -> HostedNsdBackend = ::AvahiHostedNsdBackend) : INsdManager.Stub() {
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "host-nsd-service").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun connect(callback: INsdManagerCallback?, useJavaBackend: Boolean): INsdServiceConnector {
        context.enforceCallingOrSelfPermission(Manifest.permission.INTERNET, "NsdService")
        requireNotNull(callback) { "Unknown client callback from uid=${android.os.Binder.getCallingUid()}" }
        return Client(callback, backendFactory()).also {
            try { callback.asBinder().linkToDeath(it, 0) }
            catch (_: RemoteException) { it.binderDied() }
        }
    }

    private enum class Kind { DISCOVERY, RESOLUTION, INFO }
    private class Request(val kind: Kind, val info: NsdServiceInfo? = null,
        val network: Network? = null, val interfaceIndex: Int = 0) {
        var handle: Closeable? = null
        var timeout: ScheduledFuture<*>? = null
        var started = false
        var closeFailure: Exception? = null
        val found = HashSet<String>()
    }

    private inner class Client(private val callback: INsdManagerCallback,
        private val backend: HostedNsdBackend) : INsdServiceConnector.Stub(),
        INsdServiceConnector by unsupported<INsdServiceConnector>(), IBinder.DeathRecipient {
        override fun asBinder(): IBinder = this
        private val requests = HashMap<Int, Request>()
        private var dead = false // All state below belongs to worker.
        private fun submit(action: () -> Unit) { worker.execute { if (!dead) action() } }
        private fun reply(action: () -> Unit) {
            try { action() } catch (_: RemoteException) { cleanup() }
        }
        private fun remove(key: Int): Request? = requests.remove(key)?.also { request ->
            request.timeout?.cancel(false)
            try { request.handle?.close() }
            catch (failure: Exception) {
                request.closeFailure = failure
                Log.w("HostedNsdService", "Closing discovery connection", failure)
            }
        }
        private fun cleanup() {
            if (dead) return
            dead = true
            requests.keys.toList().forEach(::remove)
            callback.asBinder().unlinkToDeath(this, 0)
        }
        override fun binderDied() { worker.execute { cleanup() } }

        // Android V removed the old native-daemon start operation. Avahi is
        // contacted on demand when opening a discovery connection.
        override fun startDaemon() {}

        override fun discoverServices(key: Int, discovery: DiscoveryRequest) = submit {
            if (requests.size >= 200) {
                reply { callback.onDiscoverServicesFailed(key, NsdManager.FAILURE_MAX_LIMIT) }
                return@submit
            }
            val type = queryType(discovery.serviceType, discovery.subtype)
            if (type == null) {
                reply { callback.onDiscoverServicesFailed(key, NsdManager.FAILURE_BAD_PARAMETERS) }
                return@submit
            }
            val request = Request(Kind.DISCOVERY, network = discovery.network)
            start(key, request, type) {
                request.started = true
                reply { callback.onDiscoverServicesStarted(key, discovery) }
            }
        }

        override fun stopDiscovery(key: Int) = submit {
            if (requests[key]?.kind != Kind.DISCOVERY) return@submit
            val request = checkNotNull(remove(key))
            reply {
                if (request.closeFailure == null) callback.onStopDiscoverySucceeded(key)
                else callback.onStopDiscoveryFailed(key, NsdManager.FAILURE_INTERNAL_ERROR)
            }
        }

        override fun resolveService(key: Int, info: NsdServiceInfo) {
            val snapshot = info.copy()
            submit {
                val type = queryType(snapshot.serviceType, null)
                if (type == null) {
                    reply { callback.onResolveServiceFailed(key, NsdManager.FAILURE_BAD_PARAMETERS) }
                    return@submit
                }
                val request = Request(Kind.RESOLUTION, snapshot, snapshot.network, snapshot.interfaceIndex)
                start(key, request, type) {
                    request.timeout = worker.schedule({
                        if (requests[key] === request) failed(key, request)
                    }, 30, TimeUnit.SECONDS)
                }
            }
        }

        override fun stopResolution(key: Int) = submit {
            if (requests[key]?.kind != Kind.RESOLUTION) return@submit
            val request = checkNotNull(remove(key))
            reply {
                if (request.closeFailure == null) callback.onStopResolutionSucceeded(key)
                else callback.onStopResolutionFailed(key, NsdManager.FAILURE_OPERATION_NOT_RUNNING)
            }
        }

        override fun registerServiceInfoCallback(key: Int, info: NsdServiceInfo) {
            val snapshot = info.copy()
            submit {
                val type = queryType(snapshot.serviceType, null)
                if (type == null) {
                    reply { callback.onServiceInfoCallbackRegistrationFailed(key, NsdManager.FAILURE_BAD_PARAMETERS) }
                    return@submit
                }
                start(key, Request(Kind.INFO, snapshot, snapshot.network, snapshot.interfaceIndex), type) {}
            }
        }

        override fun unregisterServiceInfoCallback(key: Int) = submit {
            if (requests[key]?.kind != Kind.INFO) return@submit
            remove(key)
            reply { callback.onServiceInfoCallbackUnregistered(key) }
        }

        override fun registerService(key: Int, advertising: AdvertisingRequest) {
            NsdManager.checkServiceInfoForRegistration(advertising.serviceInfo)
            // The existing backend has no advertiser. Report the same operation
            // failure as NsdService when its advertiser cannot register a service.
            submit { reply { callback.onRegisterServiceFailed(key, NsdManager.FAILURE_INTERNAL_ERROR) } }
        }

        override fun unregisterService(key: Int) = submit {
            // Failed registrations have no live request, as in NsdService.
        }

        private fun start(key: Int, request: Request, type: String, started: () -> Unit) {
            requests[key] = request
            try {
                val iface = if (request.network != null) {
                    context.getSystemService(ConnectivityManager::class.java)
                        .getLinkProperties(request.network)?.interfaceName
                        ?: throw IllegalArgumentException("Network is unavailable")
                } else if (request.interfaceIndex != 0) {
                    NetworkInterface.getByIndex(request.interfaceIndex)?.name
                        ?: throw IllegalArgumentException("Interface is unavailable")
                } else null
                request.handle = backend.discover(type, object : HostedNsdBackend.Observer {
                    override fun onServiceFound(info: NsdServiceInfo, updated: Boolean) {
                        val snapshot = info.copy()
                        submit { if (requests[key] === request) found(key, request, snapshot) }
                    }
                    override fun onServiceLost(info: NsdServiceInfo) {
                        val snapshot = info.copy()
                        submit { if (requests[key] === request) lost(key, request, snapshot) }
                    }
                    override fun onFailure(detail: String) {
                        submit { if (requests[key] === request) failed(key, request) }
                    }
                }, iface)
                // Synchronous backend observations were queued above; they cannot
                // overtake this acknowledgement on the serialized service worker.
                started()
            } catch (failure: Exception) {
                Log.w("HostedNsdService", "Opening discovery connection", failure)
                failed(key, request)
            }
        }

        private fun matches(request: Request, info: NsdServiceInfo): Boolean =
            request.info == null || (request.info.serviceName == info.serviceName &&
                request.info.serviceType?.trimEnd('.') == info.serviceType?.trimEnd('.'))

        private fun serviceKey(info: NsdServiceInfo) =
            "${info.serviceName}\u0000${info.serviceType}\u0000${info.interfaceIndex}"

        private fun found(key: Int, request: Request, info: NsdServiceInfo) {
            if (!matches(request, info)) return
            if (request.network != null) info.network = request.network
            when (request.kind) {
                Kind.DISCOVERY -> if (request.found.add(serviceKey(info))) {
                    reply { callback.onServiceFound(key, info) }
                }
                Kind.RESOLUTION -> if (info.port > 0 && info.hostAddresses.isNotEmpty()) {
                    remove(key)
                    reply { callback.onResolveServiceSucceeded(key, info) }
                }
                Kind.INFO -> if (info.port > 0 && info.hostAddresses.isNotEmpty()) {
                    reply { callback.onServiceUpdated(key, info) }
                }
            }
        }

        private fun lost(key: Int, request: Request, info: NsdServiceInfo) {
            if (!matches(request, info)) return
            if (request.network != null) info.network = request.network
            when (request.kind) {
                Kind.DISCOVERY -> if (request.found.remove(serviceKey(info))) {
                    reply { callback.onServiceLost(key, info) }
                }
                Kind.INFO -> reply { callback.onServiceUpdatedLost(key) }
                Kind.RESOLUTION -> Unit
            }
        }

        private fun failed(key: Int, request: Request) {
            remove(key)
            reply {
                when (request.kind) {
                    Kind.DISCOVERY -> if (request.started) {
                        callback.onStopDiscoveryFailed(key, NsdManager.FAILURE_INTERNAL_ERROR)
                    } else callback.onDiscoverServicesFailed(key, NsdManager.FAILURE_INTERNAL_ERROR)
                    Kind.RESOLUTION -> callback.onResolveServiceFailed(key, NsdManager.FAILURE_INTERNAL_ERROR)
                    Kind.INFO -> callback.onServiceInfoCallbackRegistrationFailed(key, NsdManager.FAILURE_INTERNAL_ERROR)
                }
            }
        }
    }

    // NsdService.parseTypeAndSubtype, using the original shared grammar. Avahi
    // expects a subtype in DNS form instead of the legacy comma spelling.
    private fun queryType(type: String?, subtype: String?): String? {
        if (type.isNullOrEmpty()) return null
        val match = Pattern.compile(NsdManager.TYPE_REGEX).matcher(type)
        if (!match.matches()) return null
        val suffixes = match.group(3)?.removePrefix(",")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (match.group(1) == null && suffixes.size > 1) return null
        val selected = subtype ?: match.group(1) ?: suffixes.firstOrNull()
        if (selected != null && !Pattern.matches("^${NsdManager.SUBTYPE_LABEL_REGEX}$", selected)) return null
        return if (selected == null) match.group(2) else "$selected._sub.${match.group(2)}"
    }
}
