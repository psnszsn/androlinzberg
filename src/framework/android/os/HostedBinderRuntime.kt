package android.os

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructCmsghdr
import android.system.StructMsghdr
import android.system.UnixSocketAddress
import android.util.Log
import java.io.FileDescriptor
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * This process's connection to the hosted Binder broker.
 *
 * Owns the socket, the local-object export table (strong references while a
 * remote peer holds the object), the proxy cache, in-flight synchronous calls,
 * and incoming dispatch. Incoming transactions run on a bounded pool, except
 * nested callbacks, which the broker addresses to the request a thread is
 * waiting on so they execute on that waiting thread, as they do on Android.
 *
 * Socket I/O uses libcore `Os.sendmsg`/`Os.recvmsg`, which carry SCM_RIGHTS.
 */
class HostedBinderRuntime private constructor(
    private val channel: Channel,
    val peerId: Int,
    val pid: Int,
    /** Android uid the broker assigned to this process for the session. */
    val uid: Int,
    /** [HostedBinderProtocol.ROLE_APP] or [HostedBinderProtocol.ROLE_SYSTEM]. */
    val role: Int,
    /** Android package name this process presented at hello. */
    val packageName: String,
) {
    companion object {
        private const val TAG = "HostedBinder"
        const val SOCKET_ENV = "AZK_BINDER_SOCKET"
        const val COOKIE_ENV = "AZK_BINDER_COOKIE"
        const val SYSTEM_COOKIE_ENV = "AZK_BINDER_SYSTEM_COOKIE"
        const val PACKAGE_ENV = "AZK_BINDER_PACKAGE"

        @Volatile private var configuredPackage: String? = null
        @Volatile private var configuredSystem = false

        /** Headless hosts (the `--app-process` service host) have no window and
         * no reason to live once the broker is gone; losing the connection means
         * the session is over, so the process exits instead of orphaning. */
        @Volatile
        @JvmStatic
        var exitProcessOnDisconnect = false

        /**
         * Declares this process's identity and connects. Must run before any
         * code reads [Process.myUid]; idempotent for the same identity.
         */
        @JvmStatic
        fun bootstrap(packageName: String, system: Boolean): HostedBinderRuntime {
            synchronized(startLock) {
                instance?.let {
                    check(it.packageName == packageName && (it.role == HostedBinderProtocol.ROLE_SYSTEM) == system) {
                        "hosted Binder runtime already connected as " + it.packageName
                    }
                    return it
                }
                configuredPackage = packageName
                configuredSystem = system
            }
            return get()
        }

        /** The broker-assigned uid, or null before [bootstrap]/[get] connected. */
        @JvmStatic
        fun assignedUid(): Int? = instance?.uid

        private val startLock = Any()
        @Volatile private var instance: HostedBinderRuntime? = null

        @JvmStatic
        fun isEnabled(): Boolean = System.getenv(SOCKET_ENV) != null

        /** Connects on first use. */
        @JvmStatic
        fun get(): HostedBinderRuntime {
            instance?.let { return it }
            synchronized(startLock) {
                instance?.let { return it }
                val runtime = connect()
                instance = runtime
                return runtime
            }
        }

        /** Like [get], but null (logged once) when the broker cannot be reached. */
        @JvmStatic
        fun getOrNull(): HostedBinderRuntime? {
            instance?.let { return it }
            return try {
                get()
            } catch (e: RuntimeException) {
                synchronized(startLock) {
                    if (!unavailableLogged) {
                        unavailableLogged = true
                        Log.w(TAG, "hosted Binder broker unavailable: " + e.message)
                    }
                }
                null
            }
        }

        private var unavailableLogged = false

        private fun connect(): HostedBinderRuntime {
            val path = System.getenv(SOCKET_ENV) ?: throw IllegalStateException("$SOCKET_ENV is not set")
            val system = configuredSystem
            val cookie = (if (system) System.getenv(SYSTEM_COOKIE_ENV) else System.getenv(COOKIE_ENV)) ?: ""
            val packageName = configuredPackage ?: System.getenv(PACKAGE_ENV) ?: run {
                Log.w(TAG, "no package identity configured; using an anonymous one")
                "anonymous." + Os.getpid()
            }
            val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM or OsConstants.SOCK_CLOEXEC, 0)
            try {
                Os.connect(fd, UnixSocketAddress.createFileSystem(path))
            } catch (e: ErrnoException) {
                try { Os.close(fd) } catch (ignored: ErrnoException) {}
                throw IllegalStateException("cannot connect to binder broker at $path", e)
            }
            val channel = Channel(fd)
            val hello = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_HELLO, 0, 0, 0)
            val cookieBytes = cookie.toByteArray(Charsets.UTF_8)
            val packageBytes = (if (system) "" else packageName).toByteArray(Charsets.UTF_8)
            hello.u32(cookieBytes.size).u32(packageBytes.size).bytes(cookieBytes).bytes(packageBytes)
            channel.writeFrame(hello.finish(), emptyList())
            val welcome = channel.readFrame() ?: throw IllegalStateException("binder broker closed during hello")
            if (welcome.header.tag != HostedBinderProtocol.IN_WELCOME) {
                throw IllegalStateException("binder broker rejected hello (tag " + welcome.header.tag + ")")
            }
            val body = welcome.body
            val peerId = body.getInt()
            val pid = body.getInt()
            val uid = body.getInt()
            val role = body.getInt()
            val runtime = HostedBinderRuntime(channel, peerId, pid, uid, role, if (system) "android" else packageName)
            runtime.start()
            return runtime
        }
    }

    /** Per-global-id proxy bookkeeping shared with the Cleaner action. */
    class ProxyState internal constructor(val globalId: Long) {
        /** Deliveries consumed by the live proxy; returned to the broker on release. */
        internal var deliveries = 0
        @Volatile internal var dead = false
    }

    private class ProxyRecord(val ref: WeakReference<BinderProxy>, val state: ProxyState)

    private class IncomingTransact(
        val targetKind: Int,
        val targetId: Long,
        val code: Int,
        val flags: Int,
        val callerPid: Int,
        val callerUid: Int,
        val routingId: Long,
        val payload: HostedBinderProtocol.Payload,
        val fds: List<FileDescriptor>,
    )

    private class IncomingReply(
        val status: Int,
        val handled: Boolean,
        val binderStatus: Int,
        val payload: HostedBinderProtocol.Payload,
        val fds: List<FileDescriptor>,
    )

    private class ServiceResult(val status: Int, val ref: HostedBinderProtocol.Ref, val names: ByteArray)

    private class PendingCall {
        val lock = java.lang.Object()
        var reply: IncomingReply? = null
        var service: ServiceResult? = null
        var failure: Throwable? = null
        val nested = ArrayDeque<IncomingTransact>()
    }

    private val lock = Any()
    private val localById = HashMap<Long, Binder>()
    private val localIds = IdentityHashMap<Binder, Long>()
    /** Local references sent per export; compared with the broker's count on release. */
    private val exportUses = HashMap<Long, Long>()
    private var nextLocalId = 1L
    private val proxies = HashMap<Long, ProxyRecord>()
    private val pending = HashMap<Long, PendingCall>()
    private val nextRequestId = AtomicLong(1)
    private val cleaner = Cleaner.create()
    @Volatile private var disconnected = false
    private val onewayQueues = HashMap<Long, SerialQueue>()
    private val servicing = ThreadLocal<ArrayDeque<Long>>()
    private val threadIndex = AtomicInteger()
    // Grows on demand: a bounded pool deadlocks once every thread is parked in
    // an outgoing call whose completion needs an incoming non-nested callback.
    private val executor = ThreadPoolExecutor(
        0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r ->
        Thread(r, "HostedBinder:" + threadIndex.incrementAndGet()).apply { isDaemon = true }
    }

    private fun start() {
        Thread({ readLoop() }, "HostedBinderReader").apply { isDaemon = true }.start()
    }

    fun isDisconnected(): Boolean = disconnected

    // ---------------------------------------------------------------- objects

    private fun exportLocal(binder: Binder): Long = synchronized(lock) {
        val id = localIds[binder] ?: run {
            val fresh = nextLocalId++
            localIds[binder] = fresh
            localById[fresh] = binder
            fresh
        }
        exportUses[id] = (exportUses[id] ?: 0L) + 1
        id
    }

    private fun proxyFor(globalId: Long): BinderProxy = synchronized(lock) {
        proxies[globalId]?.let { record ->
            record.ref.get()?.let { proxy ->
                record.state.deliveries++
                return proxy
            }
        }
        val state = ProxyState(globalId)
        state.deliveries = 1
        val proxy = BinderProxy(this, globalId, state)
        proxies[globalId] = ProxyRecord(WeakReference(proxy), state)
        cleaner.register(proxy, ReleaseAction(this, state))
        proxy
    }

    private class ReleaseAction(val runtime: HostedBinderRuntime, val state: ProxyState) : Runnable {
        override fun run() = runtime.releaseProxy(state)
    }

    private fun releaseProxy(state: ProxyState) {
        val count: Int
        synchronized(lock) {
            if (proxies[state.globalId]?.state === state) proxies.remove(state.globalId)
            count = state.deliveries
            state.deliveries = 0
        }
        if (count <= 0 || disconnected) return
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_RELEASE, 0, 0, 0)
        b.ref(HostedBinderProtocol.Ref(HostedBinderProtocol.REF_REMOTE, state.globalId)).u32(count).u32(0)
        try {
            channel.writeFrame(b.finish(), emptyList())
        } catch (e: ErrnoException) {
            // The connection is gone; the broker reclaims everything on disconnect.
        }
    }

    private fun refFor(binder: IBinder?): HostedBinderProtocol.Ref = when (binder) {
        null -> HostedBinderProtocol.Ref.NULL
        is BinderProxy -> {
            check(binder.runtime === this) { "BinderProxy belongs to another runtime" }
            HostedBinderProtocol.Ref(HostedBinderProtocol.REF_REMOTE, binder.globalId)
        }
        is Binder -> HostedBinderProtocol.Ref(HostedBinderProtocol.REF_LOCAL, exportLocal(binder))
        else -> throw IllegalArgumentException("cannot send " + binder.javaClass.name + " across processes")
    }

    private fun binderFor(ref: HostedBinderProtocol.Ref): IBinder? = when (ref.kind) {
        HostedBinderProtocol.REF_NULL -> null
        HostedBinderProtocol.REF_LOCAL -> synchronized(lock) { localById[ref.value] }
        HostedBinderProtocol.REF_REMOTE -> proxyFor(ref.value)
        else -> throw HostedBinderProtocol.ProtocolException("bad reference kind " + ref.kind)
    }

    /** Flattens a Parcel's side table into wire entries; descriptors are appended to `fds`. */
    private fun flatten(parcel: Parcel?, fds: MutableList<FileDescriptor>): List<HostedBinderProtocol.Entry> {
        if (parcel == null) return emptyList()
        val out = ArrayList<HostedBinderProtocol.Entry>()
        for (e in parcel.objectEntries()) {
            if (e.kind == Parcel.ObjectEntry.KIND_BINDER) {
                val ref = refFor(e.binder)
                val kind = when (ref.kind) {
                    HostedBinderProtocol.REF_LOCAL -> HostedBinderProtocol.ENTRY_BINDER_LOCAL
                    HostedBinderProtocol.REF_REMOTE -> HostedBinderProtocol.ENTRY_BINDER_REMOTE
                    else -> HostedBinderProtocol.ENTRY_BINDER_NULL
                }
                out.add(HostedBinderProtocol.Entry(e.offset, kind, ref.value))
            } else {
                if (fds.size >= HostedBinderProtocol.MAX_FDS) throw IllegalArgumentException("too many file descriptors in Parcel")
                fds.add(e.fd)
                out.add(HostedBinderProtocol.Entry(e.offset, HostedBinderProtocol.ENTRY_FD, (fds.size - 1).toLong()))
            }
        }
        if (out.size > HostedBinderProtocol.MAX_OBJECTS) throw IllegalArgumentException("too many objects in Parcel")
        return out
    }

    /** Fills `parcel` from a received payload; received descriptors become Parcel-owned. */
    private fun inflate(parcel: Parcel, payload: HostedBinderProtocol.Payload, fds: List<FileDescriptor>) {
        val objects = ArrayList<Parcel.ObjectEntry>(payload.entries.size)
        val used = BooleanArray(fds.size)
        var adopted = false
        try {
            for (e in payload.entries) {
                when (e.kind) {
                    HostedBinderProtocol.ENTRY_BINDER_NULL ->
                        objects.add(Parcel.ObjectEntry(e.offset, Parcel.ObjectEntry.KIND_BINDER, null, null, false))
                    HostedBinderProtocol.ENTRY_BINDER_LOCAL ->
                        objects.add(Parcel.ObjectEntry(e.offset, Parcel.ObjectEntry.KIND_BINDER,
                            binderFor(HostedBinderProtocol.Ref(HostedBinderProtocol.REF_LOCAL, e.value)), null, false))
                    HostedBinderProtocol.ENTRY_BINDER_REMOTE ->
                        objects.add(Parcel.ObjectEntry(e.offset, Parcel.ObjectEntry.KIND_BINDER, proxyFor(e.value), null, false))
                    HostedBinderProtocol.ENTRY_FD -> {
                        val ordinal = e.value.toInt()
                        if (ordinal < 0 || ordinal >= fds.size) throw HostedBinderProtocol.ProtocolException("descriptor ordinal out of range")
                        val fd = if (used[ordinal]) Os.dup(fds[ordinal]) else fds[ordinal]
                        used[ordinal] = true
                        objects.add(Parcel.ObjectEntry(e.offset, Parcel.ObjectEntry.KIND_FILE_DESCRIPTOR, null, fd, true))
                    }
                    else -> throw HostedBinderProtocol.ProtocolException("bad entry kind " + e.kind)
                }
            }
            parcel.adoptWire(payload.data, objects)
            adopted = true
        } finally {
            if (adopted) {
                for (i in fds.indices) if (!used[i]) closeQuietly(fds[i])
            } else {
                // Nothing took ownership: close every received descriptor,
                // including duplicates made for repeated ordinals.
                for (fd in fds) closeQuietly(fd)
                for (o in objects) if (o.ownsFd && o.fd != null && !fds.contains(o.fd)) closeQuietly(o.fd)
            }
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try { Os.close(fd) } catch (ignored: ErrnoException) {}
    }

    // ------------------------------------------------------------ transactions

    @Throws(RemoteException::class)
    internal fun transact(proxy: BinderProxy, code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (disconnected || proxy.state.dead) throw DeadObjectException()
        val oneway = (flags and IBinder.FLAG_ONEWAY) != 0
        val requestId = if (oneway) 0L else nextRequestId.getAndIncrement()
        val parent = servicing.get()?.peekLast() ?: 0L
        val fds = ArrayList<FileDescriptor>()
        val entries = flatten(data, fds)
        val bytes = data.payloadBytes()
        if (bytes.size > HostedBinderProtocol.MAX_DATA_LEN) throw IllegalArgumentException("Parcel exceeds 1 MiB")
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_TRANSACT, fds.size, requestId, parent)
        b.ref(HostedBinderProtocol.Ref(HostedBinderProtocol.REF_REMOTE, proxy.globalId))
            .u32(code).u32(flags).u32(entries.size).u32(bytes.size).entries(entries).bytes(bytes)
        val call = if (oneway) null else PendingCall().also { synchronized(lock) { pending[requestId] = it } }
        try {
            channel.writeFrame(b.finish(), fds)
        } catch (e: ErrnoException) {
            synchronized(lock) { pending.remove(requestId) }
            throw DeadObjectException("binder broker connection lost")
        }
        if (call == null) return true
        return awaitReply(call, reply)
    }

    @Throws(RemoteException::class)
    private fun awaitReply(call: PendingCall, reply: Parcel?): Boolean {
        while (true) {
            val nested: IncomingTransact?
            val result: IncomingReply?
            val failure: Throwable?
            synchronized(call.lock) {
                while (call.reply == null && call.failure == null && call.nested.isEmpty()) waitUninterruptibly(call.lock)
                nested = call.nested.pollFirst()
                result = if (nested == null) call.reply else null
                failure = if (nested == null) call.failure else null
            }
            if (nested != null) {
                dispatch(nested)
                continue
            }
            if (failure != null) {
                if (failure is RemoteException) throw failure
                throw RemoteException(failure.toString())
            }
            val r = result!!
            when (r.status) {
                HostedBinderProtocol.STATUS_OK -> {
                    // v4: the reply carries the callee's exact binder_status_t.
                    // Mirror AOSP's android_os_BinderProxy_transact: OK proceeds,
                    // UNKNOWN_TRANSACTION returns false so the proxy falls back to
                    // its default impl, and any other error throws (rather than a
                    // silent false).
                    when (r.binderStatus) {
                        HostedBinderProtocol.BINDER_STATUS_OK -> {}
                        HostedBinderProtocol.BINDER_STATUS_UNKNOWN_TRANSACTION -> {
                            r.fds.forEach(::closeQuietly)
                            return false
                        }
                        else -> {
                            r.fds.forEach(::closeQuietly)
                            throw RemoteException("remote transaction failed with status " + r.binderStatus)
                        }
                    }
                }
                HostedBinderProtocol.STATUS_DEAD_OBJECT -> {
                    r.fds.forEach(::closeQuietly)
                    throw DeadObjectException()
                }
                else -> {
                    r.fds.forEach(::closeQuietly)
                    throw RemoteException("remote transaction failed")
                }
            }
            if (reply != null) inflate(reply, r.payload, r.fds) else r.fds.forEach(::closeQuietly)
            return r.handled
        }
    }

    /**
     * Binder transactions are not interruptible on Android. Keep waiting on an
     * interrupt and re-assert the flag afterwards, so a pending call is never
     * abandoned with its reply (and descriptors) parked in the map.
     */
    private fun waitUninterruptibly(monitor: java.lang.Object) {
        var interrupted = false
        try {
            while (true) {
                try {
                    monitor.wait()
                    return
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Runs an incoming transaction on the current thread and sends the reply. */
    private fun dispatch(t: IncomingTransact) {
        val oneway = (t.flags and IBinder.FLAG_ONEWAY) != 0
        val target: Binder? = if (t.targetKind == HostedBinderProtocol.REF_LOCAL) synchronized(lock) { localById[t.targetId] } else null
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        var status = HostedBinderProtocol.STATUS_OK
        var handled = false
        try {
            inflate(data, t.payload, t.fds)
            if (target == null) {
                status = HostedBinderProtocol.STATUS_FAILED
            } else {
                val stack = servicing.get() ?: ArrayDeque<Long>().also { servicing.set(it) }
                stack.addLast(t.routingId)
                val token = Binder.clearCallingIdentity()
                Binder.setHostedCallingIdentity(t.callerPid, t.callerUid)
                try {
                    handled = target.transact(t.code, data, reply, t.flags)
                } catch (e: Exception) {
                    // AOSP Binder.execTransactInternal: RemoteException and RuntimeException
                    // travel back as a Parcel exception header; anything else fails the call.
                    if (reply != null && (e is RemoteException || e is RuntimeException)) {
                        try {
                            reply.setDataSize(0)
                            reply.setDataPosition(0)
                            reply.writeException(e)
                            handled = true
                        } catch (unmapped: Exception) {
                            Log.w(TAG, "uncaught exception in remote transaction " + t.code + " on " + target, e)
                            status = HostedBinderProtocol.STATUS_FAILED
                        }
                    } else {
                        Log.w(TAG, "uncaught exception in remote transaction " + t.code + " on " + target, e)
                        status = HostedBinderProtocol.STATUS_FAILED
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "error in remote transaction " + t.code + " on " + target, e)
                    status = HostedBinderProtocol.STATUS_FAILED
                } finally {
                    Binder.restoreCallingIdentity(token)
                    stack.removeLast()
                }
            }
            // v4: carry the exact binder_status_t. A stub's onTransact returning
            // false for an unknown code becomes STATUS_UNKNOWN_TRANSACTION so a
            // native caller's default-impl fallback works (a Java caller reads
            // the `handled` boolean, which already conveys this).
            val binderStatus = if (status == HostedBinderProtocol.STATUS_OK && !handled)
                HostedBinderProtocol.BINDER_STATUS_UNKNOWN_TRANSACTION
            else
                HostedBinderProtocol.BINDER_STATUS_OK
            if (!oneway) sendReply(t.routingId, status, handled, binderStatus, if (status == HostedBinderProtocol.STATUS_OK) reply else null)
        } catch (e: Throwable) {
            Log.w(TAG, "failed to dispatch remote transaction", e)
            if (!oneway) try { sendReply(t.routingId, HostedBinderProtocol.STATUS_FAILED, false, HostedBinderProtocol.BINDER_STATUS_OK, null) } catch (ignored: Throwable) {}
        } finally {
            data.recycle()
            reply?.recycle()
        }
    }

    private fun sendReply(routingId: Long, status: Int, handled: Boolean, binderStatus: Int, reply: Parcel?) {
        val fds = ArrayList<FileDescriptor>()
        val entries = flatten(reply, fds)
        val bytes = reply?.payloadBytes() ?: ByteArray(0)
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_REPLY, fds.size, 0, routingId)
        b.u32(status).u32(if (handled) 1 else 0).u32(entries.size).u32(bytes.size).u32(binderStatus).entries(entries).bytes(bytes)
        try {
            channel.writeFrame(b.finish(), fds)
        } catch (e: ErrnoException) {
            Log.w(TAG, "could not send reply: broker connection lost")
        }
    }

    // ---------------------------------------------------------- death links

    internal fun linkDeath(globalId: Long) = sendRefMessage(HostedBinderProtocol.TAG_LINK_DEATH, globalId)
    internal fun unlinkDeath(globalId: Long) = sendRefMessage(HostedBinderProtocol.TAG_UNLINK_DEATH, globalId)

    private fun sendRefMessage(tag: Int, globalId: Long) {
        if (disconnected) return
        val b = HostedBinderProtocol.Builder(tag, 0, 0, 0)
        b.ref(HostedBinderProtocol.Ref(HostedBinderProtocol.REF_REMOTE, globalId))
        try {
            channel.writeFrame(b.finish(), emptyList())
        } catch (e: ErrnoException) {
            // disconnect handling notifies every proxy
        }
    }

    // ------------------------------------------------------------- registry

    /** Returns false when another process owns the name. */
    fun registerService(name: String, binder: IBinder): Boolean {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val requestId = nextRequestId.getAndIncrement()
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_REGISTER_SERVICE, 0, requestId, 0)
        b.ref(refFor(binder)).u32(nameBytes.size).u32(0).bytes(nameBytes)
        val result = serviceRequest(requestId, b.finish())
        return when (result.status) {
            HostedBinderProtocol.SERVICE_OK -> true
            HostedBinderProtocol.SERVICE_ALREADY_REGISTERED -> false
            HostedBinderProtocol.SERVICE_DENIED ->
                throw SecurityException("package " + packageName + " may not register service '" + name + "'")
            else -> throw IllegalStateException("service registration failed: " + result.status)
        }
    }

    fun lookupService(name: String, wait: Boolean): IBinder? {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val requestId = nextRequestId.getAndIncrement()
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_LOOKUP_SERVICE, 0, requestId, 0)
        b.u32(if (wait) 1 else 0).u32(nameBytes.size).bytes(nameBytes)
        val result = serviceRequest(requestId, b.finish())
        return if (result.status == HostedBinderProtocol.SERVICE_OK) binderFor(result.ref) else null
    }

    fun listServices(): Array<String> {
        val requestId = nextRequestId.getAndIncrement()
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_LIST_SERVICES, 0, requestId, 0)
        val result = serviceRequest(requestId, b.finish())
        if (result.status != HostedBinderProtocol.SERVICE_OK) return emptyArray()
        val names = String(result.names, Charsets.UTF_8)
        return names.split(' ').filter { it.isNotEmpty() }.toTypedArray()
    }

    /**
     * Sets a session-wide system property through the broker (the area file
     * itself is read-only in every process). Returns false when the broker's
     * policy denies the write.
     */
    fun setProperty(name: String, value: String): Boolean {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        val requestId = nextRequestId.getAndIncrement()
        val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_SET_PROPERTY, 0, requestId, 0)
        b.u32(nameBytes.size).u32(valueBytes.size).bytes(nameBytes).bytes(valueBytes)
        return serviceRequest(requestId, b.finish()).status == HostedBinderProtocol.SERVICE_OK
    }

    private fun serviceRequest(requestId: Long, frame: ByteArray): ServiceResult {
        if (disconnected) throw IllegalStateException("binder broker connection lost")
        val call = PendingCall()
        synchronized(lock) { pending[requestId] = call }
        try {
            channel.writeFrame(frame, emptyList())
        } catch (e: ErrnoException) {
            synchronized(lock) { pending.remove(requestId) }
            throw IllegalStateException("binder broker connection lost", e)
        }
        while (true) {
            val nested: IncomingTransact?
            val result: ServiceResult?
            val failure: Throwable?
            synchronized(call.lock) {
                while (call.service == null && call.failure == null && call.nested.isEmpty()) waitUninterruptibly(call.lock)
                nested = call.nested.pollFirst()
                result = if (nested == null) call.service else null
                failure = if (nested == null) call.failure else null
            }
            if (nested != null) {
                dispatch(nested)
                continue
            }
            if (failure != null) throw IllegalStateException("binder broker connection lost", failure)
            return result!!
        }
    }

    // -------------------------------------------------------------- reading

    private fun readLoop() {
        try {
            while (true) {
                val frame = channel.readFrame() ?: break
                if (!handle(frame)) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "binder broker connection failed", t)
        }
        onDisconnected()
    }

    private fun handle(frame: HostedBinderProtocol.Frame): Boolean {
        val h = frame.header
        val body = frame.body
        when (h.tag) {
            HostedBinderProtocol.IN_TRANSACT -> {
                val target = HostedBinderProtocol.readRef(body)
                val code = body.getInt()
                val flags = body.getInt()
                val callerPid = body.getInt()
                val callerUid = body.getInt()
                val count = body.getInt()
                val dataLen = body.getInt()
                val payload = HostedBinderProtocol.readPayload(body, count, dataLen)
                val t = IncomingTransact(target.kind, target.value, code, flags, callerPid, callerUid, h.routingId, payload, frame.fds)
                if (h.requestId != 0L) {
                    val call = synchronized(lock) { pending[h.requestId] }
                    if (call != null) {
                        synchronized(call.lock) {
                            call.nested.addLast(t)
                            call.lock.notifyAll()
                        }
                        return true
                    }
                }
                if ((flags and IBinder.FLAG_ONEWAY) != 0) serialQueue(target.value).submit { dispatch(t) }
                else executor.execute { dispatch(t) }
            }
            HostedBinderProtocol.IN_REPLY -> {
                val status = body.getInt()
                val handled = body.getInt() != 0
                val count = body.getInt()
                val dataLen = body.getInt()
                val binderStatus = body.getInt() // v4: exact binder_status_t
                val payload = HostedBinderProtocol.readPayload(body, count, dataLen)
                val call = synchronized(lock) { pending.remove(h.requestId) }
                if (call == null) {
                    frame.fds.forEach(::closeQuietly)
                } else {
                    synchronized(call.lock) {
                        call.reply = IncomingReply(status, handled, binderStatus, payload, frame.fds)
                        call.lock.notifyAll()
                    }
                }
            }
            HostedBinderProtocol.IN_SERVICE_RESULT -> {
                val status = body.getInt()
                body.getInt()
                val ref = HostedBinderProtocol.readRef(body)
                val namesLen = body.getInt()
                body.getInt()
                val names = ByteArray(namesLen)
                body.get(names)
                val call = synchronized(lock) { pending.remove(h.requestId) }
                if (call != null) {
                    synchronized(call.lock) {
                        call.service = ServiceResult(status, ref, names)
                        call.lock.notifyAll()
                    }
                }
            }
            HostedBinderProtocol.IN_OBJECT_DEAD -> {
                val ref = HostedBinderProtocol.readRef(body)
                val record = synchronized(lock) { proxies[ref.value] }
                if (record != null) {
                    record.state.dead = true
                    record.ref.get()?.let { proxy -> executor.execute { proxy.notifyDeath() } }
                }
            }
            HostedBinderProtocol.IN_OBJECT_RELEASED -> {
                val ref = HostedBinderProtocol.readRef(body)
                val uses = body.getLong()
                // Drop the export only if the broker has seen every reference we
                // sent; otherwise an in-flight re-export revives the object and
                // the broker will notify again later.
                val drop = synchronized(lock) {
                    if ((exportUses[ref.value] ?: 0L) != uses) return@synchronized false
                    localById.remove(ref.value)?.let { localIds.remove(it) }
                    exportUses.remove(ref.value)
                    onewayQueues.remove(ref.value)
                    true
                }
                if (drop) {
                    val b = HostedBinderProtocol.Builder(HostedBinderProtocol.TAG_RELEASE_ACK, 0, 0, 0)
                    b.ref(HostedBinderProtocol.Ref(HostedBinderProtocol.REF_LOCAL, ref.value))
                    try {
                        channel.writeFrame(b.finish(), emptyList())
                    } catch (e: ErrnoException) {
                        // connection loss is handled by the reader loop
                    }
                }
            }
            HostedBinderProtocol.IN_PROTOCOL_ERROR -> {
                val reason = ByteArray(body.remaining()).also { body.get(it) }
                Log.e(TAG, "binder broker protocol error: " + String(reason, Charsets.UTF_8))
                return false
            }
            HostedBinderProtocol.IN_PROPERTY_CHANGED -> {
                // A property changed in the session area; the mapping is
                // already current. Drives SystemProperties change callbacks
                // (the SYSPROPS_TRANSACTION analogue).
                executor.execute { SystemProperties.reportSyspropChanged() }
            }
            HostedBinderProtocol.IN_SHUTDOWN -> return false
            else -> {
                frame.fds.forEach(::closeQuietly)
                Log.w(TAG, "ignoring unknown broker message " + h.tag)
            }
        }
        return true
    }

    private fun onDisconnected() {
        disconnected = true
        val calls: List<PendingCall>
        val records: List<ProxyRecord>
        synchronized(lock) {
            calls = ArrayList(pending.values)
            pending.clear()
            records = ArrayList(proxies.values)
        }
        for (call in calls) {
            synchronized(call.lock) {
                call.failure = DeadObjectException("binder broker connection lost")
                call.lock.notifyAll()
            }
        }
        for (record in records) {
            record.state.dead = true
            record.ref.get()?.let { proxy -> executor.execute { proxy.notifyDeath() } }
        }
        channel.close()
        if (exitProcessOnDisconnect) {
            Log.i(TAG, "broker connection lost; exiting headless host")
            Runtime.getRuntime().halt(0)
        }
    }

    // ------------------------------------------------------- oneway ordering

    private fun serialQueue(localId: Long): SerialQueue = synchronized(lock) {
        onewayQueues.getOrPut(localId) { SerialQueue() }
    }

    /** Runs submitted tasks one at a time, in order, on the pool. */
    private inner class SerialQueue {
        private val tasks = ArrayDeque<Runnable>()
        private var running = false

        fun submit(task: Runnable) {
            synchronized(this) {
                tasks.addLast(task)
                if (running) return
                running = true
            }
            executor.execute { drain() }
        }

        private fun drain() {
            while (true) {
                val next = synchronized(this) {
                    tasks.pollFirst() ?: run { running = false; null }
                } ?: return
                try {
                    next.run()
                } catch (t: Throwable) {
                    Log.w(TAG, "oneway dispatch failed", t)
                }
            }
        }
    }

    // ---------------------------------------------------------------- socket

    /** Frames over a Unix stream socket with SCM_RIGHTS, via libcore Os. */
    private class Channel(private val fd: FileDescriptor) {
        private val readBuffer = ByteBuffer.allocateDirect(64 * 1024)
        private var pendingBytes = ByteArray(64 * 1024)
        private var pendingLen = 0
        private val fdQueue = ArrayDeque<FileDescriptor>()
        private val writeLock = Any()

        /** Returns null at end of stream. */
        fun readFrame(): HostedBinderProtocol.Frame? {
            while (pendingLen < HostedBinderProtocol.HEADER_LEN) if (!fill()) return null
            val header = HostedBinderProtocol.parseHeader(
                ByteBuffer.wrap(pendingBytes, 0, HostedBinderProtocol.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN),
            )
            val total = HostedBinderProtocol.HEADER_LEN + header.bytesLen
            while (pendingLen < total) if (!fill()) throw HostedBinderProtocol.ProtocolException("truncated frame")
            val body = ByteBuffer.allocate(header.bytesLen).order(ByteOrder.LITTLE_ENDIAN)
            body.put(pendingBytes, HostedBinderProtocol.HEADER_LEN, header.bytesLen)
            body.flip()
            System.arraycopy(pendingBytes, total, pendingBytes, 0, pendingLen - total)
            pendingLen -= total
            if (fdQueue.size < header.fdCount) {
                throw HostedBinderProtocol.ProtocolException("frame announced " + header.fdCount + " descriptors, received " + fdQueue.size)
            }
            val fds = ArrayList<FileDescriptor>(header.fdCount)
            repeat(header.fdCount) { fds.add(fdQueue.pollFirst()!!) }
            return HostedBinderProtocol.Frame(header, body, fds)
        }

        private fun fill(): Boolean {
            readBuffer.clear()
            val msg = StructMsghdr(null, arrayOf(readBuffer), null, 0)
            val n = try {
                Os.recvmsg(fd, msg, MSG_CMSG_CLOEXEC)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ECONNRESET) return false
                throw e
            }
            if (n <= 0) return false
            if ((msg.msg_flags and MSG_CTRUNC) != 0) throw HostedBinderProtocol.ProtocolException("ancillary data truncated")
            msg.msg_control?.forEach { cmsg -> collectFds(cmsg) }
            if (pendingLen + n > pendingBytes.size) {
                pendingBytes = pendingBytes.copyOf(maxOf(pendingBytes.size * 2, pendingLen + n))
            }
            readBuffer.limit(n)
            readBuffer.position(0)
            readBuffer.get(pendingBytes, pendingLen, n)
            pendingLen += n
            return true
        }

        private fun collectFds(cmsg: StructCmsghdr) {
            if (cmsg.cmsg_level != OsConstants.SOL_SOCKET || cmsg.cmsg_type != SCM_RIGHTS) return
            val ints = ByteBuffer.wrap(cmsg.cmsg_data).order(ByteOrder.nativeOrder())
            while (ints.remaining() >= 4) {
                val fd = FileDescriptor()
                fd.`setInt$`(ints.getInt())
                fdQueue.addLast(fd)
            }
        }

        @Throws(ErrnoException::class)
        fun writeFrame(bytes: ByteArray, fds: List<FileDescriptor>) {
            var buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.flip()
            var control: Array<StructCmsghdr>? = null
            if (fds.isNotEmpty()) {
                val data = ByteBuffer.allocate(fds.size * 4).order(ByteOrder.nativeOrder())
                for (fd in fds) data.putInt(fd.`getInt$`())
                control = arrayOf(StructCmsghdr(OsConstants.SOL_SOCKET, SCM_RIGHTS, data.array()))
            }
            synchronized(writeLock) {
                while (buffer.hasRemaining()) {
                    val msg = StructMsghdr(null, arrayOf(buffer), control, 0)
                    val sent = try {
                        Os.sendmsg(fd, msg, MSG_NOSIGNAL)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) continue
                        throw e
                    }
                    control = null
                    if (sent <= 0) throw ErrnoException("sendmsg", OsConstants.EPIPE)
                    buffer.position(sent)
                    buffer = buffer.slice()
                }
            }
        }

        fun close() {
            try { Os.close(fd) } catch (ignored: ErrnoException) {}
        }

        private companion object {
            const val SCM_RIGHTS = 1
            const val MSG_NOSIGNAL = 0x4000
            const val MSG_CTRUNC = 0x8
            const val MSG_CMSG_CLOEXEC = 0x40000000
        }
    }
}
