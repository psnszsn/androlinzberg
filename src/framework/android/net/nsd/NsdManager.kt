package android.net.nsd

import android.content.Context
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/**
 * Hosted adaptation of AOSP NsdManager's listener/state contract. Binder and
 * NsdService are absent; HostedNsdBackend provides DNS-SD records while this
 * class owns validation, active-listener identity, and callback execution.
 */
class NsdManager(context: Context) {
    interface DiscoveryListener {
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onDiscoveryStarted(serviceType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onServiceFound(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
    }

    interface ResolveListener {
        fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceResolved(serviceInfo: NsdServiceInfo)
    }

    interface RegistrationListener {
        fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceRegistered(serviceInfo: NsdServiceInfo)
        fun onServiceUnregistered(serviceInfo: NsdServiceInfo)
    }

    interface ServiceInfoCallback {
        fun onServiceInfoCallbackRegistrationFailed(errorCode: Int)
        fun onServiceUpdated(serviceInfo: NsdServiceInfo)
        fun onServiceLost()
        fun onServiceInfoCallbackUnregistered()
    }

    private data class DiscoverySession(
        val serviceType: String,
        var handle: Closeable? = null,
        var started: Boolean = false,
        val pendingCallbacks: MutableList<() -> Unit> = mutableListOf(),
    )

    private data class ServiceInfoRegistration(
        val requested: NsdServiceInfo,
        val executor: Executor,
    )

    private val callbackExecutor = context.mainExecutor
    private val backend = HostedNsdBackends.create()
    private val discoveries = IdentityHashMap<DiscoveryListener, DiscoverySession>()
    private val serviceInfoCallbacks =
        IdentityHashMap<ServiceInfoCallback, ServiceInfoRegistration>()
    private val registrations = IdentityHashMap<RegistrationListener, NsdServiceInfo>()

    @Synchronized
    fun discoverServices(serviceType: String, protocolType: Int, listener: DiscoveryListener) {
        require(serviceType.isNotBlank()) { "Service type cannot be empty" }
        require(protocolType == PROTOCOL_DNS_SD) { "Unsupported protocol: $protocolType" }
        require(!discoveries.containsKey(listener)) { "listener already in use" }

        val session = DiscoverySession(normalizeServiceType(serviceType))
        discoveries[listener] = session
        try {
            session.handle = backend.discover(session.serviceType, observer(listener, session))
            if (discoveries[listener] !== session) {
                session.handle?.close()
                return
            }
            session.started = true
            execute { listener.onDiscoveryStarted(session.serviceType) }
            session.pendingCallbacks.forEach(::execute)
            session.pendingCallbacks.clear()
        } catch (throwable: Throwable) {
            discoveries.remove(listener)
            execute { listener.onStartDiscoveryFailed(session.serviceType, FAILURE_INTERNAL_ERROR) }
        }
    }

    @Synchronized
    fun stopServiceDiscovery(listener: DiscoveryListener) {
        val session = discoveries.remove(listener)
            ?: throw IllegalArgumentException("listener not registered")
        runCatching { session.handle?.close() }
            .onFailure {
                execute {
                    listener.onStopDiscoveryFailed(
                        session.serviceType,
                        FAILURE_INTERNAL_ERROR,
                    )
                }
                return
            }
        execute { listener.onDiscoveryStopped(session.serviceType) }
    }

    fun resolveService(serviceInfo: NsdServiceInfo, listener: ResolveListener) {
        resolveService(serviceInfo, callbackExecutor, listener)
    }

    fun resolveService(
        serviceInfo: NsdServiceInfo,
        executor: Executor,
        listener: ResolveListener,
    ) {
        val resolved = backend.resolve(serviceInfo)
        executor.execute {
            if (resolved != null) listener.onServiceResolved(resolved)
            else listener.onResolveFailed(serviceInfo.copy(), FAILURE_INTERNAL_ERROR)
        }
    }

    @Synchronized
    fun registerServiceInfoCallback(
        serviceInfo: NsdServiceInfo,
        executor: Executor,
        callback: ServiceInfoCallback,
    ) {
        require(!serviceInfoCallbacks.containsKey(callback)) { "callback already in use" }
        val registration = ServiceInfoRegistration(serviceInfo.copy(), executor)
        serviceInfoCallbacks[callback] = registration
        val resolved = backend.resolve(serviceInfo)
        if (resolved != null) {
            executor.execute { callback.onServiceUpdated(resolved) }
        } else {
            serviceInfoCallbacks.remove(callback)
            executor.execute {
                callback.onServiceInfoCallbackRegistrationFailed(FAILURE_INTERNAL_ERROR)
            }
        }
    }

    @Synchronized
    fun unregisterServiceInfoCallback(callback: ServiceInfoCallback) {
        val registration = serviceInfoCallbacks.remove(callback)
            ?: throw IllegalArgumentException("callback not registered")
        registration.executor.execute { callback.onServiceInfoCallbackUnregistered() }
    }

    @Synchronized
    fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        listener: RegistrationListener,
    ) {
        require(protocolType == PROTOCOL_DNS_SD) { "Unsupported protocol: $protocolType" }
        require(!registrations.containsKey(listener)) { "listener already in use" }
        registrations[listener] = serviceInfo.copy()
        execute { listener.onRegistrationFailed(serviceInfo.copy(), FAILURE_INTERNAL_ERROR) }
    }

    fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        executor: Executor,
        listener: RegistrationListener,
    ) {
        require(protocolType == PROTOCOL_DNS_SD) { "Unsupported protocol: $protocolType" }
        executor.execute {
            listener.onRegistrationFailed(serviceInfo.copy(), FAILURE_INTERNAL_ERROR)
        }
    }

    @Synchronized
    fun unregisterService(listener: RegistrationListener) {
        val serviceInfo = registrations.remove(listener)
            ?: throw IllegalArgumentException("listener not registered")
        execute { listener.onServiceUnregistered(serviceInfo) }
    }

    private fun observer(
        listener: DiscoveryListener,
        session: DiscoverySession,
    ): HostedNsdBackend.Observer = object : HostedNsdBackend.Observer {
        override fun onServiceFound(serviceInfo: NsdServiceInfo, updated: Boolean) {
            val callback = { listener.onServiceFound(serviceInfo.copy()) }
            synchronized(this@NsdManager) {
                if (discoveries[listener] !== session) return
                notifyServiceInfoUpdated(serviceInfo)
                if (!updated) {
                    if (session.started) execute(callback)
                    else session.pendingCallbacks += callback
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val callback = { listener.onServiceLost(serviceInfo.copy()) }
            synchronized(this@NsdManager) {
                if (discoveries[listener] !== session) return
                notifyServiceInfoLost(serviceInfo)
                if (session.started) execute(callback)
                else session.pendingCallbacks += callback
            }
        }

        override fun onFailure(detail: String) {
            synchronized(this@NsdManager) {
                if (discoveries.remove(listener) !== session) return
            }
            if (session.started) {
                execute {
                    listener.onStopDiscoveryFailed(session.serviceType, FAILURE_INTERNAL_ERROR)
                }
            } else {
                execute {
                    listener.onStartDiscoveryFailed(session.serviceType, FAILURE_INTERNAL_ERROR)
                }
            }
        }
    }

    private fun notifyServiceInfoUpdated(serviceInfo: NsdServiceInfo) {
        serviceInfoCallbacks.forEach { (callback, registration) ->
            if (sameService(registration.requested, serviceInfo)) {
                registration.executor.execute { callback.onServiceUpdated(serviceInfo.copy()) }
            }
        }
    }

    private fun notifyServiceInfoLost(serviceInfo: NsdServiceInfo) {
        serviceInfoCallbacks.forEach { (callback, registration) ->
            if (sameService(registration.requested, serviceInfo)) {
                registration.executor.execute(callback::onServiceLost)
            }
        }
    }

    private fun execute(action: () -> Unit) {
        callbackExecutor.execute(action)
    }

    private fun sameService(first: NsdServiceInfo, second: NsdServiceInfo): Boolean =
        first.getServiceName() == second.getServiceName() &&
            normalizeServiceType(first.getServiceType().orEmpty()) ==
            normalizeServiceType(second.getServiceType().orEmpty())

    private fun normalizeServiceType(value: String): String = value.trimEnd('.')

    companion object {
        const val PROTOCOL_DNS_SD: Int = 1
        const val FAILURE_INTERNAL_ERROR: Int = 0
        const val FAILURE_ALREADY_ACTIVE: Int = 3
        const val FAILURE_MAX_LIMIT: Int = 4
        const val FAILURE_BAD_PARAMETERS: Int = 6
        const val FAILURE_OPERATION_NOT_RUNNING: Int = 5

        const val NSD_STATE_DISABLED: Int = 1
        const val NSD_STATE_ENABLED: Int = 2
        const val ACTION_NSD_STATE_CHANGED: String = "android.net.nsd.STATE_CHANGED"
        const val EXTRA_NSD_STATE: String = "nsd_state"
    }
}
