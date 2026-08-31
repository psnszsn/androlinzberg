package android.os

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format shared with `src/binder/Client.zig` and `Server.zig`: a 32-byte
 * little-endian header, extern-struct bodies, and trailing object entries and
 * Parcel data. Descriptors travel as SCM_RIGHTS on the first byte of a frame.
 */
internal object HostedBinderProtocol {
    const val MAGIC = 0x424B5A41
    const val VERSION = 3
    const val HEADER_LEN = 32

    // peer -> broker
    const val TAG_HELLO = 0
    const val TAG_TRANSACT = 1
    const val TAG_REPLY = 2
    const val TAG_RELEASE = 3
    const val TAG_LINK_DEATH = 4
    const val TAG_UNLINK_DEATH = 5
    const val TAG_REGISTER_SERVICE = 6
    const val TAG_LOOKUP_SERVICE = 7
    const val TAG_LIST_SERVICES = 8
    const val TAG_RELEASE_ACK = 9
    const val TAG_SET_PROPERTY = 10

    // broker -> peer
    const val IN_WELCOME = 0
    const val IN_PROTOCOL_ERROR = 1
    const val IN_TRANSACT = 2
    const val IN_REPLY = 3
    const val IN_OBJECT_DEAD = 4
    const val IN_OBJECT_RELEASED = 5
    const val IN_SERVICE_RESULT = 6
    const val IN_SHUTDOWN = 7
    const val IN_PROPERTY_CHANGED = 8

    const val REF_NULL = 0
    const val REF_LOCAL = 1
    const val REF_REMOTE = 2

    const val ENTRY_BINDER_NULL = 0
    const val ENTRY_BINDER_LOCAL = 1
    const val ENTRY_BINDER_REMOTE = 2
    const val ENTRY_FD = 3

    const val STATUS_OK = 0
    const val STATUS_DEAD_OBJECT = 1
    const val STATUS_FAILED = 2

    const val SERVICE_OK = 0
    const val SERVICE_NOT_FOUND = 1
    const val SERVICE_ALREADY_REGISTERED = 2
    const val SERVICE_SHUTDOWN = 3
    const val SERVICE_DENIED = 4

    const val ROLE_APP = 0
    const val ROLE_SYSTEM = 1

    const val MAX_DATA_LEN = 1 shl 20
    const val MAX_OBJECTS = 1024
    const val MAX_FDS = 64
    const val MAX_BODY_LEN = MAX_DATA_LEN + MAX_OBJECTS * 16 + 4096

    class Header(
        val tag: Int,
        val flags: Int,
        val fdCount: Int,
        val bytesLen: Int,
        val requestId: Long,
        val routingId: Long,
    )

    class Ref(val kind: Int, val value: Long) {
        companion object {
            val NULL = Ref(REF_NULL, 0)
        }
    }

    class Entry(val offset: Int, val kind: Int, val value: Long)

    class Payload(val entries: List<Entry>, val data: ByteArray)

    class Frame(val header: Header, val body: ByteBuffer, val fds: List<java.io.FileDescriptor>)

    /** Little-endian frame builder; the header is patched with the final body length. */
    class Builder(private val tag: Int, private val fdCount: Int, private val requestId: Long, private val routingId: Long) {
        private var buf: ByteBuffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)

        init {
            buf.position(HEADER_LEN)
        }

        private fun ensure(n: Int) {
            if (buf.remaining() >= n) return
            val grown = ByteBuffer.allocate(maxOf(buf.capacity() * 2, buf.position() + n)).order(ByteOrder.LITTLE_ENDIAN)
            buf.flip()
            grown.put(buf)
            buf = grown
        }

        fun u32(v: Int): Builder { ensure(4); buf.putInt(v); return this }
        fun u64(v: Long): Builder { ensure(8); buf.putLong(v); return this }
        fun bytes(b: ByteArray): Builder { ensure(b.size); buf.put(b); return this }
        fun ref(r: Ref): Builder { u32(r.kind); u32(0); u64(r.value); return this }
        fun entries(list: List<Entry>): Builder {
            for (e in list) { u32(e.offset); u32(e.kind); u64(e.value) }
            return this
        }

        fun finish(): ByteArray {
            val total = buf.position()
            val out = ByteArray(total)
            buf.flip()
            buf.get(out)
            val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0, MAGIC)
            header.putShort(4, VERSION.toShort())
            header.putShort(6, tag.toShort())
            header.putShort(8, 0)
            header.putShort(10, fdCount.toShort())
            header.putInt(12, total - HEADER_LEN)
            header.putLong(16, requestId)
            header.putLong(24, routingId)
            return out
        }
    }

    fun parseHeader(b: ByteBuffer): Header {
        val magic = b.getInt(0)
        if (magic != MAGIC) throw ProtocolException("bad magic 0x" + Integer.toHexString(magic))
        val version = b.getShort(4).toInt() and 0xffff
        if (version != VERSION) throw ProtocolException("unsupported protocol version $version")
        val bytesLen = b.getInt(12)
        if (bytesLen < 0 || bytesLen > MAX_BODY_LEN) throw ProtocolException("oversized frame $bytesLen")
        val fdCount = b.getShort(10).toInt() and 0xffff
        if (fdCount > MAX_FDS) throw ProtocolException("too many descriptors $fdCount")
        return Header(
            tag = b.getShort(6).toInt() and 0xffff,
            flags = b.getShort(8).toInt() and 0xffff,
            fdCount = fdCount,
            bytesLen = bytesLen,
            requestId = b.getLong(16),
            routingId = b.getLong(24),
        )
    }

    fun readRef(b: ByteBuffer): Ref {
        val kind = b.getInt()
        b.getInt()
        return Ref(kind, b.getLong())
    }

    /** Reads `[count]Entry` followed by `[dataLen]u8` from the cursor. */
    fun readPayload(b: ByteBuffer, count: Int, dataLen: Int): Payload {
        if (count < 0 || count > MAX_OBJECTS || dataLen < 0 || dataLen > MAX_DATA_LEN) throw ProtocolException("malformed payload")
        if (b.remaining() != count * 16 + dataLen) throw ProtocolException("payload length mismatch")
        val entries = ArrayList<Entry>(count)
        for (i in 0 until count) {
            val offset = b.getInt()
            val kind = b.getInt()
            val value = b.getLong()
            entries.add(Entry(offset, kind, value))
        }
        val data = ByteArray(dataLen)
        b.get(data)
        return Payload(entries, data)
    }

    class ProtocolException(message: String) : RuntimeException(message)
}
