package dev.artzig.system

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager as CM
import android.net.IConnectivityManager
import android.net.INetworkActivityListener
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities as NC
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.ServiceSpecificException
import com.android.internal.os.BackgroundThread

/**
 * Source: ConnectivityService requestNetwork/listenForNetwork, request ownership,
 * rematching, and callCallbackForRequest. Linux supplies the host's default route;
 * netd, radio NetworkAgents and the Android policy/BPF daemon graph are absent.
 * This service owns discovery and callback state behind the stock client protocol.
 */
internal class HostedConnectivityService(
    private val context: Context,
    private val packages: android.content.pm.IPackageManager,
    private val source: HostedNetworkSource = ValidatedNetworkSource(LinuxNetworkSource(), HttpNetworkProbe.forContext(context)),
    private val handler: Handler = BackgroundThread.getHandler(),
) : IConnectivityManager.Stub(), IConnectivityManager by unsupported<IConnectivityManager>() {
    override fun asBinder(): IBinder = this
    private val lock = Any()
    private var current: HostedNetworkSnapshot? = null
    private var nextRequest = 2
    private val requests = LinkedHashMap<Int, Request>()
    private val activityListeners = RemoteCallbackList<INetworkActivityListener>()
    private var polling = false
    private val defaultCapabilities = NC().addCapability(NC.NET_CAPABILITY_INTERNET)
    private val defaultRequest = NetworkRequest(defaultCapabilities, CM.TYPE_NONE, 1, NetworkRequest.Type.REQUEST)
    private class Request(val request: NetworkRequest, val uid: Int, val messenger: Messenger,
        val binder: IBinder, val death: IBinder.DeathRecipient) {
        var matched: HostedNetworkSnapshot? = null
        var timeout: Runnable? = null
    }
    private val poll = object : Runnable {
        override fun run() = synchronized(lock) {
            refreshLocked()
            if (requests.isNotEmpty()) handler.postDelayed(this, 1_000) else polling = false
            Unit
        }
    }

    private fun access() = context.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE, "Connectivity")
    private fun settings() = context.enforceCallingOrSelfPermission(Manifest.permission.NETWORK_SETTINGS, "Connectivity")
    private fun owner(packageName: String?): Int {
        val uid = Binder.getCallingUid()
        if (packageName == null || packages.getPackagesForUid(uid)?.contains(packageName) != true) {
            throw SecurityException("uid $uid does not own $packageName")
        }
        return uid
    }
    private fun state(): HostedNetworkSnapshot? {
        access()
        return synchronized(lock) { refreshLocked(); current }
    }
    private fun forUid(uid: Int): HostedNetworkSnapshot? {
        if (uid != Binder.getCallingUid()) settings()
        return state()
    }
    private fun forNetwork(network: Network?): HostedNetworkSnapshot? = state()?.takeIf { it.network == network }

    override fun getActiveNetwork(): Network? = state()?.network?.let(::Network)

    override fun reportNetworkConnectivity(network: Network?, hasConnectivity: Boolean) {
        access()
        context.enforceCallingOrSelfPermission(Manifest.permission.INTERNET, "Connectivity report")
        val current = state() ?: return
        if (network != null && network != current.network) return
        if (!source.reportConnectivity(current.network, hasConnectivity)) {
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("android.net.IConnectivityManager",
                "reportNetworkConnectivity", "This network source cannot perform validation probes")
        }
    }
    override fun getActiveNetworkForUid(uid: Int, ignoreBlocked: Boolean): Network? = forUid(uid)?.network?.let(::Network)
    override fun getActiveNetworkInfo(): NetworkInfo? = state()?.info?.let(::NetworkInfo)
    override fun getActiveNetworkInfoForUid(uid: Int, ignoreBlocked: Boolean): NetworkInfo? = forUid(uid)?.info?.let(::NetworkInfo)
    override fun getNetworkInfo(type: Int): NetworkInfo? = state()?.info?.takeIf { it.type == type }?.let(::NetworkInfo)
    override fun getNetworkInfoForUid(network: Network?, uid: Int, ignoreBlocked: Boolean): NetworkInfo? =
        forUid(uid)?.takeIf { it.network == network }?.info?.let(::NetworkInfo)
    override fun getAllNetworkInfo(): Array<NetworkInfo> = state()?.let { arrayOf(NetworkInfo(it.info)) } ?: emptyArray()
    override fun getNetworkForType(type: Int): Network? = state()?.takeIf { it.info.type == type }?.network?.let(::Network)
    override fun getAllNetworks(): Array<Network> = state()?.let { arrayOf(Network(it.network)) } ?: emptyArray()
    override fun isNetworkSupported(type: Int): Boolean { access(); return source.supportsLegacyType(type) }
    override fun getActiveLinkProperties(): LinkProperties? = state()?.links?.let(::LinkProperties)
    override fun getLinkPropertiesForType(type: Int): LinkProperties? = state()?.takeIf { it.info.type == type }?.links?.let(::LinkProperties)
    override fun getLinkProperties(network: Network?): LinkProperties? = forNetwork(network)?.links?.let(::LinkProperties)
    override fun getNetworkCapabilities(network: Network?, packageName: String?, attribution: String?): NC? {
        owner(packageName)
        return forNetwork(network)?.capabilities?.let(::NC)
    }
    override fun getDefaultNetworkCapabilitiesForUser(userId: Int, packageName: String?, attribution: String?): Array<NC> {
        owner(packageName)
        if (userId != android.os.UserHandle.getUserId(Binder.getCallingUid())) settings()
        return state()?.let { arrayOf(NC(it.capabilities)) } ?: emptyArray()
    }
    override fun isActiveNetworkMetered(): Boolean = state()?.capabilities?.hasCapability(NC.NET_CAPABILITY_NOT_METERED) != true
    override fun getDefaultRequest(): NetworkRequest { access(); return NetworkRequest(defaultRequest) }
    override fun getGlobalProxy(): ProxyInfo? = null
    override fun getProxyForNetwork(network: Network?): ProxyInfo? = null
    override fun getEnabledConnectivityManagerFeatures(): Long = 0L
    // AOSP NetworkActivityTracker keeps an untracked transport active, including
    // when no default route exists. Linux has no Android radio idle timers here.
    override fun isDefaultNetworkActive(): Boolean = true
    override fun registerNetworkActivityListener(listener: INetworkActivityListener?) {
        activityListeners.register(requireNotNull(listener))
    }
    override fun unregisterNetworkActivityListener(listener: INetworkActivityListener?) {
        activityListeners.unregister(requireNotNull(listener))
    }

    override fun requestNetwork(uid: Int, capabilities: NC?, type: Int, messenger: Messenger?, timeoutMs: Int,
        binder: IBinder?, legacy: Int, flags: Int, packageName: String?, attribution: String?, declaredMethods: Int): NetworkRequest {
        val requestType = NetworkRequest.Type.values().getOrNull(type)
            ?: throw IllegalArgumentException("Unsupported request type $type")
        if (uid != Process.INVALID_UID || legacy != CM.TYPE_NONE) settings()
        when (requestType) {
            NetworkRequest.Type.TRACK_DEFAULT -> access()
            NetworkRequest.Type.TRACK_SYSTEM_DEFAULT, NetworkRequest.Type.BACKGROUND_REQUEST -> settings()
            NetworkRequest.Type.REQUEST, NetworkRequest.Type.RESERVATION -> {
                val permission = if (requireNotNull(capabilities).hasCapability(NC.NET_CAPABILITY_NOT_RESTRICTED))
                    Manifest.permission.CHANGE_NETWORK_STATE else Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS
                context.enforceCallingOrSelfPermission(permission, "Connectivity request")
                if (requestType == NetworkRequest.Type.RESERVATION) {
                    owner(packageName)
                    throw dev.artzig.ArtzigTestDiagnostics.unsupported("android.net.IConnectivityManager",
                        "requestNetwork", "Network reservations require a hosted network provider")
                }
            }
            NetworkRequest.Type.LISTEN_FOR_BEST -> access()
            else -> throw IllegalArgumentException("Unsupported request type $requestType")
        }
        return register(capabilities, requestType, messenger, binder, timeoutMs, legacy, packageName, uid)
    }

    override fun listenForNetwork(capabilities: NC?, messenger: Messenger?, binder: IBinder?, flags: Int,
        packageName: String?, attribution: String?, declaredMethods: Int): NetworkRequest {
        access()
        return register(capabilities, NetworkRequest.Type.LISTEN, messenger, binder, 0, CM.TYPE_NONE, packageName)
    }

    private fun register(capabilities: NC?, type: NetworkRequest.Type, messenger: Messenger?, binder: IBinder?,
        timeoutMs: Int, legacy: Int, packageName: String?, asUid: Int = Process.INVALID_UID): NetworkRequest {
        val uid = owner(packageName)
        require(timeoutMs >= 0) { "Bad timeout specified" }
        val target = java.util.Objects.requireNonNull(messenger)!!
        val token = java.util.Objects.requireNonNull(binder)!!
        val needs = if (type == NetworkRequest.Type.TRACK_DEFAULT || type == NetworkRequest.Type.TRACK_SYSTEM_DEFAULT)
            NC(defaultCapabilities) else NC(java.util.Objects.requireNonNull(capabilities))
        if (type != NetworkRequest.Type.LISTEN) {
            require(needs.describeFirstNonRequestableCapability() == null) {
                "Cannot request network with ${needs.describeFirstNonRequestableCapability()}"
            }
        }
        require(needs.networkSpecifier !is android.net.MatchAllNetworkSpecifier) { "A MatchAllNetworkSpecifier is not permitted" }
        require(!needs.isPrivateDnsBroken) { "Can't request broken private DNS" }
        require(!needs.hasAllowedUids()) { "Can't request access UIDs" }
        require(needs.transportInfo == null) { "Can't request transport info" }
        needs.setSingleUid(if (asUid == Process.INVALID_UID) uid else asUid)
        needs.setRequestorUidAndPackageName(uid, checkNotNull(packageName))
        return synchronized(lock) {
            if (requests.values.count { it.uid == uid } >= 100) throw ServiceSpecificException(CM.Errors.TOO_MANY_REQUESTS)
            val id = nextRequest++
            val request = NetworkRequest(needs, legacy, id, type)
            val death = IBinder.DeathRecipient { synchronized(lock) { removeLocked(id) } }
            token.linkToDeath(death, 0)
            val record = Request(request, uid, target, token, death)
            requests[id] = record
            refreshLocked()
            if (!polling) { polling = true; handler.postDelayed(poll, 1_000) }
            if (timeoutMs > 0) {
                val timeout = Runnable {
                    synchronized(lock) {
                        if (requests[id] === record && record.matched == null) {
                            send(record, CM.CALLBACK_UNAVAIL, null)
                            removeLocked(id)
                        }
                    }
                }
                record.timeout = timeout
                handler.postDelayed(timeout, timeoutMs.toLong())
            }
            NetworkRequest(request)
        }
    }

    override fun releaseNetworkRequest(request: NetworkRequest?) {
        val id = java.util.Objects.requireNonNull(request)!!.requestId
        synchronized(lock) {
            val record = requests[id] ?: return
            if (record.uid != Binder.getCallingUid() && Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw SecurityException("Network request belongs to another UID")
            }
            removeLocked(id)
        }
    }

    private fun removeLocked(id: Int) {
        val record = requests.remove(id) ?: return
        record.timeout?.let(handler::removeCallbacks)
        record.timeout = null
        try { record.binder.unlinkToDeath(record.death, 0) } catch (_: NoSuchElementException) { }
        if (requests.isEmpty()) { handler.removeCallbacks(poll); polling = false }
    }

    /** Service event seam also used by deterministic network-change tests. */
    internal fun refresh() = synchronized(lock) { refreshLocked() }

    private fun refreshLocked() {
        current = source.snapshot()
        for (record in requests.values.toList()) {
            val next = current?.takeIf { record.request.networkCapabilities.satisfiedByNetworkCapabilities(it.capabilities) }
            val old = record.matched
            record.matched = next
            if (old?.network != next?.network) {
                if (old != null) send(record, CM.CALLBACK_LOST, old)
                if (next != null) send(record, CM.CALLBACK_AVAILABLE, next)
            } else if (old != null && next != null) {
                if (old.capabilities != next.capabilities) send(record, CM.CALLBACK_CAP_CHANGED, next)
                if (old.links != next.links) send(record, CM.CALLBACK_IP_CHANGED, next)
            }
        }
    }

    private fun send(record: Request, kind: Int, snapshot: HostedNetworkSnapshot?) {
        val data = Bundle().apply {
            putParcelable(NetworkRequest::class.java.simpleName, NetworkRequest(record.request))
            snapshot?.let {
                putParcelable(Network::class.java.simpleName, Network(it.network))
                if (kind == CM.CALLBACK_AVAILABLE || kind == CM.CALLBACK_CAP_CHANGED)
                    putParcelable(NC::class.java.simpleName, NC(it.capabilities))
                if (kind == CM.CALLBACK_AVAILABLE || kind == CM.CALLBACK_IP_CHANGED)
                    putParcelable(LinkProperties::class.java.simpleName, LinkProperties(it.links))
            }
        }
        try { record.messenger.send(Message.obtain().apply { what = kind; arg1 = 0; this.data = data }) }
        catch (_: RemoteException) { removeLocked(record.request.requestId) }
    }
}
