package android.app

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Host adaptation of AOSP DownloadManager and DownloadProvider.
 *
 * AOSP DownloadManager delegates request storage and asynchronous transfer to the out-of-process
 * DownloadProvider/DownloadService. Those services cannot be compiled for this single-process host
 * because they require system_server scheduling, provider installation, network policy, and system
 * notifications. This class preserves the AOSP application-facing request, query, status, file,
 * and completion-broadcast contracts behind an in-process download backend.
 */
class DownloadManager(private val context: Context) {
    class Request(private val source: Uri) {
        companion object {
            const val NETWORK_MOBILE: Int = 1
            const val NETWORK_WIFI: Int = 1 shl 1

            const val VISIBILITY_VISIBLE: Int = 0
            const val VISIBILITY_VISIBLE_NOTIFY_COMPLETED: Int = 1
            const val VISIBILITY_HIDDEN: Int = 2
            const val VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION: Int = 3
        }

        internal var destination: Uri? = null
            private set
        internal var title: CharSequence? = null
            private set
        internal var description: CharSequence? = null
            private set
        internal var mimeType: String? = null
            private set
        internal var allowedNetworkTypes: Int = NETWORK_MOBILE or NETWORK_WIFI
            private set
        internal var notificationVisibility: Int = VISIBILITY_VISIBLE
            private set
        internal val headers = mutableListOf<Pair<String, String>>()

        init {
            val scheme = source.scheme
            require(scheme == "http" || scheme == "https") {
                "Can only download HTTP/HTTPS URIs: $source"
            }
        }

        fun setDestinationUri(uri: Uri): Request = apply { destination = uri }

        fun setDestinationInExternalFilesDir(
            context: Context,
            dirType: String?,
            subPath: String,
        ): Request = apply {
            val directory = context.getExternalFilesDir(dirType)
                ?: throw IllegalStateException("Failed to get external storage files directory")
            if (directory.exists() && !directory.isDirectory) {
                throw IllegalStateException("${directory.absolutePath} is not a directory")
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Unable to create directory: ${directory.absolutePath}")
            }
            destination = Uri.fromFile(File(directory, subPath))
        }

        fun setAllowedNetworkTypes(flags: Int): Request = apply { allowedNetworkTypes = flags }

        fun setNotificationVisibility(visibility: Int): Request = apply {
            notificationVisibility = visibility
        }

        fun setTitle(value: CharSequence?): Request = apply { title = value }

        fun setDescription(value: CharSequence?): Request = apply { description = value }

        fun setMimeType(value: String?): Request = apply { mimeType = value }

        fun addRequestHeader(header: String, value: String?): Request = apply {
            require(!header.contains(':')) { "header may not contain ':'" }
            headers += header to (value ?: "")
        }

        internal fun sourceUri(): Uri = source
    }

    class Query {
        internal var ids: LongArray? = null
            private set
        internal var statusFlags: Int? = null
            private set

        fun setFilterById(vararg values: Long): Query = apply { ids = values }

        fun setFilterByStatus(flags: Int): Query = apply { statusFlags = flags }

        internal fun matches(id: Long, status: Int): Boolean {
            if (ids?.none { it == id } == true) return false
            val flags = statusFlags ?: return true
            return flags and status != 0
        }
    }

    fun enqueue(request: Request): Long = HostedDownloadBackend.enqueue(context, request)

    fun query(query: Query): Cursor = HostedDownloadBackend.query(context, query)

    @Throws(FileNotFoundException::class)
    fun openDownloadedFile(id: Long): ParcelFileDescriptor =
        HostedDownloadBackend.openDownloadedFile(context, id)

    fun getUriForDownloadedFile(id: Long): Uri? = HostedDownloadBackend.completedUri(context, id)

    fun getMimeTypeForDownloadedFile(id: Long): String? = HostedDownloadBackend.mimeType(context, id)

    fun remove(vararg ids: Long): Int = HostedDownloadBackend.remove(context, ids)

