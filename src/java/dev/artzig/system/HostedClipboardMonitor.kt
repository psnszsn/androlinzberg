package dev.artzig.system

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import com.android.server.LocalServices
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Host seam following ClipboardService's EmulatorClipboardMonitor integration.
 * Stock ClipboardService owns access checks, listeners, expiry and URI grants.
 * Its lock also protects the final native-import revision check, so an older
 * desktop read cannot overwrite a newer app copy during an asynchronous transfer.
 */
class HostedClipboardMonitor(private val importer: BiConsumer<Long, ClipData?>) : Consumer<ClipData?> {
    interface Publisher {
        fun publish(revision: Long, text: ByteArray?, html: ByteArray?): Boolean
    }
    private data class Packet(val text: ByteArray?, val html: ByteArray?)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var revision = 0L
    private var packet = Packet(null, null)
    private var dirty = false
    private var posted = false
    private var publisher: Publisher? = null
    private var available = false

    init { LocalServices.addService(HostedClipboardMonitor::class.java, this) }

    override fun accept(clip: ClipData?) {
        // Called under ClipboardService's lock. Do not perform Binder/FD I/O or
        // coerce a content URI here; URI staging belongs in a grant-aware seam.
        val outgoing = try { encode(clip) }
        catch (failure: IllegalArgumentException) {
            issue(failure.message ?: "Unsupported Android clipboard representation")
            Packet(null, null) // Never leave the previous native content active.
        }
        synchronized(lock) {
            check(revision < Long.MAX_VALUE) { "Clipboard revision exhausted" }
            revision++
            packet = outgoing
            dirty = true
            scheduleLocked()
        }
    }

    fun connect(publisher: Publisher, available: Boolean) {
        check(Looper.myLooper() === handler.looper)
        this.publisher = publisher
        availability(available)
    }
    fun availability(value: Boolean) {
        check(Looper.myLooper() === handler.looper)
        synchronized(lock) {
            available = value
            if (value) scheduleLocked()
        }
    }
    private fun scheduleLocked() {
        if (posted || !dirty || !available || publisher == null) return
        posted = true
        check(handler.post {
            val outgoing = synchronized(lock) {
                posted = false
                if (!dirty || !available) return@post
                revision to packet
            }
            if (publisher!!.publish(outgoing.first, outgoing.second.text, outgoing.second.html)) {
                synchronized(lock) {
                    if (revision == outgoing.first) dirty = false
                    scheduleLocked()
                }
            } else synchronized(lock) { available = false }
        })
    }

    fun changed(nativeRevision: Long, text: ByteArray?, html: ByteArray?) {
        check(Looper.myLooper() === handler.looper)
        if (!isCurrentRevision(nativeRevision)) return
        val clip = try {
            if (text == null && html == null) null else {
                val rich = html?.let(::decode)
                val plain = text?.let(::decode) ?: android.text.Html.fromHtml(rich!!, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                if (rich == null) ClipData.newPlainText("Desktop clipboard", plain)
                else ClipData.newHtmlText("Desktop clipboard", plain, rich)
            }
        } catch (failure: IllegalArgumentException) {
            issue(failure.message ?: "Invalid native clipboard payload")
            return
        }
        // The AOSP constructor callback checks the revision again under mLock.
        // This bypasses the outbound monitor, preventing an import/export loop.
        importer.accept(nativeRevision, clip)
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024
        @Volatile private var enabled = false
        @JvmStatic fun enable() { enabled = true }
        @JvmStatic fun isEnabled() = enabled
        @JvmStatic fun isCurrentRevision(revision: Long): Boolean {
            val monitor = LocalServices.getService(HostedClipboardMonitor::class.java) ?: return false
            return synchronized(monitor.lock) { revision == monitor.revision }
        }
        fun current(): HostedClipboardMonitor? = LocalServices.getService(HostedClipboardMonitor::class.java)
        fun nativeIssue(detail: String) {
            if (detail in setOf("BrokenPipe", "ConnectionResetByPeer", "Timeout", "Canceled")) {
                android.util.Log.w("WaylandClipboard", "Native clipboard peer ended the transfer: $detail")
            } else issue(detail)
        }
        fun issue(detail: String) {
            android.util.Log.w("WaylandClipboard", detail,
                dev.artzig.ArtzigTestDiagnostics.unsupported("WaylandClipboard", "transfer", detail))
        }
        private fun encode(clip: ClipData?): Packet {
            if (clip == null) return Packet(null, null)
            require(clip.itemCount == 1) { "Native clipboard currently requires one text item" }
            val item = clip.getItemAt(0)
            require(item.uri == null && item.intent == null) { "Native clipboard URI/Intent export requires a grant-aware transfer" }
            val text = requireNotNull(item.text) { "Native clipboard item has no text representation" }
            require(text.length <= MAX_BYTES) { "Clipboard text exceeds the transfer budget" }
            val html = item.htmlText
            require(html == null || html.length <= MAX_BYTES) { "Clipboard HTML exceeds the transfer budget" }
            fun bytes(value: String): ByteArray {
                require('\u0000' !in value) { "Clipboard text contains NUL" }
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                require(encoded.remaining() <= MAX_BYTES) { "Clipboard UTF-8 exceeds the transfer budget" }
                return ByteArray(encoded.remaining()).also { encoded.get(it) }
            }
            return try { Packet(bytes(text.toString()), html?.let(::bytes)) }
            catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Clipboard text contains an invalid Unicode scalar", failure)
            }
        }
        private fun decode(bytes: ByteArray): String {
            require(bytes.size <= MAX_BYTES && bytes.none { it == 0.toByte() }) { "Invalid clipboard payload size or NUL" }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Clipboard payload is not UTF-8", failure)
            }
        }
    }
}
