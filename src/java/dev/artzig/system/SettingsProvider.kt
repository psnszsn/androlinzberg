package dev.artzig.system

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * The session's settings provider, authority `"settings"`, hosted in the
 * system process (source note: `com.android.providers.settings.SettingsProvider`
 * reduced to the system/secure/global name-value tables — no per-user split,
 * no generation trackers, no config namespace).
 *
 * The verbatim AOSP `Settings` client talks to it through
 * `IContentProvider.call` (`GET_*`/`PUT_*`/`DELETE_*`) with the `name=?`
 * query fallback. Replies never carry `CALL_METHOD_TRACK_GENERATION_KEY`, so
 * `NameValueCache` never caches and every read calls through. State is
 * write-through XML in the session directory (per-launch durability; the
 * session dir is swept with the session).
 */
class SettingsProvider : ContentProvider() {
    private val lock = Any()
    private val tables = linkedMapOf(
        "system" to LinkedHashMap<String, String>(),
        "secure" to LinkedHashMap<String, String>(),
        "global" to LinkedHashMap<String, String>(),
    )
    private val store: File? = System.getenv("AZK_BINDER_SESSION_DIR")?.let { File(it, "settings.xml") }

    override fun onCreate(): Boolean {
        load()
        return true
    }

    // ------------------------------------------------------------- call()

    override fun call(method: String, name: String?, extras: Bundle?): Bundle? {
        val (op, table) = method.split('_', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return super.call(method, name, extras)
        }
        val values = synchronized(lock) { tables[table] } ?: return super.call(method, name, extras)
        if (name == null && op != "GET") return Bundle()
        return when (op) {
            "GET" -> Bundle().apply {
                putString(Settings.NameValueTable.VALUE, synchronized(lock) { values[name] })
            }
            "PUT" -> {
                enforceWrite(table)
                val value = extras?.getString(Settings.NameValueTable.VALUE)
                synchronized(lock) {
                    if (value == null) values.remove(name) else values[name!!] = value
                    persistLocked()
                }
                notifyRow(table, name)
                Bundle()
            }
            "DELETE" -> {
                enforceWrite(table)
                val removed = synchronized(lock) {
                    val had = values.remove(name) != null
                    if (had) persistLocked()
                    had
                }
                if (removed) notifyRow(table, name)
                Bundle()
            }
            else -> super.call(method, name, extras)
        }
    }

    /** uid 1000/0 write freely; apps need the AOSP-named permission. */
    private fun enforceWrite(table: String) {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.ROOT_UID) return
        val permission =
            if (table == "system") "android.permission.WRITE_SETTINGS"
            else "android.permission.WRITE_SECURE_SETTINGS"
        if (android.app.HostedServiceClient.checkUidPermission(permission, uid) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("uid " + uid + " needs " + permission + " to write " + table + " settings")
        }
    }

    private fun notifyRow(table: String, name: String?) {
        val base = Uri.parse("content://settings/" + table)
        val uri = if (name != null) Uri.withAppendedPath(base, name) else base
        context?.contentResolver?.notifyChange(uri, null)
    }

    // ------------------------------------------------- query and row CRUD

    private fun tableFor(uri: Uri): Pair<String, MutableMap<String, String>>? {
        val table = uri.pathSegments.firstOrNull() ?: return null
        return synchronized(lock) { tables[table] }?.let { table to it }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val (_, values) = tableFor(uri) ?: return null
        val columns = projection ?: arrayOf(Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE)
        val cursor = MatrixCursor(columns)
        val rows: List<Pair<String, String?>> = synchronized(lock) {
            when {
                // The NameValueCache fallback path.
                selection == "name=?" && selectionArgs?.size == 1 ->
                    listOf(selectionArgs[0] to values[selectionArgs[0]])
                selection == null && uri.pathSegments.size == 2 ->
                    listOf(uri.pathSegments[1] to values[uri.pathSegments[1]])
                selection == null -> values.entries.map { it.key to it.value }
                else -> emptyList()
            }
        }
        for ((name, value) in rows) {
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        Settings.NameValueTable.NAME -> name
                        Settings.NameValueTable.VALUE -> value
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String =
        if (uri.pathSegments.size < 2) "vnd.android.cursor.dir/" + (uri.pathSegments.firstOrNull() ?: "settings")
        else "vnd.android.cursor.item/" + uri.pathSegments[0]

    override fun insert(uri: Uri, contentValues: ContentValues?): Uri? {
        val (table, values) = tableFor(uri) ?: return null
        enforceWrite(table)
        val name = contentValues?.getAsString(Settings.NameValueTable.NAME) ?: return null
        val value = contentValues.getAsString(Settings.NameValueTable.VALUE)
        synchronized(lock) {
            if (value == null) values.remove(name) else values[name] = value
            persistLocked()
        }
        notifyRow(table, name)
        return Uri.withAppendedPath(uri, name)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val (table, values) = tableFor(uri) ?: return 0
        enforceWrite(table)
        val name = when {
            selection == "name=?" && selectionArgs?.size == 1 -> selectionArgs[0]
            uri.pathSegments.size == 2 -> uri.pathSegments[1]
            else -> return 0
        }
        val removed = synchronized(lock) {
            val had = values.remove(name) != null
            if (had) persistLocked()
            had
        }
        if (removed) notifyRow(table, name)
        return if (removed) 1 else 0
    }

    override fun update(
        uri: Uri,
        contentValues: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = if (insert(uri, contentValues) != null) 1 else 0

    // -------------------------------------------------------- persistence

    private fun persistLocked() {
        val file = store ?: return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { out ->
                val serializer = android.util.Xml.newSerializer()
                serializer.setOutput(out, "utf-8")
                serializer.startDocument(null, true)
                serializer.startTag(null, "settings")
                for ((table, values) in tables) {
                    for ((name, value) in values) {
                        serializer.startTag(null, "setting")
                        serializer.attribute(null, "table", table)
                        serializer.attribute(null, "name", name)
                        serializer.attribute(null, "value", value)
                        serializer.endTag(null, "setting")
                    }
                }
                serializer.endTag(null, "settings")
                serializer.endDocument()
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "cannot persist settings to " + file)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot persist settings", t)
        }
    }

    private fun load() {
        val file = store ?: return
        if (!file.isFile) return
        try {
            file.inputStream().use { input ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(input, "utf-8")
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                    if (parser.name != "setting") continue
                    val table = parser.getAttributeValue(null, "table") ?: continue
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    val value = parser.getAttributeValue(null, "value") ?: continue
                    synchronized(lock) { tables[table]?.put(name, value) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot load settings from " + file, t)
        }
    }

    private companion object {
        const val TAG = "SettingsProvider"
    }
}
