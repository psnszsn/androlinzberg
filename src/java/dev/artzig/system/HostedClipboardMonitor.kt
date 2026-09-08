package dev.artzig.system

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import com.android.server.LocalServices
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.function.Consumer

/** Host seam following ClipboardService's EmulatorClipboardMonitor integration.
 * Stock ClipboardService owns access checks, listeners, expiry and URI grants.
 * Its lock also protects the final native-import revision check, so an older
 * desktop read cannot overwrite a newer app copy during an asynchronous transfer.
 */
class HostedClipboardMonitor(context: android.content.Context, private val importer: Importer) : Consumer<ClipData?> {
    fun interface Importer { fun accept(token: Long, clip: ClipData?, sourceUid: Int) }
    interface Publisher {
        fun publish(revision: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?): Boolean
    }
    private data class Packet(val text: ByteArray?, val html: ByteArray?, val uris: ByteArray? = null, val lease: String? = null, var submitted: Boolean = false)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val transfers = HostedDesktopTransfers.get(context, handler)
    private var operation = 0L
    private var imported: String? = null
    private data class Export(val token: Long, val clip: ClipData?, val uid: Int)
    private var pendingExport: Export? = null
    private var stagingPosted = false
    private var revision = 0L
    private var packet = Packet(null, null)
    private var dirty = false
    private var posted = false
    private var publisher: Publisher? = null
    private var available = false

    init { LocalServices.addService(HostedClipboardMonitor::class.java, this) }

    override fun accept(clip: ClipData?) = acceptFromAndroid(clip, android.os.Process.SYSTEM_UID)

    fun acceptFromAndroid(clip: ClipData?, sourceUid: Int) {
        // Called after stock checkDataOwner, under ClipboardService's lock.
        // Copy metadata here; grant/provider/FD work runs outside that lock.
        var files: ClipData? = null
        val outgoing = try {
            if (clip != null && clip.itemCount in 1..16 &&
                (0 until clip.itemCount).any { clip.getItemAt(it).uri != null }) {
                require((0 until clip.itemCount).all {
                    val item = clip.getItemAt(it)
                    item.uri?.scheme == "content" && item.intent == null && item.uri.toString().length <= 16 * 1024
                }) { "Desktop file export requires content URI items" }
                files = ClipData(clip)
                Packet(null, null)
            } else encode(clip)
        } catch (failure: IllegalArgumentException) {
            issue(failure.message ?: "Unsupported Android clipboard representation")
            Packet(null, null)
        }
        val pending = files
        synchronized(lock) {
            check(revision < Long.MAX_VALUE && operation < Long.MAX_VALUE)
            revision++
            val token = ++operation
            replacePacketLocked(outgoing)
            dirty = true
            pendingExport = Export(token, pending, sourceUid)
            scheduleStagingLocked()
            scheduleLocked()
        }
    }
    private fun scheduleStagingLocked() {
        if (stagingPosted) return
        stagingPosted = true
        check(handler.post {
            val request = synchronized(lock) {
                stagingPosted = false
                pendingExport.also { pendingExport = null }
            } ?: return@post
            retireImport()
            if (request.clip != null && isCurrentImport(request.token)) transfers.stage(request.clip, request.uid, null) { result ->
                result.fold({ staged ->
                    val current = synchronized(lock) {
                        if (operation != request.token) false else {
                            check(revision < Long.MAX_VALUE)
                            revision++
                            replacePacketLocked(Packet(null, null, staged.native, staged.token))
                            dirty = true
                            scheduleLocked()
                            true
                        }
                    }
                    transfers.release(staged.token, discard = !current)
                }, { failure ->
                    if (isCurrentImport(request.token)) issue(failure.message ?: "Android URI staging failed")
                })
            }
        })
    }
    private fun replacePacketLocked(replacement: Packet) {
        val previous = packet
        packet = replacement
        if (!previous.submitted) previous.lease?.let { lease ->
            check(handler.post { transfers.release(lease, discard = true) })
        }
    }
    private fun retireImport() {
        imported?.let { transfers.release(it) }
        imported = null
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
                // Once admitted to Binder, the native source could expose these
                // paths even if its acknowledgement is lost. Retain conservatively.
                packet.submitted = true
                revision to packet
            }
            if (publisher!!.publish(outgoing.first, outgoing.second.text, outgoing.second.html, outgoing.second.uris)) {
                synchronized(lock) {
                    if (revision == outgoing.first) dirty = false
                    scheduleLocked()
                }
            } else synchronized(lock) { available = false }
        })
    }

    fun changed(nativeRevision: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?) {
        check(Looper.myLooper() === handler.looper)
        val token = synchronized(lock) {
            if (nativeRevision != revision) return
            check(operation < Long.MAX_VALUE)
            ++operation
        }
        retireImport()
        if (uris != null) {
            val list = try { decode(uris) } catch (failure: IllegalArgumentException) {
                issue(failure.message ?: "Invalid desktop URI list")
                return
            }
            transfers.stage(null, android.os.Process.SYSTEM_UID, list) { result ->
                result.fold({ staged ->
                    if (isCurrentImport(token)) {
                        importer.accept(token, staged.clip, staged.uid)
                        if (isCurrentImport(token)) imported = staged.token else transfers.release(staged.token)
                    } else transfers.release(staged.token)
                }, { failure -> if (isCurrentImport(token)) issue(failure.message ?: "Native URI staging failed") })
            }
            return
        }
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
        importer.accept(token, clip, android.os.Process.SYSTEM_UID)
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024
        @Volatile private var enabled = false
        @JvmStatic fun enable() { enabled = true }
        @JvmStatic fun isEnabled() = enabled
        @JvmStatic fun isCurrentImport(token: Long): Boolean {
            val monitor = LocalServices.getService(HostedClipboardMonitor::class.java) ?: return false
            return synchronized(monitor.lock) { token == monitor.operation }
        }
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
        internal fun decode(bytes: ByteArray): String {
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
