package dev.artzig

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaFile
import android.mtp.MtpConstants
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MetadataReader
import com.android.internal.content.FileSystemProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/** Tests the original filesystem provider algorithms with a private temporary document root. */
internal object StockDownloadFileSupportSmoke {
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)

    private class Documents(private val root: File) : FileSystemProvider() {
        override fun onCreate(): Boolean { super.onCreate(columns); return true }
        override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: arrayOf())
        override fun getFileForDocId(id: String, visible: Boolean): File? {
            // These documents are private; there is no separate MediaStore-visible path.
            if (visible) return null
            val file = if (id == "root") root else File(root, id.removePrefix("root/")).canonicalFile
            if (file != root && !file.path.startsWith(root.path + File.separator)) throw FileNotFoundException(id)
            return file
        }
        override fun getDocIdForFile(file: File): String =
            if (file.canonicalFile == root) "root" else "root/" + file.canonicalFile.relativeTo(root).path
        override fun buildNotificationUri(id: String): Uri = Uri.parse("content://stock-file-fixture/$id")
        fun search(args: Bundle, excluded: Set<String> = emptySet()): Cursor =
            querySearchDocuments(root, columns, excluded, args)
        fun documentPath(id: String): List<String> = findDocumentPath(root, getFileForDocId(id, false))
    }

    fun run() {
        check(MediaFile.getMimeType("file.jpg", MtpConstants.FORMAT_MP3) == "image/jpeg")
        check(MediaFile.getMimeType("file.unknown", MtpConstants.FORMAT_MP3) == "audio/mpeg")
        check(MediaFile.getMimeTypeForFile(null) == "application/octet-stream")
        check(MediaFile.getFormatCode("file.jpg", "audio/mpeg") == MtpConstants.FORMAT_MP3)
        check(MediaFile.getFormatCode("file.jpg", null) == MtpConstants.FORMAT_EXIF_JPEG)
        check(MediaFile.getFormatCodeForMimeType("audio/x-fixture") == MtpConstants.FORMAT_UNDEFINED_AUDIO)
        check(MediaFile.getFormatCodeForMimeType("video/x-fixture") == MtpConstants.FORMAT_UNDEFINED_VIDEO)
        check(MediaFile.isDocumentMimeType("application/pdf"))
        check(!MediaFile.isDocumentMimeType("image/jpeg"))
        check(MetadataReader.isSupportedMimeType("image/jpeg"))
        check(MetadataReader.isSupportedMimeType("image/jpg"))
        check(!MetadataReader.isSupportedMimeType("image/png"))
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val metadata = Bundle().apply { putString("caller", "preserved") }
        MetadataReader.getMetadata(metadata, input, "text/plain", null)
        check(metadata.getString("caller") == "preserved")
        check(metadata.getStringArray(DocumentsContract.METADATA_TYPES)!!.isEmpty())
        check(input.available() == 3)

        val root = Files.createTempDirectory("stock-file-support-").toFile().canonicalFile
        try {
            val nested = File(root, "nested").apply { check(mkdir()) }
            val first = File(root, "report.txt").apply { writeText("hello") }
            File(nested, "report-second.txt").writeText("world!")
            File(root, "photo.jpg").writeBytes(byteArrayOf(1, 2))
            val provider = Documents(root).apply { check(onCreate()) }
            check(provider.isChildDocument("root", "root/nested/report-second.txt"))
            check(!provider.isChildDocument("root/nested", "root/report.txt"))
            check(provider.documentPath("root/nested/report-second.txt") ==
                listOf("root", "root/nested", "root/nested/report-second.txt"))
            check(provider.getDocumentType("root/nested") == Document.MIME_TYPE_DIR)
            check(provider.getDocumentType("root/report.txt") == "text/plain")
            provider.queryDocument("root/report.txt", columns).use { cursor ->
                check(cursor.count == 1 && cursor.moveToFirst())
                check(cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)) == "root/report.txt")
                check(cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)) == 5L)
                val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
                check(flags and Document.FLAG_SUPPORTS_WRITE != 0)
                check(flags and Document.FLAG_SUPPORTS_DELETE != 0)
                check(flags and Document.FLAG_SUPPORTS_METADATA == 0)
            }
            val args = Bundle().apply { putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, "report") }
            provider.search(args).use { cursor ->
                check(cursor.count == 2)
                check(cursor.extras.getStringArray(ContentResolver.EXTRA_HONORED_ARGS)!!
                    .contains(DocumentsContract.QUERY_ARG_DISPLAY_NAME))
            }
            provider.search(args, setOf(first.path)).use { check(it.count == 1) }
            ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument("root/report.txt", "r", null)).use {
                check(it.readBytes().toString(Charsets.UTF_8) == "hello")
            }
            ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument("root/report.txt", "wa", null)).use {
                it.write('!'.code)
            }
            check(first.readText() == "hello!")
            val tree = checkNotNull(provider.getDocumentMetadata("root"))
            check(tree.getLong(DocumentsContract.METADATA_TREE_COUNT) == 3L)
            check(tree.getLong(DocumentsContract.METADATA_TREE_SIZE) == 14L)
            check(provider.getDocumentMetadata("root/report.txt") == null)
            val missing = runCatching { provider.getDocumentMetadata("root/missing") }.exceptionOrNull()
            check(missing is FileNotFoundException)
        } finally {
            check(root.deleteRecursively())
        }
        System.err.println("stock-download-file-support: PASS MIME/format precedence, metadata, document query/search/path and descriptor I/O")
    }
}
