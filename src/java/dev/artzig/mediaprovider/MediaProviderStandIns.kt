package dev.artzig.mediaprovider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageVolume
import android.service.storage.ExternalStorageService
import java.io.File

/**
 * MediaProvider's MediaStore content provider contract, as far as stock
 * StorageManagerService needs it: StorageSessionController resolves the
 * system app holding the `media` authority to find the external storage
 * service, and stock storage code never queries it.
 *
 * Source note: stock packages/providers/MediaProvider is not compiled because
 * its closure needs the FUSE daemon (libfuse_jni over /dev/fuse, which vold
 * mounts) and the media scanner and database. Until that lands, every MediaStore
 * operation an app performs is journaled as unimplemented and finds nothing.
 */
class MediaStoreProvider : ContentProvider() {
    private fun unhosted(member: String, uri: Uri?) = dev.artzig.ArtzigTestDiagnostics.reportMissing(
        "MediaProvider", member, "The MediaStore index is not hosted: $member $uri")

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
        sortOrder: String?): Cursor? { unhosted("query", uri); return null }
    override fun getType(uri: Uri): String? { unhosted("getType", uri); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { unhosted("insert", uri); return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int { unhosted("delete", uri); return 0 }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        unhosted("update", uri); return 0
    }
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        unhosted("call", Uri.parse("content://media/$method")); return null
    }
}

/**
 * MediaProvider's ExternalStorageServiceImpl without its FUSE daemon: on a
 * device the service serves /storage through the FUSE device vold mounted.
 * Here the volume's lower path is the directory apps already use, so a session
 * is accepted and the placeholder device descriptor is closed.
 */
class HostedExternalStorageService : ExternalStorageService() {
    override fun onStartSession(sessionId: String, flag: Int, deviceFd: ParcelFileDescriptor,
        upperFileSystemPath: File, lowerFileSystemPath: File) {
        deviceFd.close()
    }
    override fun onEndSession(sessionId: String) {}
    override fun onVolumeStateChanged(vol: StorageVolume) {}
}