    companion object {
        const val ACTION_DOWNLOAD_COMPLETE: String = "android.intent.action.DOWNLOAD_COMPLETE"
        const val ACTION_NOTIFICATION_CLICKED: String =
            "android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED"
        const val ACTION_VIEW_DOWNLOADS: String = "android.intent.action.VIEW_DOWNLOADS"
        const val EXTRA_DOWNLOAD_ID: String = "extra_download_id"
        const val EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS: String =
            "extra_click_download_ids"

        const val COLUMN_ID: String = "_id"
        const val COLUMN_TITLE: String = "title"
        const val COLUMN_DESCRIPTION: String = "description"
        const val COLUMN_URI: String = "uri"
        const val COLUMN_MEDIA_TYPE: String = "media_type"
        const val COLUMN_TOTAL_SIZE_BYTES: String = "total_size"
        const val COLUMN_LOCAL_URI: String = "local_uri"
        const val COLUMN_LOCAL_FILENAME: String = "local_filename"
        const val COLUMN_STATUS: String = "status"
        const val COLUMN_REASON: String = "reason"
        const val COLUMN_BYTES_DOWNLOADED_SO_FAR: String = "bytes_so_far"
        const val COLUMN_LAST_MODIFIED_TIMESTAMP: String = "last_modified_timestamp"

        const val STATUS_PENDING: Int = 1
        const val STATUS_RUNNING: Int = 1 shl 1
        const val STATUS_PAUSED: Int = 1 shl 2
        const val STATUS_SUCCESSFUL: Int = 1 shl 3
        const val STATUS_FAILED: Int = 1 shl 4

        const val ERROR_UNKNOWN: Int = 1000
        const val ERROR_FILE_ERROR: Int = 1001
        const val ERROR_UNHANDLED_HTTP_CODE: Int = 1002
        const val ERROR_HTTP_DATA_ERROR: Int = 1004
        const val ERROR_TOO_MANY_REDIRECTS: Int = 1005
        const val ERROR_INSUFFICIENT_SPACE: Int = 1006
        const val ERROR_DEVICE_NOT_FOUND: Int = 1007
        const val ERROR_CANNOT_RESUME: Int = 1008
        const val ERROR_FILE_ALREADY_EXISTS: Int = 1009
    }
}

private object HostedDownloadBackend {
    private const val TAG = "HostedDownloadManager"
    private const val STATE_VERSION = 1
    private const val STATE_DIRECTORY = "hosted-download-manager"
    private const val STATE_FILE = "state.bin"
    private val stores = ConcurrentHashMap<String, Store>()

    private class Store(val directory: File) {
        val lock = Any()
        val records = linkedMapOf<Long, Record>()
        var loaded = false
        var nextId = 1L
    }

    internal class Record(
        val id: Long,
        val source: Uri,
        val destination: File,
        val title: String?,
        val description: String?,
        val mimeType: String?,
        val headers: List<Pair<String, String>>,
    ) {
        @Volatile var status: Int = DownloadManager.STATUS_PENDING
        @Volatile var reason: Int = 0
        @Volatile var totalBytes: Long = -1L
        @Volatile var downloadedBytes: Long = 0L
        @Volatile var lastModified: Long = System.currentTimeMillis()
        @Volatile var cancelled: Boolean = false
        @Volatile var published: Boolean = false
        @Volatile var workerStarted: Boolean = false
    }

    fun enqueue(context: Context, request: DownloadManager.Request): Long {
        val store = storeFor(context)
        val id: Long
        synchronized(store.lock) {
            id = store.nextId++
        }
        val destination = destinationFor(context, request, id)
        val record = Record(
            id = id,
            source = request.sourceUri(),
            destination = destination,
            title = request.title?.toString(),
            description = request.description?.toString(),
            mimeType = request.mimeType,
            headers = request.headers.toList(),
        )
        synchronized(store.lock) {
            store.records[id] = record
            persistLocked(store)
        }
        startDownload(context.applicationContext ?: context, store, record)
        return id
    }

