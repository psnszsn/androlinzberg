package dev.artzig.uitest

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.ServiceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Stock statsd, as `adb shell dumpsys stats --metadata` reads it. */
class StatsdUiTest {
    @Test
    fun atomsReachStatsdAndAreInspectable() {
        UiApp.expectVisible("Counter: 0")
        val stats = ServiceManager.waitForService("stats")
        // Counted pushed atoms prove Java and native clients reach the socket.
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        var report = dump(stats)
        while (!PUSHED_ATOM.containsMatchIn(report) && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(250)
            report = dump(stats)
        }
        assertTrue(report, PUSHED_ATOM.containsMatchIn(report))
        // StatsCompanion sends the uid map over a pipe when statsd says hello.
        var uids = command(stats, "print-uid-map")
        while (!uids.contains(FIXTURE) && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(250)
            uids = command(stats, "print-uid-map")
        }
        assertTrue(uids, uids.contains(FIXTURE))
    }

    @Test
    fun decodedImagesAreCountedByNativeClients() {
        UiApp.expectVisible("Counter: 0")
        val stats = ServiceManager.waitForService("stats")
        val png = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)
        // HWUI's own IMAGE_DECODED atom, through libstatssocket rather than Java's StatsLog.
        checkNotNull(android.graphics.BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size()))
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        var report = dump(stats)
        while (!IMAGE_DECODED.containsMatchIn(report) && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(250)
            report = dump(stats)
        }
        assertTrue(report, IMAGE_DECODED.containsMatchIn(report))
    }

    @Test
    fun configuredMetricIsStoredAndReported() {
        UiApp.expectVisible("Counter: 0")
        val stats = ServiceManager.waitForService("stats")
        command(stats, "config", "update", CONFIG.toString(), input = config())
        // ConfigManager persists each configuration under /data/misc/stats-service.
        val report = dump(stats)
        assertTrue(report, report.contains("Config ID: $CONFIG,"))
        android.util.StatsLog.write(android.util.StatsEvent.newBuilder().setAtomId(ATOM).writeInt(7).usePooledBuffer().build())
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        do {
            Thread.sleep(250)
            val atom = varint((ATOM.toLong() shl 3) or 2)
            val data = command(stats, "dump-report", CONFIG.toString(), "--keep_data", "--proto", bytes = true)
            if (data.toList().windowed(atom.size).any { it == atom.toList() }) return
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        throw AssertionError("The report never contained atom $ATOM")
    }

    /** A StatsdConfig with one EventMetric on [ATOM], in protobuf wire format. */
    private fun config(): ByteArray {
        val matcher = field(1, varint(MATCHER)) + message(2, field(1, varint(ATOM.toLong())))
        val metric = field(1, varint(METRIC)) + field(2, varint(MATCHER), wire = 0)
        return field(1, varint(CONFIG)) + message(2, metric) + message(7, matcher) +
            message(12, "AID_SYSTEM".toByteArray()) + field(24, varint(ATOM.toLong()))
    }

    private fun varint(value: Long): ByteArray {
        var rest = value
        val out = java.io.ByteArrayOutputStream()
        while (rest >= 0x80) {
            out.write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }
    private fun field(number: Int, encoded: ByteArray, wire: Int = 0) = varint((number.toLong() shl 3) or wire.toLong()) + encoded
    private fun message(number: Int, body: ByteArray) = field(number, varint(body.size.toLong()) + body, wire = 2)

    /** `dumpsys stats --metadata`. */
    private fun dump(stats: IBinder): String {
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = CompletableFuture.supplyAsync {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.readBytes().decodeToString() }
        }
        pipe[1].use { stats.dump(it.fileDescriptor, arrayOf("--metadata")) }
        return reader.get()
    }

    /** `cmd stats <args>`: a Binder shell command from the shell uid. */
    private fun command(stats: IBinder, vararg args: String, input: ByteArray = ByteArray(0)): String =
        run(stats, args, input).decodeToString()

    private fun command(stats: IBinder, vararg args: String, bytes: Boolean): ByteArray {
        check(bytes)
        return run(stats, args, ByteArray(0))
    }

    private fun run(stats: IBinder, args: Array<out String>, input: ByteArray): ByteArray {
        val request = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(request[1]).use { it.write(input) }
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = CompletableFuture.supplyAsync {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.readBytes() }
        }
        val code = CompletableFuture<Int>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code.complete(resultCode) }
        }
        request[0].use { source ->
            pipe[1].use { stats.shellCommand(source.fileDescriptor, it.fileDescriptor, it.fileDescriptor, args, null, receiver) }
        }
        assertEquals(0, code.get(30, TimeUnit.SECONDS))
        return reader.get()
    }

    private companion object {
        val PUSHED_ATOM = Regex("""Atom \d+->\(total count\)[1-9]""")
        val IMAGE_DECODED = Regex("""Atom 977->\(total count\)[1-9]""")
        const val FIXTURE = "dev.artzig.fixtures.windows"
        /** In the atom id range for private use. */
        const val ATOM = 100_001
        const val CONFIG = 11L
        const val METRIC = 22L
        const val MATCHER = 33L
    }
}
