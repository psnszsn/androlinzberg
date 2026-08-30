package android.webkit

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host seam behind WebView's AOSP-facing API. Android normally obtains this
 * behavior from the updatable Chromium WebView provider. That provider cannot
 * run in this process, so the desktop host drives a sandboxed headless Chromium
 * page through the DevTools protocol instead.
 */
interface HostedWebViewProvider {
    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String> = emptyMap())
    fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?)
    fun stopLoading()
    fun destroy()
}

/** Test seam kept at the provider boundary rather than in WebView callers. */
object HostedWebViewProviders {
    @Volatile
    private var testFactory: ((WebView) -> HostedWebViewProvider)? = null

    @JvmStatic
    fun create(owner: WebView): HostedWebViewProvider =
        testFactory?.invoke(owner) ?: ChromiumHostedWebViewProvider(owner)

    internal fun installForTests(factory: ((WebView) -> HostedWebViewProvider)?) {
        testFactory = factory
    }
}

internal class InMemoryHostedWebViewProvider(
    private val owner: WebView,
) : HostedWebViewProvider {
    private var stopped = false

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        if (stopped) return
        owner.hostedOnPageStarted(url)
        owner.hostedOnProgressChanged(100)
        owner.hostedOnPageCommitVisible(url)
        owner.hostedOnPageFinished(url)
    }

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        if (!stopped) resultCallback?.onReceiveValue("null")
    }

    override fun stopLoading() = Unit

    override fun destroy() {
        stopped = true
    }
}

private class ChromiumHostedWebViewProvider(
    private val owner: WebView,
) : HostedWebViewProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HostedWebView").apply { isDaemon = true }
    }
    private var chromium: ChromiumPage? = null
    @Volatile private var destroyed = false

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        if (destroyed) return
        mainHandler.post { if (!destroyed) owner.hostedOnPageStarted(url) }
        worker.execute {
            try {
                page().navigate(url, additionalHttpHeaders)
            } catch (failure: Throwable) {
                reportLoadFailure(url, failure)
            }
        }
    }

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        if (destroyed) return
        worker.execute {
            val value = try {
                page().evaluate(script)
            } catch (failure: Throwable) {
                Log.e(TAG, "evaluateJavascript failed", failure)
                "null"
            }
            mainHandler.post {
                if (!destroyed) resultCallback?.onReceiveValue(value)
            }
        }
    }

    override fun stopLoading() {
        if (destroyed) return
        worker.execute {
            try {
                chromium?.stopLoading()
            } catch (_: Throwable) {
            }
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        worker.execute {
            try {
                chromium?.close()
            } finally {
                chromium = null
            }
        }
        worker.shutdown()
    }

    private fun page(): ChromiumPage {
        chromium?.let { return it }
        return ChromiumPage(owner, mainHandler).also { chromium = it }
    }

    private fun reportLoadFailure(url: String, failure: Throwable) {
        Log.e(TAG, "loadUrl failed for $url", failure)
        mainHandler.post {
            if (destroyed) return@post
            owner.hostedOnReceivedError(
                WebViewClient.ERROR_UNKNOWN,
                failure.message ?: failure.javaClass.name,
                url,
            )
        }
    }

    companion object {
        private const val TAG = "HostedWebView"
    }
}

