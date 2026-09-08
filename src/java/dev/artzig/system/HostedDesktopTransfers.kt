package dev.artzig.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import com.android.server.LocalServices
import com.android.server.uri.UriGrantsManagerInternal
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded asynchronous provider work. The source UID comes from stock
 * ClipboardService after checkDataOwner, never from a ClipData extra or client.
 */
internal class HostedDesktopTransfers private constructor(private val context: Context, private val handler: Handler) {
    companion object {
        private var shared: HostedDesktopTransfers? = null
        @Synchronized fun get(context: Context, handler: Handler): HostedDesktopTransfers {
            val existing = shared
            if (existing != null) {
                check(existing.handler.looper === handler.looper)
                return existing
            }
            return HostedDesktopTransfers(context, handler).also { shared = it }
        }
    }
    data class Staged(val token: String, val clip: ClipData, val uid: Int, val native: ByteArray)
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), { work -> Thread(work, "DesktopTransfer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val authority = "dev.artzig.desktop.transfers"
    private val packageName = "dev.artzig.desktop"
    private val base = Uri.parse("content://$authority")
    private val grants get() = checkNotNull(LocalServices.getService(UriGrantsManagerInternal::class.java))

    fun stage(clip: ClipData?, sourceUid: Int, native: String?, done: (Result<Staged>) -> Unit) {
        val token = UUID.randomUUID().toString()
        val delivered = AtomicBoolean()
        val owner = java.util.concurrent.atomic.AtomicReference<android.os.IBinder?>()
        fun revoke() { owner.get()?.let { grants.revokeUriPermissionFromOwner(it, null, Intent.FLAG_GRANT_READ_URI_PERMISSION, 0) } }
        val timeout = Runnable {
            if (delivered.compareAndSet(false, true)) {
                // Revocation does not depend on an unresponsive provider returning
                // from a Binder open/query call. The worker also revokes in finally.
                revoke()
                done(Result.failure(IllegalStateException("Desktop file transfer timed out")))
            }
        }
        handler.postDelayed(timeout, 6000)
        try {
            executor.execute {
                if (delivered.get()) return@execute
                val result = runCatching {
                    val info = requireNotNull(context.packageManager.resolveContentProvider(authority, 0)) { "Desktop provider is unavailable" }
                    require(info.packageName == packageName && info.applicationInfo.uid >= Process.FIRST_APPLICATION_UID) { "Invalid desktop provider identity" }
                    owner.set(grants.newUriPermissionOwner("desktop staging $token"))
                    try {
                        check(!delivered.get()) { "Desktop transfer expired before admission" }
                        val extras = Bundle()
                        if (native != null) extras.putString("uris", native) else {
                            val data = requireNotNull(clip)
                            val uris = ArrayList<String>()
                            for (index in 0 until data.itemCount) {
                                val uri = requireNotNull(data.getItemAt(index).uri)
                                require(uri.scheme == "content") { "Android file export requires content URIs" }
                                android.app.UriGrantsManager.getService().grantUriPermissionFromOwner(owner.get(), sourceUid, packageName,
                                    ContentProvider.getUriWithoutUserId(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)), 0)
                                check(!delivered.get()) { "Desktop transfer expired during grant validation" }
                                uris.add(ContentProvider.maybeAddUserId(uri, UserHandle.getUserId(sourceUid)).toString())
                            }
                            extras.putStringArrayList("uris", uris)
                            extras.putString("type", data.description.getMimeType(0))
                        }
                        val response = requireNotNull(context.contentResolver.call(base, if (native != null) "import" else "export", token, extras))
                        require(response.getInt("uid") == info.applicationInfo.uid)
                        val uris = requireNotNull(response.getStringArrayList("uris"))
                        require(uris.size in 1..16)
                        val types = requireNotNull(response.getStringArrayList("types"))
                        require(types.size == uris.size && types.all { it.length <= 128 })
                        val description = ClipDescription("Desktop files", types.distinct().toTypedArray())
                        val items = uris.mapIndexed { index, value ->
                            val uri = Uri.parse(value)
                            require(uri == base.buildUpon().appendPath(token).appendPath(index.toString()).build())
                            ClipData.Item(uri)
                        }
                        val staged = ClipData(description, items.first())
                        items.drop(1).forEach(staged::addItem)
                        val bytes = requireNotNull(response.getString("native")).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 256 * 1024)
                        Staged(token, staged, info.applicationInfo.uid, bytes)
                    } finally {
                        revoke()
                    }
                }
                handler.post {
                    handler.removeCallbacks(timeout)
                    if (delivered.compareAndSet(false, true)) {
                        if (result.isFailure) release(token, discard = true)
                        done(result)
                    } else release(token, discard = true)
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            handler.removeCallbacks(timeout)
            if (delivered.compareAndSet(false, true)) done(Result.failure(IllegalStateException("Desktop staging worker budget exhausted", failure)))
        }
    }
    fun release(token: String, discard: Boolean = false) {
        try {
            executor.execute {
                try { context.contentResolver.call(base, if (discard) "discard" else "release", token, null) }
                catch (failure: Exception) { android.util.Log.w("WaylandClipboard", "Desktop lease retirement failed", failure) }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            HostedClipboardMonitor.issue("Desktop lease retirement worker budget exhausted")
        }
    }
}
