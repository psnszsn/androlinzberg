package dev.artzig.system

import android.Manifest
import android.content.Context
import android.os.DropBoxManager
import android.os.ParcelFileDescriptor
import android.provider.Settings

/**
 * The session's IDropBoxManagerService over the real AIDL surface, published as
 * `Context.DROPBOX_SERVICE`.
 *
 * Source note: `com.android.server.DropBoxManagerService`: entries are ordered by a
 * strictly increasing timestamp (a colliding time is bumped by one millisecond),
 * a tag is enabled unless `Settings.Global` "dropbox:<tag>" says "disabled",
 * `getNextEntry` needs READ_LOGS and returns the first entry after the given time
 * for the tag (or any tag when null). Entries live in memory for the session,
 * capped at the AOSP default quota of files; nothing else in the session reads
 * them, so the ENTRY_ADDED broadcast is not sent.
 */
class HostedDropBoxManagerService(private val context: Context, private val permissions: HostedPermissions) : UnsupportedDropBoxManagerService() {
    private class Record(val tag: String, val time: Long, val data: ByteArray, val flags: Int)

    private val lock = Any()
    private val entries = ArrayList<Record>()
    private var lastTime = 0L

    private fun add(tag: String?, data: ByteArray, flags: Int) {
        val name = tag ?: throw NullPointerException("tag == null")
        if (!isTagEnabled(name)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            lastTime = if (now > lastTime) now else lastTime + 1
            entries.add(Record(name, lastTime, data, flags))
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    override fun addData(tag: String?, data: ByteArray?, flags: Int) = add(tag, data ?: ByteArray(0), flags)

    override fun addFile(tag: String?, fd: ParcelFileDescriptor?, flags: Int) {
        val bytes = fd?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } } ?: ByteArray(0)
        add(tag, bytes, flags)
    }

    override fun isTagEnabled(tag: String?): Boolean =
        "disabled" != Settings.Global.getString(context.contentResolver, "${Settings.Global.DROPBOX_TAG_PREFIX}$tag")

    override fun getNextEntry(tag: String?, millis: Long, packageName: String?): DropBoxManager.Entry? = getNextEntryWithAttribution(tag, millis, packageName, null)

    override fun getNextEntryWithAttribution(tag: String?, millis: Long, packageName: String?, attributionTag: String?): DropBoxManager.Entry? {
        permissions.enforce(Manifest.permission.READ_LOGS, "getNextEntry")
        val record = synchronized(lock) { entries.firstOrNull { it.time > millis && (tag == null || it.tag == tag) } } ?: return null
        return DropBoxManager.Entry(record.tag, record.time, record.data.clone(), record.flags)
    }

    private companion object {
        /** DropBoxManagerService DEFAULT_MAX_FILES. */
        const val MAX_ENTRIES = 1000
    }
}