private class ChromiumPage(
    private val owner: WebView,
    private val mainHandler: Handler,
) : AutoCloseable {
    private val profileDirectory = Files.createTempDirectory("artzig-webview-")
    private val process: Process
    private val eventWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HostedWebView-events").apply { isDaemon = true }
    }
    private val connection: CdpConnection
    @Volatile private var mainFrameId: String? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var closed = false

    init {
        val executable = resolveChromiumExecutable()
            ?: throw IOException(
                "No Chromium executable found; set ARTZIG_CHROMIUM or install chromium",
            )
        process = ProcessBuilder(
            executable,
            "--headless=new",
            "--disable-crash-reporter",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--no-default-browser-check",
            "--no-first-run",
            "--remote-allow-origins=*",
            "--remote-debugging-port=0",
            "--user-data-dir=$profileDirectory",
            "about:blank",
        ).redirectErrorStream(true).start()
        drainProcessOutput(process)

        val port = awaitDevToolsPort(process, profileDirectory.resolve("DevToolsActivePort").toFile())
        val webSocketUrl = awaitPageWebSocketUrl(port)
        connection = CdpConnection(webSocketUrl, ::dispatchEvent)
        connection.send("Page.enable")
        connection.send("Runtime.enable")
        connection.send("Network.enable")
        connection.send(
            "Fetch.enable",
            JSONObject().put(
                "patterns",
                JSONArray().put(
                    JSONObject()
                        .put("urlPattern", "*")
                        .put("resourceType", "Document"),
                ),
            ),
        )
        val frameTree = connection.send("Page.getFrameTree")
        mainFrameId = frameTree
            .optJSONObject("frameTree")
            ?.optJSONObject("frame")
            ?.optString("id")
    }

    fun navigate(url: String, additionalHttpHeaders: Map<String, String>) {
        check(!closed) { "WebView provider is closed" }
        currentUrl = url
        val headers = JSONObject()
        additionalHttpHeaders.forEach { (name, value) -> headers.put(name, value) }
        if (additionalHttpHeaders.isNotEmpty()) {
            connection.send(
                "Network.setExtraHTTPHeaders",
                JSONObject().put("headers", headers),
            )
        }
        val userAgent = owner.settings.getUserAgentString()
        if (userAgent.isNotBlank()) {
            connection.send(
                "Network.setUserAgentOverride",
                JSONObject().put("userAgent", userAgent),
            )
        }
        mainHandler.post { owner.hostedOnProgressChanged(10) }
        val result = connection.send("Page.navigate", JSONObject().put("url", url))
        val errorText = result.optString("errorText")
        if (errorText.isNotEmpty()) throw IOException(errorText)
        result.optString("frameId").takeIf { it.isNotEmpty() }?.let { mainFrameId = it }
    }

    fun evaluate(script: String): String {
        check(!closed) { "WebView provider is closed" }
        val result = connection.send(
            "Runtime.evaluate",
            JSONObject()
                .put("expression", script)
                .put("returnByValue", true)
                .put("userGesture", true),
        )
        result.optJSONObject("exceptionDetails")?.let { details ->
            val exception = details.optJSONObject("exception")
            val message = exception?.optString("description")
                ?.takeIf { it.isNotEmpty() }
                ?: details.optString("text", "JavaScript evaluation failed")
            throw IOException(message)
        }
        val remote = result.optJSONObject("result") ?: return "null"
        if (!remote.has("value") || remote.isNull("value")) return "null"
        val value = remote.get("value")
        return when (value) {
            is String -> JSONObject.quote(value)
            JSONObject.NULL -> "null"
            else -> value.toString()
        }
    }

    fun stopLoading() {
        if (!closed) connection.send("Page.stopLoading")
    }

    private fun dispatchEvent(method: String, params: JSONObject) {
        if (closed) return
        eventWorker.execute {
            try {
                when (method) {
                    "Fetch.requestPaused" -> handleRequestPaused(params)
                    "Runtime.consoleAPICalled" -> handleConsoleMessage(params)
                    "Page.frameNavigated" -> handleFrameNavigated(params)
                    "Page.domContentEventFired" -> handleDocumentCommitted()
                    "Page.loadEventFired" -> handleLoadFinished()
                }
            } catch (failure: Throwable) {
                Log.e("HostedWebView", "CDP event $method failed", failure)
            }
        }
    }

    private fun handleRequestPaused(params: JSONObject) {
        val requestId = params.getString("requestId")
        val request = params.getJSONObject("request")
        val url = request.getString("url")
        val frameId = params.optString("frameId")
        val resourceType = params.optString("resourceType")
        val isMainFrame = resourceType == "Document" &&
            (mainFrameId == null || frameId == mainFrameId)
        val headersObject = request.optJSONObject("headers")
        val headers = LinkedHashMap<String, String>()
        if (headersObject != null) {
            val names = headersObject.keys()
            while (names.hasNext()) {
                val name = names.next()
                headers[name] = headersObject.optString(name)
            }
        }
        val hostedRequest = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = params.has("redirectedRequestId")
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = request.optString("method", "GET")
            override fun getRequestHeaders(): Map<String, String> = headers
        }

        val response = try {
            owner.webViewClient.shouldInterceptRequest(owner, hostedRequest)
        } catch (failure: Throwable) {
            Log.e("HostedWebView", "shouldInterceptRequest failed for $url", failure)
            null
        }
        if (response == null) {
            if (resourceType == "Document") {
                Log.i("HostedWebView", "Continuing document request without interception: $url")
            }
            connection.send(
                "Fetch.continueRequest",
                JSONObject().put("requestId", requestId),
            )
            return
        }

        val responseHeaders = JSONArray()
        response.getResponseHeaders()?.forEach { (name, value) ->
            responseHeaders.put(JSONObject().put("name", name).put("value", value))
        }
        if (response.getMimeType() != null &&
            response.getResponseHeaders()?.keys?.none { it.equals("Content-Type", true) } != false
        ) {
            val contentType = buildString {
                append(response.getMimeType())
                response.getEncoding()?.let { append("; charset=").append(it) }
            }
            responseHeaders.put(JSONObject().put("name", "Content-Type").put("value", contentType))
        }
        val bytes = response.getData()?.use { input -> input.readBytes() } ?: ByteArray(0)
        val status = response.getStatusCode().takeIf { it in 100..599 } ?: 200
        Log.i(
            "HostedWebView",
            "Fulfilling document request: $url status=$status bytes=${bytes.size}",
        )
        val command = JSONObject()
            .put("requestId", requestId)
            .put("responseCode", status)
            .put("responseHeaders", responseHeaders)
            .put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
        response.getReasonPhrase()?.let { command.put("responsePhrase", it) }
        connection.send("Fetch.fulfillRequest", command)
    }

    private fun handleConsoleMessage(params: JSONObject) {
        val args = params.optJSONArray("args") ?: JSONArray()
        val pieces = ArrayList<String>(args.length())
        for (index in 0 until args.length()) {
            val value = args.optJSONObject(index) ?: continue
            pieces += when {
                value.has("value") && !value.isNull("value") -> value.get("value").toString()
                value.has("description") -> value.optString("description")
                else -> value.optString("type")
            }
        }
        val level = when (params.optString("type")) {
            "error", "assert" -> ConsoleMessage.MessageLevel.ERROR
            "warning" -> ConsoleMessage.MessageLevel.WARNING
            "debug" -> ConsoleMessage.MessageLevel.DEBUG
            "log", "info" -> ConsoleMessage.MessageLevel.LOG
            else -> ConsoleMessage.MessageLevel.TIP
        }
        val message = ConsoleMessage(pieces.joinToString(" "), currentUrl, 0, level)
        mainHandler.post { if (!closed) owner.webChromeClient?.onConsoleMessage(message) }
    }

    private fun handleFrameNavigated(params: JSONObject) {
        val frame = params.optJSONObject("frame") ?: return
        val id = frame.optString("id")
        val parentId = frame.optString("parentId")
        if (parentId.isNotEmpty() && id != mainFrameId) return
        if (parentId.isEmpty()) mainFrameId = id
        val url = frame.optString("url").takeIf { it.isNotEmpty() } ?: return
        currentUrl = url
    }

    private fun handleDocumentCommitted() {
        val url = currentUrl ?: return
        mainHandler.post { if (!closed) owner.hostedOnPageCommitVisible(url) }
    }

    private fun handleLoadFinished() {
        val url = currentUrl ?: return
        mainHandler.post {
            if (closed) return@post
            owner.hostedOnProgressChanged(100)
            owner.hostedOnPageFinished(url)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: Throwable) {
        }
        eventWorker.shutdownNow()
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        try {
            Files.walk(profileDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private fun resolveChromiumExecutable(): String? {
            val configured = System.getenv("ARTZIG_CHROMIUM")
            if (!configured.isNullOrBlank() && File(configured).canExecute()) return configured
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparatorChar)) {
                for (name in arrayOf("chromium", "chromium-browser", "google-chrome")) {
                    val candidate = File(directory, name)
                    if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
                }
            }
            return null
        }

        private fun drainProcessOutput(process: Process) {
            Thread({
                try {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (input.read(buffer) >= 0) Unit
                    }
                } catch (_: Throwable) {
                }
            }, "HostedWebView-chromium-log").apply {
                isDaemon = true
                start()
            }
        }

        private fun awaitDevToolsPort(process: Process, portFile: File): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                if (portFile.isFile) {
                    val firstLine = portFile.useLines { it.firstOrNull() }
                    firstLine?.toIntOrNull()?.let { return it }
                }
                if (!process.isAlive) throw IOException("Chromium exited during startup")
                Thread.sleep(25)
            }
            throw IOException("Timed out waiting for Chromium DevTools port")
        }

        private fun awaitPageWebSocketUrl(port: Int): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var lastFailure: Throwable? = null
            while (System.nanoTime() < deadline) {
                try {
                    val connection = URL("http://127.0.0.1:$port/json/list").openConnection()
                    connection.connectTimeout = 1_000
                    connection.readTimeout = 1_000
                    val response = connection.getInputStream().bufferedReader().use { it.readText() }
                    val targets = JSONArray(response)
                    for (index in 0 until targets.length()) {
                        val target = targets.getJSONObject(index)
                        if (target.optString("type") == "page") {
                            return target.getString("webSocketDebuggerUrl")
                        }
                    }
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
                Thread.sleep(25)
            }
            throw IOException("Timed out finding Chromium page target", lastFailure)
        }
    }
}

