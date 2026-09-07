package dev.artzig.system

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.Uri
import android.net.captiveportal.CaptivePortalProbeResult
import android.net.metrics.ValidationProbeEvent
import android.os.SystemClock
import android.provider.Settings
import android.provider.DeviceConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun interface HostedNetworkProbe {
    fun evaluate(network: HostedNetworkSnapshot): CaptivePortalProbeResult
}

/** NetworkMonitor's validation/revalidation state at the Linux discovery boundary.
 * The stock monitor also owns DHCP/APF, NetworkStack callbacks, private-DNS and data-stall
 * machinery that this host does not run. Keep probe evidence separate from route discovery;
 * discard results from replaced routes, retry failures with its 1s..10min backoff, and retain
 * success until a network change or an application report requests revalidation. */
internal class ValidatedNetworkSource(
    private val source: HostedNetworkSource,
    private val probe: HostedNetworkProbe,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : HostedNetworkSource, AutoCloseable {
    private class Evaluation(val network: HostedNetworkSnapshot) {
        var result = CaptivePortalProbeResult.failed(CaptivePortalProbeResult.PROBE_UNKNOWN)
        var due = 0L
        var retry = 1_000L
        var running = false
    }
    private val lock = Any()
    private var active: Evaluation? = null
    private var closed = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NetworkValidation").apply { isDaemon = true } }

    override fun supportsLegacyType(type: Int): Boolean = source.supportsLegacyType(type)

    override fun snapshot(): HostedNetworkSnapshot? {
        val observed = source.snapshot()
        return synchronized(lock) {
            if (closed || observed == null) {
                active = null
                return@synchronized null
            }
            var evaluation = active
            if (evaluation == null || evaluation.network.network != observed.network || evaluation.network.links != observed.links) {
                evaluation = Evaluation(observed)
                active = evaluation
            }
            if (!evaluation.running && now() >= evaluation.due) startLocked(evaluation)
            val capabilities = NetworkCapabilities(observed.capabilities)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)
            when {
                evaluation.result.isSuccessful -> capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                evaluation.result.isPortal -> capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                evaluation.result.isPartialConnectivity -> capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)
            }
            observed.copy(capabilities = capabilities)
        }
    }

    private fun startLocked(evaluation: Evaluation) {
        evaluation.running = true
        worker.execute {
            if (synchronized(lock) { closed || active !== evaluation }) return@execute
            val result = try { probe.evaluate(evaluation.network) } catch (e: Exception) {
                if (synchronized(lock) { closed }) return@execute
                if (e !is IOException) throw e
                CaptivePortalProbeResult.failed(CaptivePortalProbeResult.PROBE_UNKNOWN)
            }
            synchronized(lock) {
                if (closed || active !== evaluation) return@synchronized
                evaluation.result = result
                evaluation.running = false
                evaluation.due = when {
                    result.isSuccessful -> Long.MAX_VALUE
                    result.isPortal -> now() + 600_000L
                    else -> (now() + evaluation.retry).also { evaluation.retry = minOf(600_000L, evaluation.retry * 2) }
                }
            }
        }
    }

    /** ConnectivityService.handleReportNetworkConnectivity revalidates a contradictory report. */
    override fun reportConnectivity(network: Network, hasConnectivity: Boolean): Boolean = synchronized(lock) {
        val evaluation = active ?: return@synchronized true
        if (evaluation.network.network == network && evaluation.result.isSuccessful != hasConnectivity) {
            evaluation.due = 0L
            evaluation.retry = 1_000L
            if (!evaluation.running) startLocked(evaluation)
        }
        true
    }

    override fun close() {
        synchronized(lock) { closed = true; active = null }
        worker.shutdownNow()
        if (probe is AutoCloseable) probe.close()
    }
}

/** Pinned NetworkMonitor.sendHttpProbe/sendHttpAndHttpsParallelWithFallbackProbes.
 * Uses stock Network connections and probe-result types. The hosted image has no NetworkStack
 * RRO, CAPPORT agent, PAC resolver or private-DNS monitor; this follows the default HTTP/HTTPS
 * and fallback evaluation, including the default empty/small HTTP 200 failure rule. */
