package dev.artzig

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Downloads
import android.provider.Settings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/** Public DownloadManager operations across the original provider's process boundary. */
internal object StockDownloadManagerSmoke {
    private fun testCloseAfterRename(context: Context) {
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "download-close-").toFile()
        val original = File(directory, "writing")
        val finished = File(directory, "finished")
        val callbackThread = android.os.HandlerThread("download-close-smoke").apply { start() }
        val handler = android.os.Handler(callbackThread.looper)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sizes = java.util.concurrent.LinkedBlockingQueue<Long>()
        handler.post { entered.countDown(); release.await() }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val pfd = dev.artzig.system.HostedDownloadStorage.openFile(original,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
                handler, java.util.function.LongConsumer { sizes.add(it) })
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(byteArrayOf(1, 2, 3, 4)) }
            check(original.renameTo(finished))
            original.writeText("replacement at the original path")
            release.countDown()
            check(sizes.poll(5, java.util.concurrent.TimeUnit.SECONDS) == 4L)
            check(finished.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        } finally {
            release.countDown()
            callbackThread.quitSafely(); callbackThread.join(5_000)
            directory.deleteRecursively()
        }
    }

    fun run(context: Context) {
        testCloseAfterRename(context)
        val manager = checkNotNull(context.getSystemService(DownloadManager::class.java))
        check(manager === context.getSystemService(Context.DOWNLOAD_SERVICE))
        val resolver = context.contentResolver
        val previousMode = Settings.Global.getString(resolver, Settings.Global.CAPTIVE_PORTAL_MODE)
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        // Validation has its own deterministic probe tests. IGNORE is the stock setting
        // for a local-only network; downloads here must not depend on public probe hosts.
        check(Settings.Global.putInt(resolver, Settings.Global.CAPTIVE_PORTAL_MODE,
            Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE))
        connectivity.reportNetworkConnectivity(null, true)
        val payload = "stock DownloadProvider transfer".toByteArray()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.LinkedBlockingQueue<String>()
        val responder = Thread({
            try {
                while (!server.isClosed) server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    val path = request.split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.add(path)
                    val body = if (path == "/missing") byteArrayOf() else payload
                    val status = if (path == "/missing") 404 else 200
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                    }
                }
            } catch (e: SocketException) { if (!server.isClosed) throw e }
        }, "stock-download-fixture").apply { isDaemon = true; start() }
        val ids = ArrayList<Long>()
        val destination = File(context.getExternalFilesDir(null), "stock-download.bin")
        fun request(path: String = "/payload") = DownloadManager.Request(
            Uri.parse("http://127.0.0.1:${server.localPort}$path"))
            .setTitle("Stock download smoke").setDescription("Original provider transfer")
        fun enqueue(request: DownloadManager.Request): Long = manager.enqueue(request).also {
            check(it > 0); ids.add(it)
        }
        fun status(id: Long): Pair<Int, Int> = manager.query(DownloadManager.Query().setFilterById(id)).use {
            check(it.moveToFirst())
            it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        }
        fun terminal(id: Long): Pair<Int, Int> {
            val deadline = SystemClock.uptimeMillis() + 45_000
            var result = status(id)
            while (result.first != DownloadManager.STATUS_SUCCESSFUL && result.first != DownloadManager.STATUS_FAILED
                && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(20); result = status(id)
            }
            return result
        }
        fun bytes(id: Long) = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { it.readBytes() }
        var failure: Throwable? = null
        try {
            val info = checkNotNull(context.packageManager.resolveContentProvider("downloads", 0))
            check(info.packageName == "com.android.providers.downloads")
            check(info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            val providerApp = context.packageManager.getApplicationInfo(info.packageName, 0)
            check(providerApp.dataDir == info.applicationInfo.dataDir)
            val providerData = File(providerApp.dataDir).canonicalFile
            check(providerData.path.endsWith("/service-app-data/user/0/${info.packageName}"))
            check(providerData != android.os.Environment.getDataUserCePackageDirectory(null, 0, info.packageName).canonicalFile)
            check(runCatching { DownloadManager.Request(Uri.parse("file:///invalid")) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { resolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null, null, null, null)?.close() }
                .exceptionOrNull() is SecurityException)
            check(runCatching { manager.enqueue(request().setDestinationUri(Uri.fromFile(File(context.filesDir, "private.bin")))) }
                .exceptionOrNull() is SecurityException)
            check(runCatching { manager.enqueue(request().setDestinationInExternalFilesDir(context, null, "../../escape.bin")) }
                .exceptionOrNull() is IllegalArgumentException)

            val cached = enqueue(request())
            check(terminal(cached).first == DownloadManager.STATUS_SUCCESSFUL) { "cache download: ${status(cached)}" }
            check(bytes(cached).contentEquals(payload))
            check(manager.getUriForDownloadedFile(cached).toString() == "content://downloads/all_downloads/$cached")
            check(manager.getMimeTypeForDownloadedFile(cached) == "application/octet-stream")
            manager.query(DownloadManager.Query().setFilterById(cached)).use {
                check(it.moveToFirst())
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val transferred = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                check(total == payload.size.toLong()) { "Download sizes: total=$total transferred=$transferred expected=${payload.size}" }
                check(runCatching { it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME)) }
                    .exceptionOrNull() is SecurityException)
            }
            DownloadManager(context).query(DownloadManager.Query().setFilterById(cached)).use {
                check(it.moveToFirst())
                check(it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
            }
            val provider = checkNotNull(context.packageManager.resolveContentProvider("downloads", 0))
            check(provider.applicationInfo.uid > 0 && provider.applicationInfo.uid != context.applicationInfo.uid && provider.applicationInfo.uid != android.os.Process.SYSTEM_UID)

            destination.delete()
            val external = enqueue(request().setDestinationUri(Uri.fromFile(destination)))
            check(terminal(external).first == DownloadManager.STATUS_SUCCESSFUL) { "external download: ${status(external)}" }
            check(destination.readBytes().contentEquals(payload) && bytes(external).contentEquals(payload))
            check(manager.remove(external) == 1); ids.remove(external)
            check(!destination.exists())

            destination.writeText("existing destination")
            val collision = enqueue(request().setDestinationUri(Uri.fromFile(destination)))
            // Pinned Helpers.generateSaveFile reserves an available sibling name.
            check(terminal(collision).first == DownloadManager.STATUS_SUCCESSFUL)
            check(bytes(collision).contentEquals(payload))
            val sibling = manager.query(DownloadManager.Query().setFilterById(collision)).use {
                check(it.moveToFirst())
                File(checkNotNull(Uri.parse(it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))).path))
            }
            check(sibling != destination && sibling.parentFile == destination.parentFile)
            check(sibling.readBytes().contentEquals(payload))
            check(destination.readText() == "existing destination")
            check(manager.remove(collision) == 1); ids.remove(collision)
            check(!sibling.exists() && destination.readText() == "existing destination")

            val missing = enqueue(request("/missing"))
            check(terminal(missing) == (DownloadManager.STATUS_FAILED to 404))
            check(manager.getUriForDownloadedFile(missing) == null)
            check(requests.count { it == "/payload" } == 3 && requests.count { it == "/missing" } == 1)
            check(manager.remove(cached) == 1); ids.remove(cached)
            manager.query(DownloadManager.Query().setFilterById(cached)).use { check(!it.moveToFirst()) }
            // DownloadProvider.delete revokes the all_downloads grant. The public
            // manager opens that URI, so permission enforcement precedes row lookup.
            check(runCatching { manager.openDownloadedFile(cached) }.exceptionOrNull() is SecurityException)
            val ownUri = android.content.ContentUris.withAppendedId(Downloads.Impl.CONTENT_URI, cached)
            check(runCatching { resolver.openFileDescriptor(ownUri, "r") }.exceptionOrNull() is java.io.FileNotFoundException)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            val cleanup = runCatching { ids.forEach { manager.remove(it) } }.exceptionOrNull()
            destination.delete()
            server.close(); responder.join(1_000)
            Settings.Global.putString(resolver, Settings.Global.CAPTIVE_PORTAL_MODE, previousMode)
            connectivity.reportNetworkConnectivity(null, false)
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
        System.err.println("stock-download-manager: PASS provider identity, transfer, URI, query, file ownership, errors and deletion")
    }
}
