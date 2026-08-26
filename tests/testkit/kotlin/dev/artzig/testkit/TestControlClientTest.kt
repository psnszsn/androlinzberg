package dev.artzig.testkit

import com.google.gson.JsonObject
import java.net.StandardProtocolFamily
import java.net.SocketTimeoutException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestControlClientTest {
    @Test
    fun handlesPartialNulDelimitedResponse() {
        val directory = Files.createTempDirectory("test-control-client")
        val socket = directory.resolve("control.sock")
        val requestText = arrayOfNulls<String>(1)
        val server = thread(start = true) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                listener.accept().use { connection ->
                    val incoming = ByteBuffer.allocate(4096)
                    val bytes = mutableListOf<Byte>()
                    while (true) {
                        connection.read(incoming)
                        incoming.flip()
                        var complete = false
                        while (incoming.hasRemaining()) {
                            val byte = incoming.get()
                            if (byte.toInt() == 0) {
                                complete = true
                                break
                            }
                            bytes += byte
                        }
                        incoming.clear()
                        if (complete) break
                    }
                    requestText[0] = bytes.toByteArray().toString(StandardCharsets.UTF_8)
                    connection.write(ByteBuffer.wrap("{\"parameters\":{".toByteArray()))
                    Thread.sleep(20)
                    connection.write(ByteBuffer.wrap("\"value\":7}}\u0000".toByteArray()))
                }
            }
        }
        try {
            while (!Files.exists(socket)) Thread.sleep(5)
            val response = TestControlClient(socket.toString()).call("Probe", JsonObject())
            assertEquals(7, response["value"].asInt)
            assertTrue(requestText[0]!!.contains("dev.artzig.androlinzberg.test1.Probe"))
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun surfacesVarlinkErrors() {
        val directory = Files.createTempDirectory("test-control-error")
        val socket = directory.resolve("control.sock")
        val server = thread(start = true) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                listener.accept().use { connection ->
                    val incoming = ByteBuffer.allocate(4096)
                    while (connection.read(incoming) > 0 && incoming.array().take(incoming.position()).none { it.toInt() == 0 }) {}
                    connection.write(ByteBuffer.wrap("{\"error\":\"NotReady\",\"parameters\":{}}\u0000".toByteArray()))
                }
            }
        }
        try {
            while (!Files.exists(socket)) Thread.sleep(5)
            assertFailsWith<VarlinkException> {
                TestControlClient(socket.toString()).call("Probe", JsonObject())
            }
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsMalformedJsonResponses() {
        withServer(listOf("not-json\u0000")) { socket ->
            assertFailsWith<IllegalStateException> {
                TestControlClient(socket.toString()).call("Probe", JsonObject())
            }
        }
    }

    @Test
    fun timesOutWhenServerDoesNotReply() {
        val directory = Files.createTempDirectory("test-control-timeout")
        val socket = directory.resolve("control.sock")
        val server = thread(start = true) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                listener.accept().use { Thread.sleep(1_500) }
            }
        }
        try {
            while (!Files.exists(socket)) Thread.sleep(5)
            assertFailsWith<SocketTimeoutException> {
                TestControlClient(socket.toString(), timeoutMillis = 30).call("Probe", JsonObject())
            }
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun stepUsesRawLogOffsetAndAppendsOneJsonlRecord() {
        val rawLog = Files.createTempFile("test-control", ".log")
        val steps = Files.createTempFile("test-control", ".jsonl")
        Files.writeString(rawLog, "startup\n")
        val before = """{"epoch":"e","command_seq":1,"input_seq":2,"tree_revision":3,"log_seq":4,"issue_seq":5}"""
        val after = """{"epoch":"e","command_seq":6,"input_seq":7,"tree_revision":8,"log_seq":9,"issue_seq":10}"""
        val responses = listOf(
            """{"parameters":{"observation":{"mark":$before}}}""" + '\u0000',
            """{"parameters":{"result":{"applied":true},"observation":{"mark":$after}}}""" + '\u0000',
            """{"parameters":{"diagnostics":{"mark":$after,"runtime":{"logs":[],"issues":[]},"tree":{"revision":8}}}}""" + '\u0000',
        )
        withServer(responses, beforeResponse = { index ->
            if (index == 1) Files.writeString(rawLog, "action\n", StandardOpenOption.APPEND)
        }) { socket ->
            val result = TestControlClient(
                socket.toString(),
                rawLogPath = rawLog,
                stepLogPath = steps,
            ).step(
                Selector(text = "Run"),
                Action(ActionKind.CLICK, InputVia.WAYLAND),
            )
            assertTrue(result.ok)
            assertEquals("action\n", result.rawLog)
            assertEquals(8, result.accessibility.revision)
            assertEquals(1, Files.readAllLines(steps).size)
        }
        Files.deleteIfExists(rawLog)
        Files.deleteIfExists(steps)
    }

    private fun withServer(
        responses: List<String>,
        beforeResponse: (Int) -> Unit = {},
        block: (java.nio.file.Path) -> Unit,
    ) {
        val directory = Files.createTempDirectory("test-control-server")
        val socket = directory.resolve("control.sock")
        val server = thread(start = true) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                responses.forEachIndexed { index, response ->
                    listener.accept().use { connection ->
                        val incoming = ByteBuffer.allocate(4096)
                        var complete = false
                        while (!complete) {
                            if (connection.read(incoming) < 0) break
                            incoming.flip()
                            while (incoming.hasRemaining()) {
                                if (incoming.get().toInt() == 0) {
                                    complete = true
                                    break
                                }
                            }
                            incoming.clear()
                        }
                        beforeResponse(index)
                        connection.write(ByteBuffer.wrap(response.toByteArray(StandardCharsets.UTF_8)))
                    }
                }
            }
        }
        try {
            while (!Files.exists(socket)) Thread.sleep(5)
            block(socket)
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }
}
