package dev.artzig

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Parcel
import dev.artzig.system.HostedNsdService
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Original NSD client contracts, with a deterministic service-side DNS-SD connection. */
internal object StockNsdSmoke {
    private fun expect(queue: LinkedBlockingQueue<String>, value: String) {
        check(queue.poll(5, TimeUnit.SECONDS) == value) { "Expected NSD callback $value" }
    }
    private inline fun <reified T : Throwable> rejects(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            check(failure is T) { failure }
            return
        }
        error("Expected ${T::class.java.simpleName}")
    }

    fun run() {
        avahiRecords()
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            200, 200, 120, 0)
        val published = checkNotNull(context.getSystemService(NsdManager::class.java))
        check(published === context.getSystemService(Context.NSD_SERVICE))
        binderDeathClosesConnection(context)
        val backend = InMemoryHostedNsdBackend()
        val manager = NsdManager(context, HostedNsdService(context) { backend })
        val service = NsdServiceInfo().apply {
            serviceName = "Living Room"
            serviceType = "_fcast._tcp"
            hostAddresses = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
            port = 46899
            setAttribute("id", "framework-smoke")
            setAttribute("binary", byteArrayOf(0, -1, 7))
        }
        val parcel = Parcel.obtain()
        try {
            service.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = NsdServiceInfo.CREATOR.createFromParcel(parcel)
            check(restored.serviceName == service.serviceName && restored.port == service.port)
            check(restored.hostAddresses == service.hostAddresses)
            check(checkNotNull(restored.attributes["binary"]).contentEquals(byteArrayOf(0, -1, 7)))
            restored.removeAttribute("id")
            check(service.attributes.containsKey("id"))
        } finally { parcel.recycle() }
        for (key in listOf("", "bad=key", "\u001f", "\u00e9")) {
            rejects<IllegalArgumentException> { service.setAttribute(key, "invalid") }
        }
        rejects<IllegalArgumentException> { service.setAttribute("oversize", ByteArray(255)) }

        backend.publish(service)
        val events = LinkedBlockingQueue<String>()
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) { events += "failed:$error" }
            override fun onStopDiscoveryFailed(type: String, error: Int) { events += "stop-failed:$error" }
            override fun onDiscoveryStarted(type: String) { events += "started:$type" }
            override fun onDiscoveryStopped(type: String) { events += "stopped:$type" }
            override fun onServiceFound(info: NsdServiceInfo) { found += info; events += "found:${info.serviceName}" }
            override fun onServiceLost(info: NsdServiceInfo) { events += "lost:${info.serviceName}" }
        }
        manager.discoverServices("_fcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        rejects<IllegalArgumentException> {
            manager.discoverServices("_fcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        }
        expect(events, "started:_fcast._tcp")
        expect(events, "found:Living Room")
        val discovered = checkNotNull(found.poll(5, TimeUnit.SECONDS))
        check(discovered.hostAddresses == service.hostAddresses && discovered.port == 46899)

        val updates = LinkedBlockingQueue<String>()
        val infoCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(error: Int) { updates += "failed:$error" }
            override fun onServiceUpdated(info: NsdServiceInfo) { updates += "updated:${info.port}" }
            override fun onServiceLost() { updates += "lost" }
            override fun onServiceInfoCallbackUnregistered() { updates += "unregistered" }
        }
        val direct = Executor(Runnable::run)
        manager.registerServiceInfoCallback(discovered, direct, infoCallback)
        expect(updates, "updated:46899")
        service.port = 46900
        backend.publish(service)
        expect(updates, "updated:46900")
        check(events.isEmpty()) { "A TXT/address update became a duplicate discovery" }
        backend.remove(service)
        expect(events, "lost:Living Room")
        expect(updates, "lost")
        backend.publish(service)
        expect(events, "found:Living Room")
        expect(updates, "updated:46900")
        manager.unregisterServiceInfoCallback(infoCallback)
        expect(updates, "unregistered")
        rejects<IllegalArgumentException> { manager.unregisterServiceInfoCallback(infoCallback) }

        val resolutions = LinkedBlockingQueue<String>()
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolutions += "failed:$error" }
            override fun onServiceResolved(info: NsdServiceInfo) { resolutions += "resolved:${info.port}" }
            override fun onResolutionStopped(info: NsdServiceInfo) { resolutions += "stopped" }
        }
        manager.resolveService(NsdServiceInfo("Living Room", "_fcast._tcp"), direct, resolver)
        expect(resolutions, "resolved:46900")
        manager.resolveService(NsdServiceInfo("Missing", "_fcast._tcp"), direct, resolver)
        manager.stopServiceResolution(resolver)
        expect(resolutions, "stopped")
        rejects<IllegalArgumentException> { manager.stopServiceResolution(resolver) }

        manager.stopServiceDiscovery(listener)
        expect(events, "stopped:_fcast._tcp")
        rejects<IllegalArgumentException> { manager.stopServiceDiscovery(listener) }
        service.port = 47000
        backend.publish(service)
        check(events.poll(30, TimeUnit.MILLISECONDS) == null && updates.isEmpty())

        // Backend failure must release the original client's listener so it can be reused.
        val unavailable = NsdManager(context, HostedNsdService(context) {
            object : HostedNsdBackend {
                override fun discover(type: String, observer: HostedNsdBackend.Observer,
                    interfaceName: String?): java.io.Closeable = throw java.io.IOException("fixture connection failure")
            }
        })
        repeat(2) {
            unavailable.discoverServices("_fcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            expect(events, "failed:${NsdManager.FAILURE_INTERNAL_ERROR}")
        }
        val closeFailure = NsdManager(context, HostedNsdService(context) {
            object : HostedNsdBackend {
                override fun discover(type: String, observer: HostedNsdBackend.Observer,
                    interfaceName: String?): java.io.Closeable = java.io.Closeable {
                        throw java.io.IOException("fixture close failure")
                    }
            }
        })
        repeat(2) {
            closeFailure.discoverServices("_fcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            expect(events, "started:_fcast._tcp")
            closeFailure.stopServiceDiscovery(listener)
            expect(events, "stop-failed:${NsdManager.FAILURE_INTERNAL_ERROR}")
        }
        val registrations = LinkedBlockingQueue<String>()
        val registration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { registrations += "failed:$error" }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) { registrations += "unregister-failed:$error" }
            override fun onServiceRegistered(info: NsdServiceInfo) { registrations += "registered" }
            override fun onServiceUnregistered(info: NsdServiceInfo) { registrations += "unregistered" }
        }
        repeat(2) {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, direct, registration)
            expect(registrations, "failed:${NsdManager.FAILURE_INTERNAL_ERROR}")
            rejects<IllegalArgumentException> { manager.unregisterService(registration) }
        }
        println("stock NSD values, discovery/resolve/update ordering and cancellation: PASS")
    }

    private fun binderDeathClosesConnection(context: Context) {
        val opened = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        var recipient: android.os.IBinder.DeathRecipient? = null
        val binder = object : android.os.Binder() {
            override fun linkToDeath(value: android.os.IBinder.DeathRecipient, flags: Int) { recipient = value }
            override fun unlinkToDeath(value: android.os.IBinder.DeathRecipient, flags: Int): Boolean {
                check(value === recipient)
                recipient = null
                return true
            }
        }
        val callback = object : android.net.nsd.INsdManagerCallback by android.net.nsd.INsdManagerCallback.Default() {
            override fun asBinder(): android.os.IBinder = binder
            override fun onDiscoverServicesStarted(key: Int, request: android.net.nsd.DiscoveryRequest) { opened.countDown() }
        }
        val service = HostedNsdService(context) {
            object : HostedNsdBackend {
                override fun discover(type: String, observer: HostedNsdBackend.Observer,
                    interfaceName: String?): java.io.Closeable = java.io.Closeable { closed.countDown() }
            }
        }
        val connector = service.connect(callback, true)
        connector.discoverServices(17, android.net.nsd.DiscoveryRequest.Builder(
            NsdManager.PROTOCOL_DNS_SD, "_fcast._tcp").build())
        check(opened.await(5, TimeUnit.SECONDS))
        checkNotNull(recipient).binderDied()
        check(closed.await(5, TimeUnit.SECONDS)) { "Dead Binder client retained its DNS-SD connection" }
    }

    private fun avahiRecords() {
        val found = ArrayList<Pair<NsdServiceInfo, Boolean>>()
        val lost = ArrayList<NsdServiceInfo>()
        val records = AvahiNsdRecords(object : HostedNsdBackend.Observer {
            override fun onServiceFound(info: NsdServiceInfo, updated: Boolean) { found += info to updated }
            override fun onServiceLost(info: NsdServiceInfo) { lost += info }
            override fun onFailure(detail: String) { error(detail) }
        })
        val suffix = "lo;IPv4;Caf\\195\\169\\032Room\\0591;_fcast._tcp;local"
        records.consume("+;$suffix")
        check(found.single().first.serviceName == "Café Room;1" && !found.single().second)
        records.consume("=;$suffix;host.local;127.0.0.1;46899;\"id=one;two\" \"binary=\\000\\255\" \"flag\" \"quoted=a\\\"b\\\\c\"")
        val info = found.last().first
        check(found.last().second && info.port == 46899)
        check(String(checkNotNull(info.attributes["id"])) == "one;two")
        check(checkNotNull(info.attributes["binary"]).contentEquals(byteArrayOf(0, -1)))
        check(info.attributes.containsKey("flag") && info.attributes["flag"] == null)
        check(String(checkNotNull(info.attributes["quoted"])) == "a\"b\\c")
        records.consume("=;${suffix.replace("IPv4", "IPv6")};host.local;::1;46899;\"id=updated\"")
        check(found.last().first.hostAddresses.size == 2)
        check(!found.last().first.attributes.containsKey("binary"))
        records.consume("-;$suffix")
        check(lost.isEmpty() && found.last().first.hostAddresses == listOf(InetAddress.getByName("::1")))
        records.consume("-;${suffix.replace("IPv4", "IPv6")}")
        check(lost.single().serviceName == "Café Room;1")
    }
}