internal class HttpNetworkProbe(private val config: () -> Config) : HostedNetworkProbe, AutoCloseable {
    data class Config(
        val https: URL?, val http: URL?, val fallback: URL? = null,
        val enabled: Boolean = true, val useHttps: Boolean = true, val userAgent: String? = null,
    )
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "NetworkProbe").apply { isDaemon = true } }
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun evaluate(network: HostedNetworkSnapshot): CaptivePortalProbeResult {
        val settings = config()
        if (!settings.enabled) return CaptivePortalProbeResult.success(CaptivePortalProbeResult.PROBE_UNKNOWN)
        val proxy = network.links.httpProxy
        val pac = proxy?.pacFileUrl
        if (pac != null && pac != Uri.EMPTY) {
            val url = urlOrNull(pac.toString()) ?: return failed()
            return request(network.network, url, ValidationProbeEvent.PROBE_PAC, null, settings)
        }
        val https = settings.https ?: return failed()
        val http = settings.http ?: return failed()
        if (!settings.useHttps) return request(network.network, http, ValidationProbeEvent.PROBE_HTTP, proxy, settings)
        val latch = CountDownLatch(2)
        val httpsResult = AtomicReference(failed())
        val httpResult = AtomicReference(failed())
        fun launch(url: URL, type: Int, result: AtomicReference<CaptivePortalProbeResult>) = workers.submit<CaptivePortalProbeResult> {
            try {
                request(network.network, url, type, proxy, settings).also {
                    result.set(it)
                    if (conclusive(it)) while (latch.count > 0) latch.countDown()
                }
            } finally { latch.countDown() }
        }
        val secure = launch(https, ValidationProbeEvent.PROBE_HTTPS, httpsResult)
        val plain = launch(http, ValidationProbeEvent.PROBE_HTTP, httpResult)
        try {
            latch.await(3_000, TimeUnit.MILLISECONDS)
            if (conclusive(httpResult.get())) return httpResult.get()
            if (conclusive(httpsResult.get())) return httpsResult.get()
            settings.fallback?.let {
                val fallback = request(network.network, it, ValidationProbeEvent.PROBE_FALLBACK, proxy, settings)
                if (fallback.isPortal) return fallback
            }
            val httpAnswer = plain.get()
            if (httpAnswer.isPortal) return httpAnswer
            val httpsAnswer = secure.get()
            return if (httpsAnswer.isFailed && httpAnswer.isSuccessful) CaptivePortalProbeResult.PARTIAL else httpsAnswer
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failed()
        } catch (e: ExecutionException) {
            throw IllegalStateException("Network validation worker failed", e.cause)
        }
    }

    private fun request(network: Network, url: URL, type: Int, proxy: ProxyInfo?, config: Config): CaptivePortalProbeResult {
        var connection: HttpURLConnection? = null
        var status = CaptivePortalProbeResult.FAILED_CODE
        var redirect: String? = null
        try {
            val conn = network.openConnection(url, proxy?.makeProxy() ?: Proxy.NO_PROXY) as HttpURLConnection
            connection = conn
            connections.add(conn)
            conn.instanceFollowRedirects = type == ValidationProbeEvent.PROBE_PAC
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            config.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            status = conn.responseCode
            redirect = conn.getHeaderField("location")
            if (status == 200) {
                val length = conn.contentLengthLong
                if (type == ValidationProbeEvent.PROBE_PAC) status = CaptivePortalProbeResult.SUCCESS_CODE
                else if (length == -1L) {
                    if (conn.inputStream.read() == -1) status = CaptivePortalProbeResult.FAILED_CODE
                } else if (length <= 4L) status = CaptivePortalProbeResult.FAILED_CODE
            }
        } catch (_: IOException) {
            // Preserve the response already observed if reading its body subsequently failed.
        } finally {
            connection?.let { it.disconnect(); connections.remove(it) }
        }
        if (CaptivePortalProbeResult.isPortalCode(status) && redirect.isNullOrEmpty()) redirect = url.toString()
        return CaptivePortalProbeResult(status, redirect, url.toString(), 1 shl type)
    }

    override fun close() {
        connections.forEach { it.disconnect() }
        workers.shutdownNow()
    }

    companion object {
        private fun failed() = CaptivePortalProbeResult.failed(CaptivePortalProbeResult.PROBE_UNKNOWN)
        private fun conclusive(result: CaptivePortalProbeResult) =
            result.isPortal || (result.isConcludedFromHttps && result.isSuccessful)
        private fun urlOrNull(value: String): URL? = try {
            URL(value).takeIf { it.protocol == "http" || it.protocol == "https" }
        } catch (_: java.net.MalformedURLException) { null }

        fun forContext(context: Context): HttpNetworkProbe = HttpNetworkProbe {
            val resolver = context.contentResolver
            fun setting(name: String, default: String): URL? =
                urlOrNull(Settings.Global.getString(resolver, name) ?: default)
            val firstFallback = Settings.Global.getString(resolver, Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL)
            val fallbacks = if (firstFallback.isNullOrEmpty()) listOf(
                "http://www.google.com/gen_204", "http://play.googleapis.com/generate_204")
            else (firstFallback + "," + DeviceConfig.getString(DeviceConfig.NAMESPACE_CONNECTIVITY,
                "captive_portal_other_fallback_urls", "")).split(',')
            val fallbackUrls = fallbacks.mapNotNull(::urlOrNull)
            // Pinned NetworkStack resource defaults, Global URL/mode settings and DeviceConfig.
            Config(
                setting(Settings.Global.CAPTIVE_PORTAL_HTTPS_URL, "https://www.google.com/generate_204"),
                setting(Settings.Global.CAPTIVE_PORTAL_HTTP_URL, "http://connectivitycheck.gstatic.com/generate_204"),
                fallbackUrls.takeIf { it.isNotEmpty() }?.let { it[kotlin.random.Random.nextInt(it.size)] },
                Settings.Global.getInt(resolver, Settings.Global.CAPTIVE_PORTAL_MODE,
                    Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT) != Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE,
                DeviceConfig.getInt(DeviceConfig.NAMESPACE_CONNECTIVITY, "captive_portal_use_https", 1) == 1,
                DeviceConfig.getString(DeviceConfig.NAMESPACE_CONNECTIVITY, "captive_portal_user_agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36"),
            )
        }
    }
}
