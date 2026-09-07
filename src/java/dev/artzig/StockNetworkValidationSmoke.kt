package dev.artzig

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.ProxyInfo
import android.net.Uri
import android.net.captiveportal.CaptivePortalProbeResult as Result
import android.net.captiveportal.CaptivePortalProbeSpec
import android.net.metrics.ValidationProbeEvent as Event
import android.os.Parcel
import android.os.SystemClock
import dev.artzig.system.HostedNetworkProbe
import dev.artzig.system.HostedNetworkSnapshot
import dev.artzig.system.HostedNetworkSource
import dev.artzig.system.HttpNetworkProbe
import dev.artzig.system.ValidatedNetworkSource
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Probe HTTP contracts and validation evidence are independent of public Internet availability. */
internal object StockNetworkValidationSmoke {
    private fun network(id: Int = 100) = HostedNetworkSnapshot(Network(id),
        NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "").apply {
            setIsAvailable(true); setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, "fixture$id")
        }, NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED),
        LinkProperties().apply { interfaceName = "fixture$id" })

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) Thread.sleep(1)
        check(predicate()) { "Timed out waiting for network validation" }
    }

    private class Server : AutoCloseable {
        private val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { Thread(it, "probe-fixture").apply { isDaemon = true } }
        val requests = LinkedBlockingQueue<Pair<String, Map<String, String>>>()
        init {
            workers.execute {
                try {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        workers.execute {
                            try { client.use {
                                val reader = it.getInputStream().bufferedReader()
                                val first = reader.readLine() ?: return@use
                                val path = first.split(' ')[1]
                                val headers = linkedMapOf<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                                }
                                requests.add(path to headers)
                                val status = when (path) {
                                    "/ok" -> 204
                                    "/portal", "/pac-redirect" -> 302
                                    "/empty", "/tiny", "/page", "/pac" -> 200
                                    else -> 503
                                }
                                val body = when (path) { "/tiny" -> "tiny"; "/page" -> "sign in here"; "/pac" -> "proxy script"; else -> "" }
                                val location = when (path) {
                                    "/portal" -> "Location: http://signin.invalid/\r\n"
                                    "/pac-redirect" -> "Location: /pac\r\n"
                                    else -> ""
                                }
                                val response = "HTTP/1.1 $status Fixture\r\n${location}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                it.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                            } } catch (_: java.io.IOException) { /* Early conclusive probes close their other connection. */ }
                        }
                    }
                } catch (e: SocketException) { if (!socket.isClosed) throw e }
            }
        }
        fun url(path: String): URL = URL("http://127.0.0.1:${socket.localPort}$path")
        override fun close() { socket.close(); workers.shutdownNow() }
    }

    fun run() {
        val task = java.util.concurrent.FutureTask { exercise() }
        Thread(task, "network-validation-test").start()
        task.get(60, TimeUnit.SECONDS)
    }

    private fun exercise() {
        check(Result(204, 1 shl Event.PROBE_HTTPS).isSuccessful)
        check(Result(204, 1 shl Event.PROBE_HTTPS).isConcludedFromHttps)
        check(Result(302, 1 shl Event.PROBE_HTTP).isPortal)
        check(Result(599, 1 shl Event.PROBE_HTTP).isFailed)
        check(Result.PARTIAL.isPartialConnectivity && Result.PARTIAL.isConcludedFromHttps && Result.PARTIAL.isConcludedFromHttp)
        val spec = CaptivePortalProbeSpec.parseSpec("http://localhost/@@/@@204@@/@@.*")
        check(spec.getResult(204, null).isSuccessful)
        check(spec.getResult(302, "http://portal/").isPortal)
        check(runCatching { CaptivePortalProbeSpec.parseSpec("") }.exceptionOrNull() is java.text.ParseException)
        check(runCatching { CaptivePortalProbeSpec.parseSpec("https://localhost/@@/@@204@@/@@.*") }.exceptionOrNull() is IllegalArgumentException)
        val metric = Event.Builder().setDurationMs(37).setProbeType(Event.PROBE_HTTPS, true).setReturnCode(204).build()
        Parcel.obtain().let { parcel ->
            try { metric.writeToParcel(parcel, 0); parcel.setDataPosition(0); check(Event.CREATOR.createFromParcel(parcel) == metric) }
            finally { parcel.recycle() }
        }
        Server().use { server ->
            fun evaluate(https: String, http: String, fallback: String? = null, useHttps: Boolean = true): Result =
                HttpNetworkProbe { HttpNetworkProbe.Config(server.url(https), server.url(http), fallback?.let(server::url),
                    useHttps = useHttps, userAgent = "stock-probe-fixture") }.use { it.evaluate(network()) }
            check(evaluate("/ok", "/fail").isSuccessful)
            check(evaluate("/fail", "/ok").isPartialConnectivity)
            val portal = evaluate("/fail", "/portal")
            check(portal.isPortal && portal.redirectUrl == "http://signin.invalid/")
            check(evaluate("/fail", "/fail", "/portal").isPortal)
            check(evaluate("/fail", "/empty", useHttps = false).isFailed)
            check(evaluate("/fail", "/tiny", useHttps = false).isFailed)
            val page = evaluate("/fail", "/page", useHttps = false)
            check(page.isPortal && page.redirectUrl == server.url("/page").toString())
            check(evaluate("/fail", "/ok", useHttps = false).isSuccessful)
            val pacNetwork = network().let { it.copy(links = LinkProperties(it.links).apply {
                httpProxy = ProxyInfo.buildPacProxy(Uri.parse(server.url("/pac-redirect").toString()))
            }) }
            HttpNetworkProbe { HttpNetworkProbe.Config(null, null) }.use { check(it.evaluate(pacNetwork).isSuccessful) }
            HttpNetworkProbe { HttpNetworkProbe.Config(null, null, enabled = false) }.use { check(it.evaluate(network()).isSuccessful) }
            HttpNetworkProbe { HttpNetworkProbe.Config(null, null) }.use { check(it.evaluate(network()).isFailed) }
            val requests = server.requests.toList()
            check(requests.none { it.first.startsWith("http://signin.invalid") })
            check(requests.filter { it.first != "/pac" && it.first != "/pac-redirect" }
                .all { it.second["connection"] == "close" && it.second["user-agent"] == "stock-probe-fixture" })
            check(requests.any { it.first == "/pac" })
        }
        evidence()
        System.err.println("stock-network-validation: PASS probe results/specs, metrics, HTTP decisions, retries, reports and stale evidence")
    }

    private fun evidence() {
        var observed: HostedNetworkSnapshot? = network()
        val time = AtomicLong(0)
        val entered = LinkedBlockingQueue<Int>()
        val answers = LinkedBlockingQueue<Result>()
        val validator = ValidatedNetworkSource(HostedNetworkSource { observed }, HostedNetworkProbe {
            entered.add(it.network.netId)
            answers.take()
        }, time::get)
        fun capabilities() = checkNotNull(validator.snapshot()).capabilities
        fun has(capability: Int) = capabilities().hasCapability(capability)
        fun started(id: Int = 100) { check(entered.poll(5, TimeUnit.SECONDS) == id) }
        try {
            check(!has(NetworkCapabilities.NET_CAPABILITY_VALIDATED)); started()
            answers.add(Result.PARTIAL)
            await { has(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY) }
            time.set(999); capabilities(); check(entered.isEmpty())
            time.set(1_000); capabilities(); started()
            answers.add(Result.failed(Result.PROBE_UNKNOWN))
            await { !has(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY) }
            time.set(2_999); capabilities(); check(entered.isEmpty())
            time.set(3_000); capabilities(); started()
            answers.add(Result.success(Result.PROBE_UNKNOWN))
            await { has(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            time.set(1_000_000); capabilities(); check(entered.isEmpty())
            validator.reportConnectivity(Network(100), true); check(entered.isEmpty())
            validator.reportConnectivity(Network(100), false); started()
            check(has(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            answers.add(Result(302, 1 shl Event.PROBE_HTTP))
            await { has(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) }
            check(!has(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            time.addAndGet(599_999); capabilities(); check(entered.isEmpty())
            validator.reportConnectivity(Network(100), true); started()
            observed = network(101)
            check(!has(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            answers.add(Result.success(Result.PROBE_UNKNOWN)) // Old route's result must be discarded.
            started(101)
            check(!has(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            answers.add(Result.PARTIAL)
            await { has(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY) }
            validator.reportConnectivity(Network(999), true); check(entered.isEmpty())
            observed = null; check(validator.snapshot() == null)
        } finally { validator.close() }
    }
}
