package dev.artzig.uitest

import android.net.DnsResolver
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsResolverUiTest {
    @Test
    fun nameQueriesPreserveNxDomain() {
        UiApp.expectVisible("Counter: 0")
        val result = CompletableFuture<Pair<ByteArray, Int>>()
        DnsResolver.getInstance().rawQuery(null, "androlinzberg.invalid", 1, 15,
            DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
            Executor { it.run() }, null, callback(result))
        val (answer, rcode) = result.get(30, TimeUnit.SECONDS)
        assertTrue("DNS header", answer.size >= 12)
        assertEquals("NXDOMAIN remains a DNS answer", 3, rcode)
    }

    private fun callback(result: CompletableFuture<Pair<ByteArray, Int>>) =
        object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                result.complete(Pair(answer, rcode))
            }
            override fun onError(error: DnsResolver.DnsException) {
                result.completeExceptionally(error)
            }
        }

    @Test
    fun rawQueriesReturnDnsPackets() {
        UiApp.expectVisible("Counter: 0")
        // FairEmail uses this byte-packet overload for mail-service discovery.
        // An absent SRV record is a DNS answer, never a missing local socket.
        val packet = ByteArrayOutputStream()
        DataOutputStream(packet).use { out ->
            out.writeShort(0x1234)
            out.writeShort(0x0100)
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            for (label in "_imaps._tcp.praznet.com".split('.')) {
                out.writeByte(label.length)
                out.writeBytes(label)
            }
            out.writeByte(0)
            out.writeShort(33) // SRV
            out.writeShort(1) // IN
        }
        val result = CompletableFuture<Pair<ByteArray, Int>>()
        DnsResolver.getInstance().rawQuery(null, packet.toByteArray(),
            DnsResolver.FLAG_EMPTY, Executor { it.run() }, null,
            callback(result))
        val (answer, rcode) = result.get(30, TimeUnit.SECONDS)
        assertTrue("DNS header", answer.size >= 12)
        assertEquals(0x12, answer[0].toInt() and 255)
        assertEquals(0x34, answer[1].toInt() and 255)
        assertTrue("DNS response bit", answer[2].toInt() and 0x80 != 0)
        assertTrue("DNS answer or NXDOMAIN: $rcode", rcode == 0 || rcode == 3)
    }
}
