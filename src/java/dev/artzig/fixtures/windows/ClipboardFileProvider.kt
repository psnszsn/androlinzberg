package dev.artzig.fixtures.windows

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** An ordinary non-exported provider. The desktop bridge must receive an
 * explicit URI grant before it can read this fixture's file. */
class ClipboardFileProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): java.io.File {
        require(uri == URI)
        return java.io.File(requireNotNull(context).filesDir, "Android café.txt").also {
            if (!it.exists()) it.writeText("Android file 😀 café")
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        android.os.SystemClock.sleep(nextOpenDelayMillis.getAndSet(0).toLong())
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }.toTypedArray<Any>()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
    companion object {
        val URI: Uri = Uri.parse("content://dev.artzig.fixtures.windows.clipboard/Android%20caf%C3%A9.txt")
        val nextOpenDelayMillis = java.util.concurrent.atomic.AtomicInteger()
    }
}