    fun query(context: Context, query: DownloadManager.Query): Cursor {
        val store = storeFor(context)
        val columns = arrayOf(
            DownloadManager.COLUMN_ID,
            DownloadManager.COLUMN_URI,
            DownloadManager.COLUMN_STATUS,
            DownloadManager.COLUMN_LOCAL_URI,
            DownloadManager.COLUMN_LOCAL_FILENAME,
            DownloadManager.COLUMN_TITLE,
            DownloadManager.COLUMN_DESCRIPTION,
            DownloadManager.COLUMN_MEDIA_TYPE,
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
            DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP,
            DownloadManager.COLUMN_REASON,
        )
        val cursor = MatrixCursor(columns)
        val snapshot = synchronized(store.lock) { store.records.values.toList() }
        snapshot
            .asSequence()
            .filter { query.matches(it.id, it.status) }
            .sortedByDescending { it.lastModified }
            .forEach { record ->
                val localUri = Uri.fromFile(record.destination).toString()
                cursor.addRow(arrayOf(
                    record.id,
                    record.source.toString(),
                    record.status,
                    localUri,
                    record.destination.absolutePath,
                    record.title,
                    record.description,
                    record.mimeType,
                    record.totalBytes,
                    record.downloadedBytes,
                    record.lastModified,
                    record.reason,
                ))
            }
        return cursor
    }

