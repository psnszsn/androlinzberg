@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IDropBoxManagerService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedDropBoxManagerService : com.android.internal.os.IDropBoxManagerService.Stub() {
    override fun addData(tag: String?, data: ByteArray?, flags: Int) { throw UnsupportedOperationException("IDropBoxManagerService.addData is not hosted") }
    override fun addFile(tag: String?, fd: android.os.ParcelFileDescriptor?, flags: Int) { throw UnsupportedOperationException("IDropBoxManagerService.addFile is not hosted") }
    override fun isTagEnabled(tag: String?): Boolean = throw UnsupportedOperationException("IDropBoxManagerService.isTagEnabled is not hosted")
    override fun getNextEntry(tag: String?, millis: Long, packageName: String?): android.os.DropBoxManager.Entry? = throw UnsupportedOperationException("IDropBoxManagerService.getNextEntry is not hosted")
    override fun getNextEntryWithAttribution(tag: String?, millis: Long, packageName: String?, attributionTag: String?): android.os.DropBoxManager.Entry? = throw UnsupportedOperationException("IDropBoxManagerService.getNextEntryWithAttribution is not hosted")
}
