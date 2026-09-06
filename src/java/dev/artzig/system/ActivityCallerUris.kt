package dev.artzig.system

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.UserHandle
import android.util.Slog
import com.android.server.uri.GrantUri
import com.android.server.uri.UriGrantsManagerInternal

/**
 * Follows AOSP ActivityCallerState.computeCallerInfo/checkContentUriPermission.
 * The stock class owns an ActivityTaskManagerService reference, which this hosted
 * process replaces. This seam retains its delivery-time URI snapshot and errors;
 * the stock UriGrantsManagerInternal remains the authority for access checks.
 */
internal class ActivityCallerUris private constructor(
    private val readable: Set<GrantUri>,
    private val writable: Set<GrantUri>,
    private val inaccessible: Set<GrantUri>,
) {
    fun check(uri: GrantUri, mode: Int): Boolean {
        require(Intent.isAccessUriMode(mode)) { "Mode flags are not access URI mode flags: $mode" }
        if (uri in inaccessible) return false
        val read = uri in readable
        val write = uri in writable
        require(read || write) {
            "The supplied URI wasn't passed at launch in #getData, #EXTRA_STREAM, nor #getClipData: " +
                uri.uri.toSafeString()
        }
        return (mode and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 || read) &&
            (mode and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 || write)
    }

    companion object {
        fun capture(grants: UriGrantsManagerInternal, intent: Intent?, uid: Int): ActivityCallerUris {
            val readable = HashSet<GrantUri>()
            val writable = HashSet<GrantUri>()
            val inaccessible = HashSet<GrantUri>()
            val uris = LinkedHashSet<Uri>()
            collect(intent, uris)
            for (uri in uris) {
                fun grant(mode: Int) = GrantUri(
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(uid)),
                    ContentProvider.getUriWithoutUserId(uri), mode)
                val read = grant(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val write = grant(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val hasRead = grants.checkUriPermission(read, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION, true)
                val hasWrite = grants.checkUriPermission(write, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true)
                if (hasRead) readable += read
                if (hasWrite) writable += write
                if (!hasRead && !hasWrite) inaccessible += grant(0)
            }
            return ActivityCallerUris(readable, writable, inaccessible)
        }

        private fun collect(intent: Intent?, uris: MutableSet<Uri>) {
            if (intent == null) return
            fun add(uri: Uri?) {
                if (uri?.scheme == ContentResolver.SCHEME_CONTENT) uris += uri
            }
            add(intent.data)
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                val streams = unparcel { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }
                if (streams == null) add(unparcel { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) })
                else streams.asReversed().forEach(::add)
            }
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    add(item.uri)
                    collect(item.intent, uris)
                }
            }
        }

        private fun <T> unparcel(read: () -> T): T? = try {
            read()
        } catch (failure: BadParcelableException) {
            Slog.w("ActivityTaskManager", "Failed to unparcel EXTRA_STREAM, returning null: $failure")
            null
        }
    }
}