    @Throws(FileNotFoundException::class)
    fun openDownloadedFile(context: Context, id: Long): ParcelFileDescriptor {
        val store = storeFor(context)
        val record = synchronized(store.lock) { store.records[id] }
            ?: throw FileNotFoundException("Unknown download id $id")
        if (record.status != DownloadManager.STATUS_SUCCESSFUL || !record.destination.isFile) {
            throw FileNotFoundException("Download $id has not completed")
        }
        return try {
            ParcelFileDescriptor.open(record.destination, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: IOException) {
            throw FileNotFoundException(record.destination.absolutePath).also { it.initCause(error) }
        }
    }

    fun completedUri(context: Context, id: Long): Uri? = storeFor(context).let { store ->
        synchronized(store.lock) { store.records[id] }
    }
        ?.takeIf { it.status == DownloadManager.STATUS_SUCCESSFUL && it.destination.isFile }
        ?.let { Uri.fromFile(it.destination) }

    fun mimeType(context: Context, id: Long): String? = storeFor(context).let { store ->
        synchronized(store.lock) { store.records[id] }
    }
        ?.takeIf { it.status == DownloadManager.STATUS_SUCCESSFUL }
        ?.mimeType

    fun remove(context: Context, ids: LongArray): Int {
        require(ids.isNotEmpty()) { "input param 'ids' can't be empty" }
        val store = storeFor(context)
        var removed = 0
        synchronized(store.lock) {
            ids.forEach { id ->
                val record = store.records.remove(id) ?: return@forEach
                record.cancelled = true
                if (record.published) record.destination.delete()
                File(record.destination.absolutePath + ".part").delete()
                removed++
            }
            if (removed > 0) persistLocked(store)
        }
        return removed
    }

    private fun storeFor(context: Context): Store {
        val appContext = context.applicationContext ?: context
        val directory = File(appContext.filesDir, STATE_DIRECTORY).absoluteFile
        val store = stores.computeIfAbsent(directory.path) { Store(directory) }
        var resumable = emptyList<Record>()
        synchronized(store.lock) {
            if (!store.loaded) {
                loadLocked(store, appContext)
                store.loaded = true
                var changed = false
                store.records.values.forEach { record ->
                    when (record.status) {
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING,
                        -> {
                            record.status = DownloadManager.STATUS_PENDING
                            record.reason = 0
                            record.totalBytes = -1L
                            record.downloadedBytes = 0L
                            File(record.destination.absolutePath + ".part").delete()
                            changed = true
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> if (!record.destination.isFile) {
                            record.status = DownloadManager.STATUS_FAILED
                            record.reason = DownloadManager.ERROR_FILE_ERROR
                            record.published = false
                            changed = true
                        }
                    }
                }
                if (changed) persistLocked(store)
                resumable = store.records.values.filter {
                    it.status == DownloadManager.STATUS_PENDING
                }
            }
        }
        resumable.forEach { startDownload(appContext, store, it) }
        return store
    }

    private fun startDownload(context: Context, store: Store, record: Record) {
        synchronized(store.lock) {
            if (record.cancelled || record.workerStarted || store.records[record.id] !== record) {
                return
            }
            if (record.status != DownloadManager.STATUS_PENDING) return
            record.workerStarted = true
        }
        Thread({ download(context, store, record) }, "DownloadManager-${record.id}")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * Persists the provider-owned request table that AOSP DownloadManager normally keeps in
     * DownloadProvider. A backup rename gives the host the same old-or-new crash behavior as
     * android.util.AtomicFile without adding that class's unavailable framework dependencies.
     */
    private fun persistLocked(store: Store) {
        if (!store.directory.exists() && !store.directory.mkdirs()) {
            throw IOException("Unable to create ${store.directory.absolutePath}")
        }
        val state = File(store.directory, STATE_FILE)
        val backup = File(store.directory, "$STATE_FILE.bak")
        val temporary = File(store.directory, "$STATE_FILE.tmp")
        temporary.delete()
        val fileOutput = FileOutputStream(temporary, false)
        try {
            val output = DataOutputStream(BufferedOutputStream(fileOutput))
            output.writeInt(STATE_VERSION)
            output.writeLong(store.nextId)
            output.writeInt(store.records.size)
            store.records.values.forEach { record ->
                output.writeLong(record.id)
                output.writeString(record.source.toString())
                output.writeString(record.destination.absolutePath)
                output.writeNullableString(record.title)
                output.writeNullableString(record.description)
                output.writeNullableString(record.mimeType)
                output.writeInt(record.headers.size)
                record.headers.forEach { (name, value) ->
                    output.writeString(name)
                    output.writeString(value)
                }
                output.writeInt(record.status)
                output.writeInt(record.reason)
                output.writeLong(record.totalBytes)
                output.writeLong(record.downloadedBytes)
                output.writeLong(record.lastModified)
                output.writeBoolean(record.published)
            }
            output.flush()
            fileOutput.fd.sync()
        } finally {
            fileOutput.close()
        }

        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IOException("Unable to remove ${backup.absolutePath}")
        }
        if (state.exists() && !state.renameTo(backup)) {
            temporary.delete()
            throw IOException("Unable to back up ${state.absolutePath}")
        }
        if (!temporary.renameTo(state)) {
            if (backup.exists()) backup.renameTo(state)
            temporary.delete()
            throw IOException("Unable to publish ${state.absolutePath}")
        }
        backup.delete()
    }

    private fun loadLocked(store: Store, context: Context) {
        val state = File(store.directory, STATE_FILE)
        val backup = File(store.directory, "$STATE_FILE.bak")
        val temporary = File(store.directory, "$STATE_FILE.tmp")
        if (backup.exists()) {
            state.delete()
            if (!backup.renameTo(state)) {
                Log.w(TAG, "Unable to restore ${backup.absolutePath}")
            }
        }
        temporary.delete()
        if (!state.isFile) return

        try {
            DataInputStream(BufferedInputStream(FileInputStream(state))).use { input ->
                val version = input.readInt()
                if (version != STATE_VERSION) throw IOException("Unsupported state version $version")
                val nextId = input.readLong()
                val count = input.readInt()
                if (nextId < 1L || count !in 0..100_000) {
                    throw IOException("Invalid DownloadManager state header")
                }
                val loaded = linkedMapOf<Long, Record>()
                repeat(count) {
                    val id = input.readLong()
                    val record = Record(
                        id = id,
                        source = Uri.parse(input.readString()),
                        // DownloadProvider's database is protected by its process UID. This
                        // hosted table lives in APK-writable storage, so persisted paths must pass
                        // the same confinement check as newly enqueued requests before any record
                        // can be queried, removed, or resumed.
                        destination = requireAppOwnedDestination(context, File(input.readString())),
                        title = input.readNullableString(),
                        description = input.readNullableString(),
                        mimeType = input.readNullableString(),
                        headers = buildList {
                            val headerCount = input.readInt()
                            if (headerCount !in 0..10_000) {
                                throw IOException("Invalid request-header count $headerCount")
                            }
                            repeat(headerCount) {
                                add(input.readString() to input.readString())
                            }
                        },
                    )
                    record.status = input.readInt()
                    record.reason = input.readInt()
                    record.totalBytes = input.readLong()
                    record.downloadedBytes = input.readLong()
                    record.lastModified = input.readLong()
                    record.published = input.readBoolean()
                    loaded[id] = record
                }
                store.records.clear()
                store.records.putAll(loaded)
                store.nextId = maxOf(nextId, (loaded.keys.maxOrNull() ?: 0L) + 1L)
            }
        } catch (error: Exception) {
            store.records.clear()
            store.nextId = 1L
            Log.w(TAG, "Unable to restore DownloadManager state: ${error.message}")
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeString(value)
        }
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size !in 0..16_777_216) throw IOException("Invalid string size $size")
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(): String? {
        val size = readInt()
        if (size == -1) return null
        if (size !in 0..16_777_216) throw IOException("Invalid string size $size")
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun destinationFor(
        context: Context,
        request: DownloadManager.Request,
        id: Long,
    ): File {
        val destination = request.destination
        if (destination != null) {
            require(destination.scheme == "file") {
                "Only file destinations are supported by the hosted DownloadManager: $destination"
            }
            val path = destination.path
                ?: throw IllegalArgumentException("Destination has no file path: $destination")
            return requireAppOwnedDestination(context, File(path))
        }
        val directory = File(context.filesDir, "downloads")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create directory: ${directory.absolutePath}")
        }
        return File(directory, id.toString())
    }

    /**
     * DownloadProvider normally relies on Android's UID sandbox to constrain file destinations.
     * Hosted APKs share the host process UID, so keep the provider adaptation inside directories
     * owned by the application context instead of exposing arbitrary host paths.
     */
    private fun requireAppOwnedDestination(context: Context, destination: File): File {
        val candidate = destination.canonicalFile
        val roots = buildList {
            add(context.filesDir.canonicalFile)
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .mapTo(this) { it.canonicalFile }
        }.distinctBy { it.path }
        if (roots.none { root -> candidate.path.startsWith(root.path + File.separator) }) {
            throw SecurityException("Download destination is outside application-owned storage: $destination")
        }
        return candidate
    }

    private fun download(context: Context, store: Store, record: Record) {
        val partial = File(record.destination.absolutePath + ".part")
        var connection: HttpURLConnection? = null
        try {
            synchronized(store.lock) {
                if (record.cancelled || store.records[record.id] !== record) return
                if (record.destination.exists()) {
                    finishFailureLocked(
                        store,
                        record,
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS,
                    )
                } else {
                    record.status = DownloadManager.STATUS_RUNNING
                    record.lastModified = System.currentTimeMillis()
                    persistLocked(store)
                }
            }
            if (record.status == DownloadManager.STATUS_FAILED) {
                sendCompletionBroadcast(context, record.id)
                return
            }
            record.destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Unable to create directory: ${parent.absolutePath}")
                }
            }
            connection = URL(record.source.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            record.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                record.reason = responseCode
                throw IOException("HTTP $responseCode for ${record.source}")
            }
            record.totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (record.cancelled) throw IOException("Download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        record.downloadedBytes += count
                        record.lastModified = System.currentTimeMillis()
                    }
                }
            }
            var successful = false
            synchronized(store.lock) {
                if (record.cancelled || store.records[record.id] !== record) {
                    partial.delete()
                    return
                }
                if (record.destination.exists()) {
                    partial.delete()
                    finishFailureLocked(
                        store,
                        record,
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS,
                    )
                } else {
                    if (!partial.renameTo(record.destination)) {
                        throw IOException("Unable to publish ${record.destination.absolutePath}")
                    }
                    record.published = true
                    record.status = DownloadManager.STATUS_SUCCESSFUL
                    record.reason = 0
                    record.lastModified = System.currentTimeMillis()
                    persistLocked(store)
                    successful = true
                }
            }
            sendCompletionBroadcast(context, record.id)
            if (!successful) {
                Log.w(TAG, "download ${record.id} failed: destination already exists")
            }
        } catch (error: Exception) {
            partial.delete()
            var notify = false
            synchronized(store.lock) {
                if (!record.cancelled && store.records[record.id] === record) {
                    finishFailureLocked(
                        store,
                        record,
                        record.reason.takeIf { it != 0 } ?: DownloadManager.ERROR_UNKNOWN,
                    )
                    notify = true
                }
            }
            if (notify) {
                Log.w(TAG, "download ${record.id} failed: ${error.javaClass.name}: ${error.message}")
                sendCompletionBroadcast(context, record.id)
            }
        } finally {
            connection?.disconnect()
            record.workerStarted = false
        }
    }

    private fun finishFailureLocked(store: Store, record: Record, reason: Int) {
        record.status = DownloadManager.STATUS_FAILED
        record.reason = reason
        record.lastModified = System.currentTimeMillis()
        persistLocked(store)
    }

    private fun sendCompletionBroadcast(context: Context, id: Long) {
        context.sendBroadcast(
            Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(context.packageName)
                putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id)
            },
        )
    }
}
