package dev.artzig.testkit

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector as NioSelector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.max

class VarlinkException(val response: JsonObject) : RuntimeException(response.toString())

class TestControlClient(
    address: String,
    private val timeoutMillis: Long = 10_000,
    private val rawLogPath: Path? = null,
    private val stepLogPath: Path? = null,
) {
    private val socketPath = Path.of(address.removePrefix("unix:"))
    private val gson = Gson()

    fun call(method: String, parameters: JsonObject): JsonObject {
        val request = JsonObject().apply {
            addProperty("method", "$INTERFACE.$method")
            add("parameters", parameters)
        }
        val response = exchange((gson.toJson(request) + '\u0000').toByteArray(StandardCharsets.UTF_8))
        val parsed = JsonParser.parseString(response).asJsonObject
        if (parsed.has("error")) throw VarlinkException(parsed)
        return parsed.objectOrEmpty("parameters")
    }

    fun observe(
        selector: dev.artzig.testkit.Selector? = null,
        wait: WaitKind = WaitKind.NOW,
        includeTree: Boolean = false,
        stableFrames: Int = 2,
        after: Mark? = null,
    ): JsonObject = call("Observe", JsonObject().apply {
        add("selector", selector?.toJson() ?: JsonNull.INSTANCE)
        add("wait", waitJson(wait, stableFrames, after))
        addProperty("include_tree", includeTree)
    })

    fun perform(
        selector: dev.artzig.testkit.Selector,
        action: Action,
        settle: WaitKind = WaitKind.IDLE,
        stableFrames: Int = 2,
        after: Mark? = null,
    ): JsonObject {
        // Relative waits require a starting cursor; without it the server can
        // never satisfy the frame/tree condition, even while frames arrive.
        val origin = if (settle == WaitKind.FRAME_PRESENTED || settle == WaitKind.TREE_CHANGED)
            after ?: mark() else null
        return call("Perform", JsonObject().apply {
            add("selector", selector.toJson())
            add("action", action.toJson())
            add("settle", waitJson(settle, stableFrames, origin))
        })
    }

    fun diagnose(since: Mark? = null): JsonObject = call("Diagnose", JsonObject().apply {
        add("since", since?.toJson() ?: JsonNull.INSTANCE)
    })

    fun mark(): Mark = extractMark(observe())

    fun step(
        selector: dev.artzig.testkit.Selector,
        action: Action,
        settle: WaitKind = WaitKind.IDLE,
        stableFrames: Int = 2,
    ): StepResult = step(selector, action, settle, stableFrames, allowMissing = false)

    internal fun step(
        selector: dev.artzig.testkit.Selector,
        action: Action,
        settle: WaitKind,
        stableFrames: Int,
        allowMissing: Boolean,
    ): StepResult {
        val actionJson = JsonObject().apply {
            add("selector", selector.toJson())
            for ((name, value) in action.toJson().entrySet()) add(name, value)
        }
        return recordedStep(actionJson, allowMissing) { before -> perform(selector, action, settle, stableFrames, before) }
    }

    /** Native desktop tools participate in the same diagnostic cursor and
     * retained step journal as Android UI actions. argv never passes a shell. */
    internal fun stepExec(argv: List<String>, selector: dev.artzig.testkit.Selector,
        wait: WaitKind, stableFrames: Int, allowMissing: Boolean): StepResult {
        require(argv.isNotEmpty()) { "exec requires a command" }
        val actionJson = JsonObject().apply {
            addProperty("kind", "host_exec")
            add("argv", gson.toJsonTree(argv))
            add("selector", selector.toJson())
        }
        return recordedStep(actionJson, allowMissing) { before ->
            val process = ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            try {
                check(process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    "Native command timed out: ${argv.first()}"
                }
                check(process.exitValue() == 0) { "Native command exited ${process.exitValue()}: ${argv.first()}" }
            } finally {
                if (process.isAlive) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                process.inputStream.close()
                process.errorStream.close()
            }
            try {
                observe(selector.takeUnless { it.isEmpty }, wait, includeTree = true, stableFrames, before)
                    .apply { add("result", JsonObject().apply { addProperty("exit_code", 0) }) }
            } catch (failure: VarlinkException) {
                // Observe's timeout says it performed no Android action. The
                // native command already succeeded; preserve that distinction
                // so a settle failure cannot be mistaken for an unapplied copy.
                val response = failure.response.deepCopy()
                val parameters = response.objectOrEmpty("parameters")
                parameters.addProperty("applied", true)
                parameters.addProperty("stage", "settle")
                response.add("parameters", parameters)
                throw VarlinkException(response)
            }
        }
    }

    private fun recordedStep(actionJson: JsonObject, allowMissing: Boolean,
        operation: (Mark) -> JsonObject): StepResult {
        val before = mark()
        val rawOffset = rawLogPath?.let { path -> runCatching { Files.size(path) }.getOrDefault(0) } ?: 0
        var actionParameters: JsonObject? = null
        var actionError: JsonElement? = null
        try {
            actionParameters = operation(before)
        } catch (error: VarlinkException) {
            actionError = error.response
        } catch (error: Exception) {
            actionError = clientError(error)
        }

        var diagnosticError: String? = null
        var diagnosticsJson = JsonObject()
        try {
            diagnosticsJson = diagnose(before).objectOrEmpty("diagnostics")
        } catch (error: Exception) {
            diagnosticError = error.message ?: error.javaClass.simpleName
        }
        val diagnostics = Diagnostics.fromJson(diagnosticsJson)
        val raw = readRawLog(rawOffset)
        val after = actionParameters?.let(::findMark) ?: findMark(diagnosticsJson)
        val ok = actionError == null && diagnosticError == null && (allowMissing || diagnostics.issues.isEmpty())
        val record = JsonObject().apply {
            addProperty("ok", ok)
            add("action", actionJson)
            add("before", before.toJson())
            add("after", after?.toJson() ?: JsonObject())
            add("action_result", actionParameters?.get("result") ?: JsonNull.INSTANCE)
            add("action_error", actionError ?: JsonNull.INSTANCE)
            add("diagnostic_error", diagnosticError?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("diagnostics", diagnosticsJson)
            addProperty("raw_log", raw.first)
            addProperty("raw_log_truncated", raw.second)
        }
        appendStep(record)
        return StepResult(
            ok, actionJson, before, after, actionParameters?.get("result"), actionError,
            diagnosticError, diagnostics, raw.first, raw.second, record,
        )
    }

    fun extractMark(value: JsonObject): Mark = findMark(value)
        ?: throw IllegalArgumentException("JSON does not contain a test-control mark")

    private fun findMark(value: JsonObject): Mark? {
        if (REQUIRED_MARK_FIELDS.all(value::has)) return Mark.fromJson(value)
        for (name in listOf("mark", "observation", "diagnostics", "parameters")) {
            val nested = value.get(name)
            if (nested != null && nested.isJsonObject) findMark(nested.asJsonObject)?.let { return it }
        }
        return null
    }

    private fun waitJson(kind: WaitKind, stableFrames: Int, after: Mark? = null): JsonObject =
        JsonObject().apply {
            addProperty("kind", kind.wireName)
            addProperty("timeout_ms", timeoutMillis.coerceAtLeast(1))
            if (kind == WaitKind.IDLE || kind == WaitKind.FRAME_PRESENTED) {
                addProperty("stable_frames", stableFrames)
            }
            after?.let { add("after", it.toJson()) }
        }

    private fun exchange(request: ByteArray): String {
        // The server uses timeoutMillis as its own wait deadline. Leave a small
        // transport grace period so its timeout response wins the boundary race.
        val deadline = System.nanoTime() + (timeoutMillis + TRANSPORT_GRACE_MILLIS) * 1_000_000
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            if (!channel.connect(UnixDomainSocketAddress.of(socketPath))) {
                waitFor(channel, SelectionKey.OP_CONNECT, deadline)
                if (!channel.finishConnect()) throw SocketTimeoutException("timed out connecting to $socketPath")
            }
            val outgoing = ByteBuffer.wrap(request)
            while (outgoing.hasRemaining()) {
                if (channel.write(outgoing) == 0) waitFor(channel, SelectionKey.OP_WRITE, deadline)
            }

            val bytes = ByteArrayOutputStream()
            val incoming = ByteBuffer.allocate(65_536)
            while (true) {
                val count = channel.read(incoming)
                if (count < 0) throw IllegalStateException("test service closed before replying")
                if (count == 0) {
                    waitFor(channel, SelectionKey.OP_READ, deadline)
                    continue
                }
                incoming.flip()
                while (incoming.hasRemaining()) {
                    val byte = incoming.get()
                    if (byte.toInt() == 0) return bytes.toString(StandardCharsets.UTF_8)
                    bytes.write(byte.toInt())
                    if (bytes.size() > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("test service response exceeds $MAX_RESPONSE_BYTES bytes")
                    }
                }
                incoming.clear()
            }
        }
    }

    private fun waitFor(channel: SocketChannel, operation: Int, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("test-control request timed out")
        NioSelector.open().use { selector ->
            channel.register(selector, operation)
            val selected = selector.select(max(1, remaining / 1_000_000))
            if (selected == 0) throw SocketTimeoutException("test-control request timed out")
        }
    }

    private fun readRawLog(offset: Long): Pair<String, Boolean> {
        val path = rawLogPath ?: return "" to false
        return try {
            Files.newInputStream(path).use { input ->
                input.skipNBytes(offset)
                val content = input.readNBytes(RAW_LOG_LIMIT + 1)
                val truncated = content.size > RAW_LOG_LIMIT
                content.copyOf(minOf(content.size, RAW_LOG_LIMIT)).toString(StandardCharsets.UTF_8) to truncated
            }
        } catch (error: Exception) {
            "<could not read raw log: $error>" to false
        }
    }

    private fun appendStep(record: JsonObject) {
        val path = stepLogPath ?: return
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            gson.toJson(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun clientError(error: Exception): JsonObject = JsonObject().apply {
        addProperty("error", "ClientTransportError")
        add("parameters", JsonObject().apply {
            addProperty("message", error.message ?: error.javaClass.simpleName)
        })
    }

    companion object {
        const val INTERFACE = "dev.artzig.androlinzberg.test1"
        const val RAW_LOG_LIMIT = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val TRANSPORT_GRACE_MILLIS = 1_000L
        private val REQUIRED_MARK_FIELDS = listOf("epoch", "command_seq", "input_seq", "tree_revision")
    }
}
