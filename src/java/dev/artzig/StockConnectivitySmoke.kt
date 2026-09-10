package dev.artzig

import android.content.Context
import android.net.ConnectivityManager
import android.net.IConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.android.net.module.util.BpfMap
import com.android.net.module.util.Struct
import dev.artzig.system.HostedConnectivityService
import dev.artzig.system.HostedNetworkSnapshot
import dev.artzig.system.HostedNetworkSource
import dev.artzig.system.HostedPackageManagerService

/** Stock client callbacks over the generated proxy, with deterministic service observations. */
internal object StockConnectivitySmoke {
    private class CallerContext(base: Context, private val caller: String) : android.content.ContextWrapper(base) {
        var allowed = true
        var internetAllowed = true
        override fun getPackageName(): String = caller
        override fun getOpPackageName(): String = caller
        override fun enforceCallingOrSelfPermission(name: String, message: String?) {
            if (!allowed || (!internetAllowed && name == android.Manifest.permission.INTERNET)) throw SecurityException(name)
        }
    }

    fun run(fixtureApk: String, pump: () -> Unit) {
        val base = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle())).apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val installed = packages.installPackage(fixtureApk)
        val caller = if (Binder.getCallingUid() == android.os.Process.SYSTEM_UID) "android" else installed
        check(packages.getPackagesForUid(Binder.getCallingUid())!!.contains(caller))
        val context = CallerContext(base, caller)
        var observed: HostedNetworkSnapshot? = snapshot(100)
        val reports = ArrayList<Pair<Network, Boolean>>()
        val observations = object : HostedNetworkSource {
            override fun snapshot(): HostedNetworkSnapshot? = observed
            override fun reportConnectivity(network: Network, hasConnectivity: Boolean): Boolean {
                reports.add(Network(network) to hasConnectivity)
                return true
            }
        }
        val service = HostedConnectivityService(context, packages, observations, Handler(Looper.getMainLooper()))
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.transact(code, data, reply, flags)
        }
        val transport = IConnectivityManager.Stub.asInterface(remote)
        val manager = ConnectivityManager(context, transport)
        context.allowed = false
        check(runCatching { manager.activeNetwork }.exceptionOrNull() is SecurityException)
        check(runCatching { manager.reportNetworkConnectivity(null, true) }.exceptionOrNull() is SecurityException)
        context.allowed = true
        context.internetAllowed = false
        check(manager.activeNetwork == Network(100))
        check(runCatching { manager.reportNetworkConnectivity(null, true) }.exceptionOrNull() is SecurityException)
        check(reports.isEmpty())
        context.internetAllowed = true
        manager.reportNetworkConnectivity(Network(999), true)
        check(reports.isEmpty())
        manager.reportNetworkConnectivity(null, true)
        check(reports.single() == (Network(100) to true))
        check(manager.isDefaultNetworkActive)
        var radioActivityEvents = 0
        val radioListener = ConnectivityManager.OnNetworkActiveListener { radioActivityEvents++ }
        manager.addDefaultNetworkActiveListener(radioListener)
        check(manager.activeNetwork == Network(100))
        check(manager.activeNetworkInfo!!.isConnected && !manager.isActiveNetworkMetered)
        check(manager.getNetworkCapabilities(Network(999)) == null)
        check(manager.getLinkProperties(Network(100))!!.interfaceName == "test0")
        val copied = manager.getLinkProperties(Network(100))!!
        copied.interfaceName = "changed"
        check(manager.getLinkProperties(Network(100))!!.interfaceName == "test0")
        check(runCatching { transport.getNetworkCapabilities(Network(100), "unowned.package", null) }
            .exceptionOrNull() is SecurityException)
        check(manager.defaultProxy == null && manager.boundNetworkForProcess == null)
        check(manager.bindProcessToNetwork(null))
        check(!manager.bindProcessToNetwork(Network(100))) // No Android netd routing domain.

        val events = ArrayList<String>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { events += "available:${network.netId}" }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { events += "caps:${network.netId}" }
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) { events += "links:${network.netId}" }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { check(!blocked); events += "blocked:${network.netId}" }
            override fun onLost(network: Network) { events += "lost:${network.netId}" }
        }
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        try {
            check(events.isEmpty()) { "network callback ran inside registration" }
            pump()
            check(events == listOf("available:100", "caps:100", "links:100", "blocked:100")) { events }
            events.clear()
            observed = snapshot(100).let { it.copy(links = LinkProperties(it.links).apply { mtu = 1400 }) }
            service.refresh(); pump()
            check(events == listOf("links:100")) { events }
            events.clear()
            observed = null
            service.refresh(); pump()
            check(events == listOf("lost:100") && manager.activeNetwork == null) { events }
            events.clear()
            observed = snapshot(101)
            service.refresh(); pump()
            check(events.first() == "available:101" && events.size == 4) { events }
        } finally { manager.unregisterNetworkCallback(callback) }
        events.clear()
        observed = null
        service.refresh(); pump()
        check(events.isEmpty()) { "unregistered callback received a network event" }
        check(manager.isDefaultNetworkActive && radioActivityEvents == 0)
        manager.removeDefaultNetworkActiveListener(radioListener)

        var unavailable = false
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        var canceledTimedOut = false
        val canceled = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() { canceledTimedOut = true }
        }
        manager.requestNetwork(request, canceled, Handler(Looper.getMainLooper()), 20)
        manager.unregisterNetworkCallback(canceled)
        manager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() { unavailable = true }
        }, Handler(Looper.getMainLooper()), 20)
        val deadline = SystemClock.uptimeMillis() + 1_000
        while (!unavailable && SystemClock.uptimeMillis() < deadline) { pump(); SystemClock.sleep(1) }
        check(unavailable)
        check(!canceledTimedOut) { "released request received a timeout" }

        val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
        try {
            repeat(100) {
                val cb = object : ConnectivityManager.NetworkCallback() {}
                manager.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
                callbacks += cb
            }
            check(runCatching {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {}, Handler(Looper.getMainLooper()))
            }.exceptionOrNull() is ConnectivityManager.TooManyRequestsException)
        } finally { callbacks.forEach(manager::unregisterNetworkCallback) }
        observed = snapshot(102)
        service.refresh(); pump()

        // Original JNI reports Linux errno; bad access mode fails before a BPF syscall.
        val badMode = runCatching {
            BpfMap("/nonexistent/artzig-map", -1, Struct.S32::class.java, Struct.U32::class.java).close()
        }.exceptionOrNull()
        check(badMode is ErrnoException && badMode.errno == OsConstants.EINVAL) { badMode.toString() }
        val absentMap = runCatching {
            BpfMap("/nonexistent/artzig-map", BpfMap.BPF_F_RDONLY, Struct.S32::class.java, Struct.U32::class.java).close()
        }.exceptionOrNull()
        check(absentMap is ErrnoException && absentMap.errno in listOf(OsConstants.ENOENT, OsConstants.EPERM, OsConstants.EACCES)) {
            absentMap.toString()
        }
        println("stock-connectivity: route observations, proxy callbacks, ownership, lifecycle, limits and BPF JNI errno passed")
    }

    private fun snapshot(id: Int): HostedNetworkSnapshot {
        val info = NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "").apply {
            setIsAvailable(true)
            setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, "test0")
        }
        val capabilities = NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val links = LinkProperties().apply {
            interfaceName = "test0"
            mtu = 1500
            addLinkAddress(LinkAddress("192.0.2.2/24"))
            addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), android.net.InetAddresses.parseNumericAddress("192.0.2.1"), "test0"))
        }
        return HostedNetworkSnapshot(Network(id), info, capabilities, links)
    }
}
