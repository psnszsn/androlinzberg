package dev.artzig.desktop

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.CancellationException

/** Immutable file leases for desktop URI transfers. No Android permission policy
 * lives here: the provider owns access, and ClipboardService owns recipients.
 * Work reserves its worst-case storage before opening an untrusted source.
 */
class DesktopTransferStore(private val root: File, private val clock: () -> Long = System::nanoTime) {
    data class Source(val name: String, val type: String, val describe: (() -> Pair<String, String>)? = null, val open: (deadlineNanos: Long) -> InputStream)
    data class Item(val file: File, val name: String, val type: String, val size: Long)
    data class Lease(val token: String, val items: List<Item>, val exported: Boolean)
    private class Work { @Volatile var cancelled = false }
    private val lock = Any()
    private val active = HashMap<String, Work>()
    private val leases = HashMap<String, Lease>()
    private var reserved = 0L
    private var stored = 0L

    init {
        check(root.mkdirs() || root.isDirectory) { "Cannot create desktop transfer directory" }
        // Provider processes are session scoped. Never revive old URI capabilities.
        root.listFiles()?.forEach { check(it.deleteRecursively()) { "Cannot retire old desktop transfer" } }
    }

    fun stage(token: String, sources: List<Source>, exported: Boolean): Lease {
        requireToken(token)
        require(sources.size in 1..MAX_FILES) { "Desktop transfer file count exceeds budget" }
        val work = Work()
        synchronized(lock) {
            require(token !in active && token !in leases) { "Duplicate desktop transfer token" }
            check(active.size < MAX_WORKERS && stored + reserved + MAX_TRANSFER <= MAX_STORAGE) {
                "Desktop transfer storage or worker budget exhausted"
            }
            active[token] = work
            reserved += MAX_TRANSFER
        }
        val deadline = clock() + TIMEOUT_NANOS
        val directory = File(root, token)
        fun checkLive() {
            if (work.cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Desktop transfer cancelled")
            check(clock() < deadline) { "Desktop transfer deadline exceeded" }
        }
        var committed = false
        try {
            check(directory.mkdir()) { "Cannot create desktop transfer lease" }
            var total = 0L
            val items = sources.mapIndexed { index, source ->
                checkLive()
                val (name, type) = source.describe?.invoke() ?: (source.name to source.type)
                checkLive()
                require(name.length in 1..MAX_NAME && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "Invalid desktop file name" }
                require(type.length in 1..128 && '\u0000' !in type) { "Invalid desktop MIME type" }
                val itemDirectory = File(directory, index.toString())
                check(itemDirectory.mkdir())
                val file = File(itemDirectory, name)
                var size = 0L
                source.open(deadline).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            checkLive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(count > 0) { "Desktop source made no progress" }
                            size += count
                            total += count
                            check(size <= MAX_FILE && total <= MAX_TRANSFER) { "Desktop transfer byte budget exceeded" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                checkLive()
                Item(file, name, type, size)
            }
            val lease = Lease(token, items, exported)
            synchronized(lock) {
                checkLive()
                leases[token] = lease
                stored += total
                committed = true
            }
            return lease
        } finally {
            val removed = committed || directory.deleteRecursively()
            synchronized(lock) {
                active.remove(token)
                // Failed cleanup still consumes its storage reservation. Do not
                // admit more files while orphaned bytes occupy the disk budget.
                if (removed) reserved -= MAX_TRANSFER
            }
            check(removed) { "Cannot clean up incomplete desktop transfer" }
        }
    }

    fun item(token: String, index: Int): Item = synchronized(lock) {
        leases[token]?.items?.getOrNull(index) ?: throw FileNotFoundException("Retired desktop URI")
    }
    fun release(token: String, discard: Boolean = false) {
        val lease = synchronized(lock) {
            active[token]?.cancelled = true
            val found = leases[token] ?: return
            // text/uri-list has no acknowledgement for the recipient's later
            // file open. Retain exported files until session exit, under the
            // same hard storage budget; never unlink a successfully offered file.
            if (found.exported && !discard) return
            leases.remove(token)
            found
        }
        check(File(root, token).deleteRecursively()) { "Cannot retire desktop transfer" }
        synchronized(lock) { stored -= lease.items.sumOf { it.size } }
    }

    companion object {
        const val MAX_FILES = 16
        const val MAX_NAME = 255
        const val MAX_FILE = 8L * 1024 * 1024
        const val MAX_TRANSFER = 32L * 1024 * 1024
        const val MAX_STORAGE = 64L * 1024 * 1024
        const val MAX_WORKERS = 2
        const val TIMEOUT_NANOS = 5_000_000_000L
        fun token(): String = UUID.randomUUID().toString()
        fun requireToken(token: String) {
            require(token.length == 36 && UUID.fromString(token).toString() == token) { "Invalid desktop transfer token" }
        }
        /** RFC text/uri-list: comments and CRLF, absolute local file URIs only.
         * URI decoding happens once. No query/fragment, remote host or NUL may
         * turn into a file path, and the provider separately refuses non-files.
         */
        fun parseUriList(value: String): List<File> {
            require(value.length <= 256 * 1024 && '\u0000' !in value) { "Invalid desktop URI list size or NUL" }
            val result = ArrayList<File>()
            value.lineSequence().forEach { line ->
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                require(result.size < MAX_FILES) { "Desktop URI count exceeds budget" }
                val uri = URI(line)
                require(uri.scheme.equals("file", true) && !uri.isOpaque && uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.rawAuthority.isNullOrEmpty() || uri.rawAuthority.equals("localhost", true))) {
                    "Desktop transfer requires an absolute local file URI"
                }
                val path = uri.path
                require(path != null && path.startsWith('/') && '\u0000' !in path) { "Invalid desktop file URI path" }
                result.add(File(path))
            }
            require(result.isNotEmpty()) { "Desktop URI list is empty" }
            return result
        }
    }
}
