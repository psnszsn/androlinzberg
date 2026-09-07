package dev.artzig

import android.net.nsd.NsdServiceInfo

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** Host seam replacing Android's native mDNS daemon and NsdService connector. */
interface HostedNsdBackend {
    interface Observer {
        fun onServiceFound(serviceInfo: NsdServiceInfo, updated: Boolean)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
        fun onFailure(detail: String)
    }

    fun discover(serviceType: String, observer: Observer, interfaceName: String? = null): Closeable
}

/**
 * Production DNS-SD adapter. avahi-browse owns multicast/D-Bus integration;
 * this class translates its stable parsable output into Android service state.
 */
class AvahiHostedNsdBackend : HostedNsdBackend {
    override fun discover(serviceType: String, observer: HostedNsdBackend.Observer, interfaceName: String?): Closeable {
        val command = mutableListOf("avahi-browse", "--parsable", "--resolve", "--no-fail")
        if (interfaceName != null) throw ArtzigTestDiagnostics.unsupported(
            "dev.artzig.AvahiHostedNsdBackend", "discover",
            "Interface-scoped DNS-SD requires an Avahi D-Bus connection")
        command += serviceType.trimEnd('.')
        val records = AvahiNsdRecords(observer)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use {
                    reader -> reader.lineSequence().forEach { line -> records.consume(line) }
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

    companion object {
        private const val TAG = "AvahiHostedNsd"
    }
}

/** avahi-browse.c print_service_line/service_resolver_callback, domain.c
 * avahi_escape_label, and strlst.c avahi_string_list_to_string define this wire
 * text. Escapes are decimal bytes; TXT strings are quoted and space-separated.
 * Reference: https://github.com/avahi/avahi/tree/master/avahi-utils
 * Records belong to one connection; IPv4/IPv6 removals are independent.
 */
internal class AvahiNsdRecords(private val observer: HostedNsdBackend.Observer) {
    private data class Key(val name: String, val type: String, val domain: String, val iface: String)
    private class Record(val info: NsdServiceInfo) {
        val protocols = HashSet<String>()
        val addresses = HashMap<String, InetAddress>()
    }
    private val records = HashMap<Key, Record>()

    fun consume(line: String) {
        // The final field contains quoted TXT strings, including literal semicolons.
        val fields = line.split(';', limit = 10)
        if (fields.size < 6 || fields[0] !in listOf("+", "=", "-")) return
        try {
            val key = Key(String(unescape(fields[3]), StandardCharsets.UTF_8),
                fields[4].trimEnd('.'), fields[5], fields[1])
            val protocol = fields[2]
            if (fields[0] == "-") {
                val record = records[key] ?: return
                record.protocols.remove(protocol)
                record.addresses.remove(protocol)
                record.info.hostAddresses = record.addresses.values.toList()
                if (record.protocols.isEmpty()) {
                    records.remove(key)
                    observer.onServiceLost(record.info.copy())
                } else observer.onServiceFound(record.info.copy(), true)
                return
            }
            val updated = records.containsKey(key)
            val record = records.getOrPut(key) {
                Record(NsdServiceInfo(key.name, key.type).apply {
                    interfaceIndex = java.net.NetworkInterface.getByName(key.iface)?.index ?: 0
                })
            }
            record.protocols += protocol
            if (fields[0] == "=") {
                if (fields.size != 10) return
                val port = fields[8].toIntOrNull()?.takeIf { it in 1..65535 } ?: return
                record.addresses[protocol] = android.net.InetAddresses.parseNumericAddress(fields[7])
                record.info.apply {
                    this.port = port
                    hostname = fields[6].removeSuffix(".local").removeSuffix(".local.")
                    hostAddresses = record.addresses.values.toList()
                    attributes.keys.toList().forEach(::removeAttribute)
                    for (bytes in txtStrings(fields[9])) {
                        val split = bytes.indexOf('='.code.toByte())
                        val end = if (split < 0) bytes.size else split
                        val name = String(bytes, 0, end, StandardCharsets.US_ASCII)
                        val value = if (split < 0) null else bytes.copyOfRange(split + 1, bytes.size)
                        try { setAttribute(name, value) } catch (_: IllegalArgumentException) {
                            // A malformed peer TXT key is not an Android attribute.
                        }
                    }
                }
            }
            observer.onServiceFound(record.info.copy(), updated)
        } catch (failure: IllegalArgumentException) {
            Log.w("AvahiHostedNsd", "Malformed DNS-SD record", failure)
        }
    }

    private fun txtStrings(text: String): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var index = 0
        while (index < text.length) {
            while (index < text.length && text[index] == ' ') index++
            if (index == text.length) break
            require(text[index++] == '"')
            val start = index
            while (index < text.length && text[index] != '"') {
                index += if (text[index] == '\\') 2 else 1
            }
            require(index < text.length)
            result += unescape(text.substring(start, index))
            index++
        }
        return result
    }

    private fun unescape(text: String): ByteArray {
        val source = text.toByteArray(StandardCharsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < source.size) {
            if (source[index] != '\\'.code.toByte()) {
                out.write(source[index++].toInt())
                continue
            }
            index++
            require(index < source.size)
            if (index + 2 < source.size && (index..index + 2).all { source[it] in 48..57 }) {
                val value = (source[index] - 48) * 100 + (source[index + 1] - 48) * 10 + source[index + 2] - 48
                require(value in 0..255)
                out.write(value)
                index += 3
            } else out.write(source[index++].toInt())
        }
        return out.toByteArray()
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

    override fun discover(serviceType: String, observer: HostedNsdBackend.Observer, interfaceName: String?): Closeable {
        val registration = Registration(serviceType, observer)
        registrations += registration
        synchronized(this) {
            services.values
                .filter { normalize(it.getServiceType().orEmpty()) == normalize(serviceType) }
                .forEach { observer.onServiceFound(it.copy(), false) }
        }
        return Closeable { registrations.remove(registration) }
    }

    private fun key(info: NsdServiceInfo): String =
        "${info.getServiceName()}\u0000${normalize(info.getServiceType().orEmpty())}"

    private fun normalize(value: String): String = value.trimEnd('.')
}

/** A Binder value snapshot also for in-process backend callbacks. */
internal fun NsdServiceInfo.copy(): NsdServiceInfo {
    val parcel = android.os.Parcel.obtain()
    try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return NsdServiceInfo.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}
