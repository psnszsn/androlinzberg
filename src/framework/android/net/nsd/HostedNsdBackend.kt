package android.net.nsd

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Host seam replacing Android's native mDNS daemon and NsdService connector. */
interface HostedNsdBackend {
    interface Observer {
        fun onServiceFound(serviceInfo: NsdServiceInfo, updated: Boolean)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
        fun onFailure(detail: String)
    }

    fun discover(serviceType: String, observer: Observer): Closeable
    fun resolve(serviceInfo: NsdServiceInfo): NsdServiceInfo?
}

object HostedNsdBackends {
    @Volatile
    private var testBackend: HostedNsdBackend? = null

    @JvmStatic
    fun create(): HostedNsdBackend = testBackend ?: AvahiHostedNsdBackend()

    @JvmStatic
    fun installForTests(backend: HostedNsdBackend?) {
        testBackend = backend
    }
}

/**
 * Production DNS-SD adapter. avahi-browse owns multicast/D-Bus integration;
 * this class translates its stable parsable output into Android service state.
 */
class AvahiHostedNsdBackend : HostedNsdBackend {
    private data class ServiceKey(val name: String, val type: String, val domain: String)

    private val records = ConcurrentHashMap<ServiceKey, NsdServiceInfo>()

    override fun discover(serviceType: String, observer: HostedNsdBackend.Observer): Closeable {
        val process = ProcessBuilder(
            "avahi-browse",
            "--parsable",
            "--resolve",
            "--no-fail",
            normalizeServiceType(serviceType),
        ).redirectErrorStream(true).start()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use {
                    reader -> reader.lineSequence().forEach { line -> consume(line, observer) }
                }
                val status = process.waitFor()
                if (status != 0 && !stopped.get()) {
                    observer.onFailure("avahi-browse exited with status $status")
                }
            } catch (throwable: Throwable) {
                if (!stopped.get()) observer.onFailure(throwable.toString())
            }
        }, "HostedNsd-${serviceType.take(32)}").apply {
            isDaemon = true
            start()
        }
        return Closeable {
            if (stopped.compareAndSet(false, true)) {
                process.destroy()
                thread.interrupt()
            }
        }
    }

    override fun resolve(serviceInfo: NsdServiceInfo): NsdServiceInfo? {
        val name = serviceInfo.getServiceName() ?: return null
        val type = normalizeServiceType(serviceInfo.getServiceType() ?: return null)
        return records.entries
            .firstOrNull { (key, _) -> key.name == name && normalizeServiceType(key.type) == type }
            ?.value
            ?.copy()
            ?: serviceInfo.takeIf { it.getHost() != null || it.getHostAddresses().isNotEmpty() }
                ?.copy()
    }

    private fun consume(line: String, observer: HostedNsdBackend.Observer) {
        val fields = splitAvahiFields(line)
        if (fields.size < 6) {
            if (line.isNotBlank() && !line.startsWith("+;") && !line.startsWith("-;")) {
                Log.w(TAG, "avahi-browse: $line")
            }
            return
        }
        val event = fields[0]
        val key = ServiceKey(fields[3], fields[4], fields[5])
        when (event) {
            "=" -> {
                if (fields.size < 9) return
                val address = runCatching { InetAddress.getByName(fields[7]) }.getOrNull()
                    ?: return
                val port = fields[8].toIntOrNull() ?: return
                var updated = false
                val info = records.compute(key) { _, prior ->
                    updated = prior != null
                    val next = prior?.copy() ?: NsdServiceInfo().apply {
                        setServiceName(key.name)
                        setServiceType(normalizeServiceType(key.type))
                    }
                    next.setPort(port)
                    next.setHostAddresses((next.getHostAddresses() + address).distinct())
                    fields.drop(9).forEach { txt ->
                        val normalized = txt.removeSurrounding("\"")
                        val separator = normalized.indexOf('=')
                        val attributeKey = if (separator >= 0) {
                            normalized.substring(0, separator)
                        } else {
                            normalized
                        }
                        val value = if (separator >= 0) {
                            normalized.substring(separator + 1).toByteArray(StandardCharsets.UTF_8)
                        } else {
                            ByteArray(0)
                        }
                        runCatching { next.setAttributeBytes(attributeKey, value) }
                    }
                    next
                } ?: return
                observer.onServiceFound(info.copy(), updated)
            }
            "-" -> records.remove(key)?.let { observer.onServiceLost(it.copy()) }
        }
    }

    private fun normalizeServiceType(value: String): String = value.trimEnd('.')

    private fun splitAvahiFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == ';' -> {
                    fields += current.toString()
                    current.setLength(0)
                    index++
                }
                character == '\\' && index + 3 < line.length &&
                    line.substring(index + 1, index + 4).all { it in '0'..'7' } -> {
                    current.append(line.substring(index + 1, index + 4).toInt(8).toChar())
                    index += 4
                }
                character == '\\' && index + 1 < line.length -> {
                    current.append(line[index + 1])
                    index += 2
                }
                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        fields += current.toString()
        return fields
    }

    companion object {
        private const val TAG = "AvahiHostedNsd"
    }
}

/** Deterministic adapter used to test Android listener ordering and state. */
class InMemoryHostedNsdBackend : HostedNsdBackend {
    private data class Registration(
        val serviceType: String,
        val observer: HostedNsdBackend.Observer,
    )

    private val services = linkedMapOf<String, NsdServiceInfo>()
    private val registrations = CopyOnWriteArrayList<Registration>()

    @Synchronized
    fun publish(serviceInfo: NsdServiceInfo) {
        val key = key(serviceInfo)
        val updated = services.put(key, serviceInfo.copy()) != null
        registrations
            .filter { normalize(it.serviceType) == normalize(serviceInfo.getServiceType().orEmpty()) }
            .forEach { it.observer.onServiceFound(serviceInfo.copy(), updated) }
    }

    @Synchronized
    fun remove(serviceInfo: NsdServiceInfo) {
        val removed = services.remove(key(serviceInfo)) ?: return
        registrations
            .filter { normalize(it.serviceType) == normalize(removed.getServiceType().orEmpty()) }
            .forEach { it.observer.onServiceLost(removed.copy()) }
    }

    override fun discover(serviceType: String, observer: HostedNsdBackend.Observer): Closeable {
        val registration = Registration(serviceType, observer)
        registrations += registration
        synchronized(this) {
            services.values
                .filter { normalize(it.getServiceType().orEmpty()) == normalize(serviceType) }
                .forEach { observer.onServiceFound(it.copy(), false) }
        }
        return Closeable { registrations.remove(registration) }
    }

    @Synchronized
    override fun resolve(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        services[key(serviceInfo)]?.copy()

    private fun key(info: NsdServiceInfo): String =
        "${info.getServiceName()}\u0000${normalize(info.getServiceType().orEmpty())}"

    private fun normalize(value: String): String = value.trimEnd('.')
}
