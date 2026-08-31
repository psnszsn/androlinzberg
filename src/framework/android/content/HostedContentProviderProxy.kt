package android.content

import android.content.res.AssetFileDescriptor
import android.database.BulkCursorToCursorAdaptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ICancellationSignal
import android.os.ParcelFileDescriptor
import android.os.RemoteCallback
import dev.artzig.system.IHostedContentProvider
import java.io.FileNotFoundException

/**
 * android.content.IContentProvider over the hosted transport (source note:
 * AOSP ContentProviderProxy in ContentProviderNative.java). AttributionSource
 * arguments are ignored — the broker-installed calling uid is authoritative.
 * A proxy whose binder lives in this process short-circuits to the provider
 * itself (AOSP local-provider semantics: no cursor marshalling, checked
 * exceptions intact); the remote path rebuilds cursors from BulkCursor
 * descriptors carrying real CursorWindows.
 */
class HostedContentProviderProxy(private val remote: IHostedContentProvider) : IContentProvider {
    private val local: ContentProvider? = (remote as? ContentProvider.Transport)?.provider()

    override fun asBinder(): IBinder = remote.asBinder()

    override fun query(
        attributionSource: AttributionSource,
        url: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: ICancellationSignal?,
    ): Cursor? {
        local?.let { return it.query(url, projection, queryArgs, null) }
        val adaptor = BulkCursorToCursorAdaptor()
        val descriptor = remote.query(url, projection, queryArgs, adaptor.observer)
        if (descriptor == null) {
            adaptor.close()
            return null
        }
        adaptor.initialize(descriptor)
        return adaptor
    }

    override fun getType(attributionSource: AttributionSource, url: Uri): String? {
        local?.let { return it.getType(url) }
        return remote.getType(url)
    }

    override fun getTypeAsync(attributionSource: AttributionSource, url: Uri, callback: RemoteCallback) {
        val result = Bundle()
        result.putString("result", getType(attributionSource, url))
        callback.sendResult(result)
    }

    override fun getTypeAnonymousAsync(uri: Uri, callback: RemoteCallback) {
        val result = Bundle()
        result.putString("result", if (local != null) local.getType(uri) else remote.getType(uri))
        callback.sendResult(result)
    }

    override fun insert(
        attributionSource: AttributionSource,
        url: Uri,
        initialValues: ContentValues?,
        extras: Bundle?,
    ): Uri? {
        local?.let { return it.insert(url, initialValues, extras) }
        return remote.insert(url, initialValues, extras)
    }

    override fun bulkInsert(
        attributionSource: AttributionSource,
        url: Uri,
        initialValues: Array<ContentValues>,
    ): Int {
        local?.let { return it.bulkInsert(url, initialValues) }
        return remote.bulkInsert(url, initialValues)
    }

    override fun delete(attributionSource: AttributionSource, url: Uri, extras: Bundle?): Int {
        local?.let { return it.delete(url, extras) }
        return remote.delete(url, extras)
    }

    override fun update(
        attributionSource: AttributionSource,
        url: Uri,
        values: ContentValues?,
        extras: Bundle?,
    ): Int {
        local?.let { return it.update(url, values, extras) }
        return remote.update(url, values, extras)
    }

    override fun call(
        attributionSource: AttributionSource,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        local?.let { return it.call(authority, method, arg, extras) }
        return remote.call(authority, method, arg, extras)
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(
        attributionSource: AttributionSource,
        url: Uri,
        mode: String,
        signal: ICancellationSignal?,
    ): ParcelFileDescriptor? {
        local?.let { return it.openFile(url, mode) }
        // The transport rethrows FileNotFoundException (checked, not
        // parcelable by the AIDL stub) as IllegalArgumentException.
        try {
            return remote.openFile(url, mode)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(
        attributionSource: AttributionSource,
        url: Uri,
        mode: String,
        signal: ICancellationSignal?,
    ): AssetFileDescriptor? {
        local?.let { return it.openAssetFile(url, mode) }
        try {
            return remote.openAssetFile(url, mode)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(
        attributionSource: AttributionSource,
        url: Uri,
        mimeType: String,
        opts: Bundle?,
        signal: ICancellationSignal?,
    ): AssetFileDescriptor? {
        local?.let { return it.openTypedAssetFile(url, mimeType, opts) }
        try {
            return remote.openTypedAssetFile(url, mimeType, opts)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message)
        }
    }

    override fun getStreamTypes(
        attributionSource: AttributionSource,
        url: Uri,
        mimeTypeFilter: String,
    ): Array<String>? {
        local?.let { return it.getStreamTypes(url, mimeTypeFilter) }
        return remote.getStreamTypes(url, mimeTypeFilter)
    }

    override fun canonicalize(attributionSource: AttributionSource, url: Uri): Uri? {
        local?.let { return it.canonicalize(url) }
        return remote.canonicalize(url)
    }

    override fun canonicalizeAsync(attributionSource: AttributionSource, uri: Uri, callback: RemoteCallback) {
        val result = Bundle()
        result.putParcelable("result", canonicalize(attributionSource, uri))
        callback.sendResult(result)
    }

    override fun uncanonicalize(attributionSource: AttributionSource, url: Uri): Uri? {
        local?.let { return it.uncanonicalize(url) }
        return remote.uncanonicalize(url)
    }

    override fun uncanonicalizeAsync(attributionSource: AttributionSource, uri: Uri, callback: RemoteCallback) {
        val result = Bundle()
        result.putParcelable("result", uncanonicalize(attributionSource, uri))
        callback.sendResult(result)
    }

    override fun applyBatch(
        attributionSource: AttributionSource,
        authority: String,
        operations: ArrayList<ContentProviderOperation>,
    ): Array<ContentProviderResult> {
        local?.let { return it.applyBatch(authority, operations) }
        throw UnsupportedOperationException("cross-process applyBatch is not hosted yet")
    }

    override fun checkUriPermission(
        attributionSource: AttributionSource,
        uri: Uri,
        uid: Int,
        modeFlags: Int,
    ): Int = android.content.pm.PackageManager.PERMISSION_DENIED

    override fun createCancellationSignal(): ICancellationSignal = ICancellationSignal.Default()

    override fun refresh(
        attributionSource: AttributionSource,
        url: Uri,
        extras: Bundle?,
        cancellationSignal: ICancellationSignal?,
    ): Boolean {
        local?.let { return it.refresh(url, extras, null) }
        return false
    }
}
