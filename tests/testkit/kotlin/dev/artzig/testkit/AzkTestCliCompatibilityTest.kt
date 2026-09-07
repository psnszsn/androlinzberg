package dev.artzig.testkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzkTestCliCompatibilityTest {
    @Test
    fun preservesRequestsForEveryDirectCommand() {
        val cases = listOf(
            Case(arrayOf("observe", "--task-id", "10", "--window-id", "4294967297", "--pid", "100", "--process-generation", "3"), "Observe") {
                val selector = it.parameters["selector"].asJsonObject
                assertEquals(10, selector["task_id"].asInt)
                assertEquals(4294967297L, selector["window_id"].asLong)
                assertEquals(100, selector["pid"].asInt)
                assertEquals(3L, selector["process_generation"].asLong)
            },
            Case(arrayOf("observe", "--text=Ready", "--tree", "--wait=exists"), "Observe") {
                assertEquals("Ready", it.parameters["selector"].asJsonObject["text"].asString)
                assertTrue(it.parameters["include_tree"].asBoolean)
                assertEquals("exists", it.waitKind)
            },
            Case(arrayOf("wait", "idle"), "Observe") { assertEquals("idle", it.waitKind) },
            Case(arrayOf("click", "--text", "Run", "--via", "semantic", "--settle", "now"), "Perform") {
                assertEquals("click", it.actionKind)
                assertEquals("semantic", it.actionVia)
            },
            Case(
                arrayOf(
                    "drag", "--role", "slider", "--from-x", "0.1", "--from-y", "0.5",
                    "--to-x", "0.8", "--to-y", "0.5",
                ),
                "Perform",
            ) {
                assertEquals("drag", it.actionKind)
                assertEquals("wayland", it.actionVia)
                assertEquals(0.1, it.action["from_x_fraction"].asDouble)
                assertEquals(0.8, it.action["to_x_fraction"].asDouble)
            },
            Case(
                arrayOf("scroll-forward", "--role", "slider", "--via", "semantic"),
                "Perform",
            ) {
                assertEquals("scroll_forward", it.actionKind)
                assertEquals("semantic", it.actionVia)
            },
            Case(arrayOf("type", "hello", "--id", "field", "--via", "semantic"), "Perform") {
                assertEquals("set_text", it.actionKind)
                assertEquals("hello", it.action["text"].asString)
            },
            Case(arrayOf("set-text", "hello", "--class", "android.widget.EditText"), "Perform") {
                assertEquals("set_text", it.actionKind)
                assertEquals("semantic", it.actionVia)
            },
            Case(arrayOf("key", "Escape", "--role", "field"), "Perform") {
                assertEquals("key", it.actionKind)
                assertEquals("Escape", it.action["key"].asString)
            },
        )
        for (case in cases) {
            withServer(listOf(successResponse())) { socket, requests ->
                val result = runCli(case.arguments, mapOf("AZK_TEST_SOCKET" to socket.toString()))
                assertEquals(0, result.status, result.errors)
                assertEquals(case.method, requests.single()["method"].asString.substringAfterLast('.'))
                case.verify(RequestView(requests.single().getAsJsonObject("parameters")))
            }
        }
    }

    @Test
    fun markAndAtFileDiagnosisRetainTheirJsonProtocol() {
        val mark = markJson()
        withServer(listOf("""{"parameters":{"observation":{"mark":$mark}}}""" + '\u0000')) { socket, _ ->
            val result = runCli(arrayOf("mark"), mapOf("AZK_TEST_SOCKET" to socket.toString()))
            assertEquals(0, result.status)
            assertEquals("epoch", JsonParser.parseString(result.output).asJsonObject["mark"].asJsonObject["epoch"].asString)
        }

        val markFile = Files.createTempFile("azk-mark", ".json")
        Files.writeString(markFile, """{"mark":$mark}""")
        try {
            withServer(listOf(successResponse())) { socket, requests ->
                val result = runCli(
                    arrayOf("diagnose", "--since", "@${markFile}"),
                    mapOf("AZK_TEST_SOCKET" to socket.toString()),
                )
                assertEquals(0, result.status)
                assertEquals("epoch", requests.single()["parameters"].asJsonObject["since"].asJsonObject["epoch"].asString)
            }
        } finally {
            Files.deleteIfExists(markFile)
        }
    }

    @Test
    fun failedStepAppendsJsonlAndUsesExitCodeThree() {
        val mark = markJson()
        val responses = listOf(
            """{"parameters":{"observation":{"mark":$mark}}}""" + '\u0000',
            """{"parameters":{"result":{"applied":true},"observation":{"mark":$mark}}}""" + '\u0000',
            """{"parameters":{"diagnostics":{"mark":$mark,"runtime":{"logs":[],"issues":[{"seq":1,"kind":"unimplemented","owner":"android.view.View","member":"probe()","detail":"missing","thread":"main"}]}}}}""" + '\u0000',
        )
        val stepLog = Files.createTempFile("azk-steps", ".jsonl")
        try {
            withServer(responses) { socket, _ ->
                val result = runCli(
                    arrayOf("step", "click", "--text", "Run"),
                    mapOf(
                        "AZK_TEST_SOCKET" to socket.toString(),
                        "AZK_TEST_STEP_LOG" to stepLog.toString(),
                    ),
                )
                assertEquals(3, result.status)
                assertTrue(!JsonParser.parseString(result.output).asJsonObject["ok"].asBoolean)
                assertEquals(1, Files.readAllLines(stepLog).size)
            }
            withServer(responses) { socket, _ ->
                val result = runCli(
                    arrayOf("step", "click", "--text", "Run", "--allow-missing"),
                    mapOf(
                        "AZK_TEST_SOCKET" to socket.toString(),
                        "AZK_TEST_STEP_LOG" to stepLog.toString(),
                    ),
                )
                assertEquals(0, result.status)
                assertTrue(JsonParser.parseString(result.output).asJsonObject["ok"].asBoolean)
                assertEquals(2, Files.readAllLines(stepLog).size)
            }
        } finally {
            Files.deleteIfExists(stepLog)
        }
    }

    @Test
    fun transportFailuresUseExitCodeOne() {
        val missing = Files.createTempDirectory("azk-missing-socket").resolve("control.sock")
        val result = runCli(arrayOf("mark"), mapOf("AZK_TEST_SOCKET" to missing.toString()))
        assertEquals(1, result.status)
        assertTrue(result.errors.startsWith("azk-test:"))
        Files.deleteIfExists(missing.parent)
    }

    private fun runCli(arguments: Array<String>, environment: Map<String, String>): CliResult {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val status = AzkTestCli.run(arguments, environment, PrintStream(output), PrintStream(errors))
        return CliResult(status, output.toString(), errors.toString())
    }

    private fun withServer(
        responses: List<String>,
        block: (java.nio.file.Path, List<JsonObject>) -> Unit,
    ) {
        val directory = Files.createTempDirectory("azk-cli-server")
        val socket = directory.resolve("control.sock")
        val requests = mutableListOf<JsonObject>()
        val server = thread(start = true) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                for (response in responses) {
                    listener.accept().use { connection ->
                        val bytes = mutableListOf<Byte>()
                        val buffer = ByteBuffer.allocate(4096)
                        var complete = false
                        while (!complete) {
                            connection.read(buffer)
                            buffer.flip()
                            while (buffer.hasRemaining()) {
                                val byte = buffer.get()
                                if (byte.toInt() == 0) {
                                    complete = true
                                    break
                                }
                                bytes += byte
                            }
                            buffer.clear()
                        }
                        synchronized(requests) {
                            requests += JsonParser.parseString(
                                bytes.toByteArray().toString(StandardCharsets.UTF_8),
                            ).asJsonObject
                        }
                        connection.write(ByteBuffer.wrap(response.toByteArray(StandardCharsets.UTF_8)))
                    }
                }
            }
        }
        try {
            while (!Files.exists(socket)) Thread.sleep(5)
            block(socket, requests)
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }

    private fun successResponse(): String = """{"parameters":{"result":{"applied":true}}}""" + '\u0000'

    private fun markJson(): String =
        """{"epoch":"epoch","command_seq":1,"input_seq":2,"tree_revision":3,"frame_seq":4,"log_seq":5,"issue_seq":6}"""

    private data class CliResult(val status: Int, val output: String, val errors: String)
    private data class Case(
        val arguments: Array<String>,
        val method: String,
        val verify: (RequestView) -> Unit,
    )

    private class RequestView(val parameters: JsonObject) {
        val action: JsonObject get() = parameters["action"].asJsonObject
        val actionKind: String get() = action["kind"].asString
        val actionVia: String get() = action["via"].asString
        val waitKind: String get() = (parameters["wait"] ?: parameters["settle"]).asJsonObject["kind"].asString
    }
}