private class CdpConnection(
    webSocketUrl: String,
    private val eventHandler: (String, JSONObject) -> Unit,
) : AutoCloseable {
    private class PendingResponse {
        val latch = CountDownLatch(1)
        @Volatile var response: JSONObject? = null
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, PendingResponse>()
    private val socket = WebSocketClient(webSocketUrl)
    @Volatile private var closed = false
    private val reader = Thread(::readMessages, "HostedWebView-cdp").apply {
        isDaemon = true
        start()
    }

    fun send(method: String, params: JSONObject = JSONObject()): JSONObject {
        check(!closed) { "Chromium connection is closed" }
        val id = nextId.getAndIncrement()
        val waiter = PendingResponse()
        pending[id] = waiter
        try {
            socket.sendText(JSONObject().put("id", id).put("method", method).put("params", params).toString())
            if (!waiter.latch.await(30, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting for CDP $method")
            }
            val response = waiter.response ?: throw IOException("CDP $method connection closed")
            response.optJSONObject("error")?.let { error ->
                throw IOException("CDP $method failed: ${error.optString("message")}")
            }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    private fun readMessages() {
        try {
            while (!closed) {
                val message = socket.readText() ?: break
                val json = JSONObject(message)
                if (json.has("id")) {
                    pending[json.getInt("id")]?.let { waiter ->
                        waiter.response = json
                        waiter.latch.countDown()
                    }
                } else {
                    val method = json.optString("method")
                    if (method.isNotEmpty()) {
                        eventHandler(method, json.optJSONObject("params") ?: JSONObject())
                    }
                }
            }
        } catch (failure: Throwable) {
            if (!closed) Log.e("HostedWebView", "Chromium protocol reader failed", failure)
        } finally {
            closed = true
            pending.values.forEach { it.latch.countDown() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        socket.close()
        pending.values.forEach { it.latch.countDown() }
        reader.interrupt()
    }
}

private class WebSocketClient(webSocketUrl: String) : AutoCloseable {
    private val random = SecureRandom()
    private val socket: Socket
    private val input: BufferedInputStream
    private val output: BufferedOutputStream
    private val writeLock = Any()
    @Volatile private var closed = false

    init {
        val uri = URI(webSocketUrl)
        require(uri.scheme == "ws") { "Unsupported DevTools WebSocket scheme: ${uri.scheme}" }
        val port = if (uri.port >= 0) uri.port else 80
        socket = Socket(InetAddress.getByName(uri.host), port).apply { tcpNoDelay = true }
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(uri.host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: http://127.0.0.1\r\n\r\n")
        }
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val response = readHttpHeader()
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw IOException("DevTools WebSocket handshake failed: ${response.lineSequence().firstOrNull()}")
        }
        val accept = response.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
            ?.substringAfter(':')
            ?.trim()
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .toByteArray(StandardCharsets.US_ASCII),
            ),
            Base64.NO_WRAP,
        )
        if (accept != expectedAccept) throw IOException("Invalid DevTools WebSocket accept key")
    }

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))

    fun readText(): String? {
        val message = ByteArrayOutputStream()
        var collecting = false
        while (!closed) {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) throw EOFException("Truncated WebSocket frame")
            val final = first and 0x80 != 0
            val opcode = first and 0x0f
            val masked = second and 0x80 != 0
            var length = (second and 0x7f).toLong()
            if (length == 126L) {
                length = ((readByte().toLong() shl 8) or readByte().toLong())
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length > MAX_FRAME_BYTES) throw IOException("Oversized WebSocket frame: $length")
            val mask = if (masked) readExactly(4) else null
            val payload = readExactly(length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x0 -> {
                    if (!collecting) throw IOException("Unexpected WebSocket continuation")
                    message.write(payload)
                    if (final) return message.toString(StandardCharsets.UTF_8.name())
                }
                0x1 -> {
                    if (final) return String(payload, StandardCharsets.UTF_8)
                    collecting = true
                    message.reset()
                    message.write(payload)
                }
                0x8 -> return null
                0x9 -> sendFrame(0xA, payload)
                0xA -> Unit
            }
        }
        return null
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            check(!closed) { "WebSocket is closed" }
            output.write(0x80 or opcode)
            when {
                payload.size <= 125 -> output.write(0x80 or payload.size)
                payload.size <= 0xffff -> {
                    output.write(0x80 or 126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write(0x80 or 127)
                    repeat(4) { output.write(0) }
                    output.write(payload.size ushr 24)
                    output.write(payload.size ushr 16)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
            }
            val mask = ByteArray(4).also(random::nextBytes)
            output.write(mask)
            val masked = payload.copyOf()
            for (index in masked.indices) {
                masked[index] = (masked[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
            output.write(masked)
            output.flush()
        }
    }

    private fun readHttpHeader(): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) throw EOFException("Truncated WebSocket handshake")
            bytes.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return bytes.toString(StandardCharsets.US_ASCII.name())
        }
        throw IOException("Oversized WebSocket handshake")
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Truncated WebSocket frame")
        return value
    }

    private fun readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("Truncated WebSocket frame")
            offset += count
        }
        return bytes
    }

    override fun close() {
        if (closed) return
        try {
            sendFrame(0x8, ByteArray(0))
        } catch (_: Throwable) {
        }
        closed = true
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 64L * 1024L * 1024L
    }
}
