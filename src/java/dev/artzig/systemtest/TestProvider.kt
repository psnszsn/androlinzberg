package dev.artzig.systemtest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/** system-test provider hosted by package `dev.artzig.test.b`. */
internal open class TestProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ApplicationBindingEvents.events += "provider:" + javaClass.simpleName
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("_id", "name", "value"))
        cursor.addRow(arrayOf<Any>(1L, "alpha", 10L))
        cursor.addRow(arrayOf<Any>(2L, "beta", 20L))
        return cursor
    }

    /** `.../slow` waits for the caller's CancellationSignal (cross-process cancellation test). */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: android.os.CancellationSignal?,
    ): Cursor? {
        if (uri.lastPathSegment == "slow") {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                cancellationSignal?.throwIfCanceled()
                Thread.sleep(20)
            }
            throw IllegalStateException("slow query was never cancelled")
        }
        return super.query(uri, projection, queryArgs, cancellationSignal)
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.artzig.row"

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        Uri.parse(uri.toString() + "/" + (values?.getAsString("name") ?: "null"))

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 1

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 2

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method in setOf("grant-uri", "revoke-uri", "clipboard-uri", "clear-clipboard")) {
            val request = checkNotNull(extras)
            val uri = when (val value = request.get("uri")) {
                is Uri -> value
                is String -> Uri.parse(value)
                else -> error("missing fixture URI")
            }
            val target = checkNotNull(arg ?: request.getString("package"))
            val mode = request.getInt("mode")
            val identity = android.os.Binder.clearCallingIdentity()
            try {
                val owner = checkNotNull(context)
                when (method) {
                    "grant-uri" -> owner.grantUriPermission(target, uri, mode)
                    "revoke-uri" -> owner.revokeUriPermission(target, uri, mode)
                    "clipboard-uri" -> owner.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newRawUri("grant fixture", uri))
                    "clear-clipboard" -> owner.getSystemService(android.content.ClipboardManager::class.java)
                        .clearPrimaryClip()
                }
            } finally { android.os.Binder.restoreCallingIdentity(identity) }
        }
        return Bundle().apply { putString("echo", method + ":" + arg) }
    }
}

/** The non-exported fixture provider: same behavior as [TestProvider]; a
 * manifest declares each component under a distinct class name. */
internal class SecretProvider : TestProvider()

/** Binding must omit disabled components and providers assigned to another process. */
internal class DisabledBindingProvider : TestProvider()
internal class SecondaryBindingProvider : TestProvider()
