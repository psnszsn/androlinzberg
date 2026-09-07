package dev.artzig

import android.media.IMediaHTTPConnection
import android.media.MediaHTTPConnection
import android.media.MediaHTTPService
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.system.Os
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/** Original HTTP client and native IMemory payloads over a deterministic local server. */
internal object StockMediaHttpSmoke {
    private val payload = ByteArray(80 * 1024) { (it % 251).toByte() }

    private class Server : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var stopped = false
        private val requests = ArrayList<Pair<String, Map<String, String>>>()
        val base = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread({
            try {
                while (!stopped) {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine() ?: return@use
                        val path = request.split(' ')[1]
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                        }
                        synchronized(requests) { requests.add(path to headers) }
                        val output = client.getOutputStream()
                        val offset = headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                        val response = when (path) {
                            "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: /bytes\r\nContent-Length: 0\r\n"
                            "/cross" -> "HTTP/1.1 302 Found\r\nLocation: http://localhost:${socket.localPort}/bytes\r\nContent-Length: 0\r\n"
                            "/missing" -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                            else -> {
                                val start = if (path == "/no-range") 0 else offset
                                val status = if (start > 0) "206 Partial Content" else "200 OK"
                                "HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nContent-Length: ${payload.size - start}\r\n" +
                                    if (start > 0) "Content-Range: bytes $start-${payload.lastIndex}/${payload.size}\r\n" else ""
                            }
                        }
                        try {
                            output.write((response + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            if (path !in listOf("/redirect", "/cross", "/missing")) {
                                val start = if (path == "/no-range") 0 else offset
                                output.write(payload, start, payload.size - start)
                            }
                            output.flush()
                        } catch (_: SocketException) {
                            // Seeking/disconnecting may close an unread response body.
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!stopped) failure.set(error)
            }
        }, "media-http-fixture").apply { isDaemon = true; start() }

        fun seen(): List<Pair<String, Map<String, String>>> = synchronized(requests) { requests.toList() }
        override fun close() {
            stopped = true
            socket.close()
            thread.join(5000)
            check(!thread.isAlive)
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }

    private class SharedMemory(memory: IBinder) : AutoCloseable {
        private val fd: ParcelFileDescriptor
        private val offset: Long
        val size: Long
        init {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                // Original BnMemory::GET_MEMORY has no Java exception header.
                request.writeInterfaceToken("android.utils.IMemory")
                check(memory.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
                val heap = checkNotNull(reply.readStrongBinder())
                val allocationOffset = reply.readLong()
                size = reply.readLong()
                check(reply.dataAvail() == 0)
                request.setDataSize(0); request.setDataPosition(0)
                reply.setDataSize(0); reply.setDataPosition(0)
                request.writeInterfaceToken("android.utils.IMemoryHeap")
                check(heap.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
                fd = checkNotNull(reply.readFileDescriptor())
                val heapSize = reply.readLong()
                offset = allocationOffset + reply.readLong()
                reply.readInt() // Heap flags.
                check(reply.dataAvail() == 0 && offset >= 0 && offset + size <= heapSize)
            } finally { request.recycle(); reply.recycle() }
        }
        fun read(size: Int): ByteArray = ByteArray(size).also {
            check(Os.pread(fd.fileDescriptor, it, 0, size, offset) == size)
        }
        override fun close() = fd.close()
    }

    private fun release(connection: IMediaHTTPConnection) {
        connection.disconnect()
        MediaHTTPConnection::class.java.getDeclaredMethod("native_finalize")
            .apply { isAccessible = true }.invoke(connection)
    }

    fun run() {
        val previousCookies = CookieHandler.getDefault()
        val previousPolicy = StrictMode.getThreadPolicy()
        val cookies = CookieManager()
        CookieHandler.setDefault(cookies)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        try {
            Server().use { server ->
                val cookie = HttpCookie("bootstrap", "present").apply { domain = "127.0.0.1"; path = "/" }
                val service = MediaHTTPService(listOf(cookie))
                val connection = service.makeHTTPConnection()
                var released = false
                try {
                    check(cookies.cookieStore.cookies.any { it.name == "bootstrap" })
                    val memory = checkNotNull(connection.connect(server.base + "/bytes", "X-Fixture: present\r\n"))
                    SharedMemory(memory).use { shared ->
                        check(shared.size == 32768L)
                        check(connection.getSize() == payload.size.toLong())
                        check(connection.getMIMEType() == "video/mp4")
                        check(connection.getUri() == server.base + "/bytes")
                        val first = connection.readAt(0, Int.MAX_VALUE)
                        check(first in 1..32768)
                        check(shared.read(first).contentEquals(payload.copyOfRange(0, first)))
                        val seek = 40_000
                        val count = connection.readAt(seek.toLong(), 113)
                        check(count in 1..113)
                        check(shared.read(count).contentEquals(payload.copyOfRange(seek, seek + count)))
                        var position = seek + count
                        while (position < payload.size) {
                            val n = connection.readAt(position.toLong(), 32768)
                            check(n in 1..32768)
                            check(shared.read(n).contentEquals(payload.copyOfRange(position, position + n)))
                            position += n
                        }
                        check(connection.readAt(position.toLong(), 1) == 0)
                        val beforeRelease = shared.read(20)
                        release(connection); released = true
                        check(shared.read(20).contentEquals(beforeRelease))
                    }
                    val requests = server.seen()
                    check(requests.any { it.second["x-fixture"] == "present" })
                    check(requests.any { it.second["range"] == "bytes=40000-" })
                } finally { if (!released) release(connection) }

                for ((path, headers, expected) in listOf(
                    Triple("/redirect", "", 1),
                    Triple("/cross", "android-allow-cross-domain-redirect: 0\r\n", -1010),
                    Triple("/missing", "", -1),
                    Triple("/no-range", "", -1010)
                )) {
                    val conn = service.makeHTTPConnection()
                    try {
                        checkNotNull(conn.connect(server.base + path, headers))
                        check(conn.readAt(if (path == "/no-range") 5 else 0, 1) == expected) { path }
                        if (path == "/redirect") check(conn.getUri() == server.base + "/bytes")
                    } finally { release(conn) }
                }
                check(server.seen().none { "android-allow-cross-domain-redirect" in it.second })
                val invalid = service.makeHTTPConnection()
                try { check(invalid.connect("not a url", "") == null) } finally { release(invalid) }
                val create = MediaHTTPService::class.java.getDeclaredMethod(
                    "createHttpServiceBinderIfNecessary", String::class.java).apply { isAccessible = true }
                check(create.invoke(null, "file:///tmp/media") == null)
                check(create.invoke(null, "widevine://legacy") == null)
                check(create.invoke(null, server.base) is IBinder)
            }
        } finally {
            CookieHandler.setDefault(previousCookies)
            StrictMode.setThreadPolicy(previousPolicy)
        }
        println("stock media HTTP requests, seeking and Binder shared-memory lifetimes: PASS")
    }
}
