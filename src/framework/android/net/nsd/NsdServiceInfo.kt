package android.net.nsd

import android.net.Network
import android.os.Parcel
import android.os.Parcelable
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Mutable DNS-SD service record following the Android 15 public contract. */
class NsdServiceInfo : Parcelable {
    private var serviceName: String? = null
    private var serviceType: String? = null
    private var host: InetAddress? = null
    private var port: Int = 0
    private var hostAddresses: List<InetAddress> = emptyList()
    private val attributes = linkedMapOf<String, ByteArray>()
    private var network: Network? = null
    private var subtypes: Set<String> = emptySet()

    fun getServiceName(): String? = serviceName
    fun setServiceName(value: String?) { serviceName = value }

    fun getServiceType(): String? = serviceType
    fun setServiceType(value: String?) { serviceType = value }

    fun getHost(): InetAddress? = host ?: hostAddresses.firstOrNull()
    fun setHost(value: InetAddress?) {
        host = value
        hostAddresses = value?.let(::listOf).orEmpty()
    }

    fun getPort(): Int = port
    fun setPort(value: Int) { port = value }

    fun getHostAddresses(): List<InetAddress> =
        Collections.unmodifiableList(hostAddresses)

    fun setHostAddresses(value: List<InetAddress>) {
        hostAddresses = value.toList()
        host = hostAddresses.firstOrNull()
    }

    fun setAttribute(key: String, value: String?) {
        setAttributeBytes(key, value?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0))
    }

    fun removeAttribute(key: String) {
        attributes.remove(key)
    }

    fun getAttributes(): Map<String, ByteArray> =
        Collections.unmodifiableMap(attributes)

    fun getNetwork(): Network? = network
    fun setNetwork(value: Network?) { network = value }

    fun setSubtypes(value: Set<String>) { subtypes = value.toSet() }
    fun getSubtypes(): Set<String> = Collections.unmodifiableSet(subtypes)

    internal fun setAttributeBytes(key: String, value: ByteArray) {
        require(key.none { it.code <= 0x20 || it.code >= 0x7f || it == '=' }) {
            "Key strings must be printable US-ASCII and must not contain '='"
        }
        require(key.toByteArray(StandardCharsets.UTF_8).size <= 9) {
            "Key length must not exceed 9 bytes"
        }
        require(key.toByteArray(StandardCharsets.UTF_8).size + value.size < 255) {
            "Key and value length must be less than 255 bytes"
        }
        attributes[key] = value.copyOf()
    }

    internal fun copy(): NsdServiceInfo = NsdServiceInfo().also { copy ->
        copy.serviceName = serviceName
        copy.serviceType = serviceType
        copy.host = host
        copy.port = port
        copy.hostAddresses = hostAddresses.toList()
        attributes.forEach { (key, value) -> copy.attributes[key] = value.copyOf() }
        copy.network = network
        copy.subtypes = subtypes.toSet()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeString(serviceName)
        destination.writeString(serviceType)
        destination.writeInt(port)
        destination.writeInt(hostAddresses.size)
        hostAddresses.forEach { destination.writeString(it.hostAddress) }
        destination.writeInt(attributes.size)
        attributes.forEach { (key, value) ->
            destination.writeString(key)
            destination.writeByteArray(value)
        }
        destination.writeInt(subtypes.size)
        subtypes.forEach(destination::writeString)
    }

    override fun toString(): String =
        "name: $serviceName, type: $serviceType, host: ${getHost()}, " +
            "port: $port, addresses: $hostAddresses, attributes: ${attributes.keys}"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<NsdServiceInfo> =
            object : Parcelable.Creator<NsdServiceInfo> {
                override fun createFromParcel(source: Parcel): NsdServiceInfo =
                    NsdServiceInfo().apply {
                        serviceName = source.readString()
                        serviceType = source.readString()
                        port = source.readInt()
                        val addresses = mutableListOf<InetAddress>()
                        repeat(source.readInt()) {
                            source.readString()?.let { address ->
                                runCatching { InetAddress.getByName(address) }
                                    .getOrNull()
                                    ?.let(addresses::add)
                            }
                        }
                        setHostAddresses(addresses)
                        repeat(source.readInt()) {
                            val key = source.readString()
                            val value = source.createByteArray()
                            if (key != null && value != null) attributes[key] = value
                        }
                        val restoredSubtypes = linkedSetOf<String>()
                        repeat(source.readInt()) {
                            source.readString()?.let(restoredSubtypes::add)
                        }
                        subtypes = restoredSubtypes
                    }

                override fun newArray(size: Int): Array<NsdServiceInfo?> = arrayOfNulls(size)
            }
    }
}
