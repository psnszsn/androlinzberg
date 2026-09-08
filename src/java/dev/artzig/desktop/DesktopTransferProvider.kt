package dev.artzig.desktop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileNotFoundException

/** Host-specific staging storage, installed with an ordinary package UID.
 * ContentProvider.Transport enforces the grants that stock ClipboardService and
 * DragAndDropPermissionsHandler issue. Only system_server may create leases.
 */
class DesktopTransferProvider : ContentProvider() {
    private lateinit var store: DesktopTransferStore
    override fun onCreate(): Boolean {
        store = DesktopTransferStore(java.io.File(requireNotNull(context).cacheDir, "desktop-transfers"))
        return true
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("Only the desktop bridge may manage file leases")
        val token = requireNotNull(arg)
        DesktopTransferStore.requireToken(token)
        if (method == "release" || method == "discard") {
            store.release(token, discard = method == "discard")
            return Bundle.EMPTY
        }
        require(method == "import" || method == "export") { "Unknown desktop transfer operation" }
        val values = requireNotNull(extras)
        val exported = method == "export"
        val resolver = requireNotNull(context).contentResolver
        val sources = if (!exported) {
            DesktopTransferStore.parseUriList(requireNotNull(values.getString("uris"))).map { file ->
                DesktopTransferStore.Source(file.name, "application/octet-stream") { deadline ->
                    // No FIFO/device reads, and no symlink substitution at the final
                    // component. Adopt the descriptor immediately and close on every path.
                    val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    val stream = FileInputStream(fd, true)
                    try {
                        require(OsConstants.S_ISREG(Os.fstat(fd).st_mode)) { "Desktop URI is not a regular file" }
                        timed(stream, fd, deadline)
                    } catch (failure: Throwable) { stream.close(); throw failure }
                }
            }
        } else {
            val uris = requireNotNull(values.getStringArrayList("uris"))
            require(uris.size in 1..DesktopTransferStore.MAX_FILES && uris.sumOf { it.length } <= 256 * 1024)
            uris.map { value ->
                val uri = Uri.parse(value)
                require(uri.scheme == "content") { "Android desktop export requires content URIs" }
                // The bridge grants this package temporary READ access on behalf
                // of ClipboardService's authenticated source UID before this call.
                val name = uri.lastPathSegment ?: "file"
                val type = values.getString("type") ?: "application/octet-stream"
                DesktopTransferStore.Source(name, type, describe = {
                    var displayName = name
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) displayName = cursor.getString(0)
                    }
                    displayName to (resolver.getType(uri) ?: type)
                }) { deadline ->
                    val asset = requireNotNull(resolver.openAssetFileDescriptor(uri, "r")) { "Content provider returned no file" }
                    try { timed(asset.createInputStream(), asset.parcelFileDescriptor.fileDescriptor, deadline) }
                    catch (failure: Throwable) { asset.close(); throw failure }
                }
            }
        }
        // Control calls arrive with system identity. ContentResolver must acquire
        // and read source providers under THIS package's UID and temporary grant.
        val identity = Binder.clearCallingIdentity()
        try {
            val lease = store.stage(token, sources, exported)
            return Bundle().apply {
                putInt("uid", Process.myUid())
                putStringArrayList("uris", ArrayList(lease.items.indices.map { index ->
                    Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).appendPath(index.toString()).build().toString()
                }))
                putString("native", lease.items.joinToString("\r\n", postfix = "\r\n") { it.file.toURI().toASCIIString() })
                putStringArrayList("types", ArrayList(lease.items.map { it.type }))
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun timed(input: java.io.InputStream, fd: java.io.FileDescriptor, deadline: Long): java.io.InputStream {
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            return object : java.io.InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    while (true) {
                        check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "Desktop file read timed out" }
                        val poll = android.system.StructPollfd().apply {
                            this.fd = fd
                            events = OsConstants.POLLIN.toShort()
                        }
                        try { if (Os.poll(arrayOf(poll), 100) == 0) continue }
                        catch (failure: android.system.ErrnoException) {
                            if (failure.errno == OsConstants.EINTR) continue
                            throw failure
                        }
                        try {
                            val count = input.read(bytes, offset, length)
                            // Pinned IoBridge.read maps EAGAIN to zero.
                            if (count == 0) continue
                            return count
                        }
                        catch (failure: java.io.IOException) {
                            val cause = failure.cause as? android.system.ErrnoException
                            if (cause?.errno != OsConstants.EAGAIN) throw failure
                        }
                    }
                }
                override fun close() = input.close()
            }
        } catch (failure: Throwable) { input.close(); throw failure }
    }
    private fun item(uri: Uri): DesktopTransferStore.Item {
        if (uri.scheme != "content" || uri.authority != AUTHORITY || uri.query != null || uri.fragment != null || uri.pathSegments.size != 2)
            throw FileNotFoundException("Invalid desktop URI")
        val token = uri.pathSegments[0]
        DesktopTransferStore.requireToken(token)
        val index = uri.pathSegments[1].toIntOrNull() ?: throw FileNotFoundException("Invalid desktop item")
        if (index.toString() != uri.pathSegments[1]) throw FileNotFoundException("Invalid desktop item")
        return store.item(token, index)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Desktop transfers are read-only")
        return ParcelFileDescriptor.open(item(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        return openFile(uri, mode)
    }
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        require(selection == null && selectionArgs == null && sortOrder == null)
        val value = item(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(columns.size <= 16)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> value.name
                OpenableColumns.SIZE -> value.size
                else -> null
            } }.toTypedArray<Any?>())
        }
    }
    override fun getType(uri: Uri): String = item(uri).type
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only desktop transfer")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Read-only desktop transfer")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Read-only desktop transfer")
    companion object { const val AUTHORITY = "dev.artzig.desktop.transfers" }
}
