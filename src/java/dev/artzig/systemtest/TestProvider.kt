package dev.artzig.systemtest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/** system-test provider hosted by package `dev.artzig.test.b`. */
internal open class TestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

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

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply { putString("echo", method + ":" + arg) }
}

/** The non-exported fixture provider: same behavior as [TestProvider]; a
 * manifest declares each component under a distinct class name. */
internal class SecretProvider : TestProvider()
