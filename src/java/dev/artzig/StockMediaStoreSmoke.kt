package dev.artzig

import android.app.ActivityThread
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.provider.IMPCancellationSignal
import android.provider.MediaStore
import android.provider.OpenAssetFileRequest
import android.provider.OpenFileRequest
import android.provider.ParcelableException
import java.io.File
import java.io.FileNotFoundException

/** Stock MediaStore client calls against an in-memory provider and real file descriptors. */
internal object StockMediaStoreSmoke {
    private fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private class Fixture(context: Context, val file: File) {
        var calls = 0
        var releases = 0
        var acquisitions = 0
        var cancellations = 0
        var failure: Throwable? = null
        var cancelDuringOpen: CancellationSignal? = null
        var pending: OpenFileRequest? = null
        var assetRequest: OpenAssetFileRequest? = null
        var originalRequested = false
        val remoteCancellation = object : IMPCancellationSignal.Stub() {
            override fun cancel() {
                cancellations++
                pending?.callback?.onFailure(ParcelableException(OperationCanceledException("cancelled")))
            }
        }
        private fun descriptor() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val provider: ContentProvider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("Unexpected query")
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                selectionArgs: Array<out String>?): Int = error("Unexpected update")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected delete")
            override fun getType(uri: Uri) = "image/test"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                check(mode == "r")
                return descriptor()
            }
            override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor {
                originalRequested = opts?.getParcelable(MediaStore.EXTRA_FILE_DESCRIPTOR, ParcelFileDescriptor::class.java) != null
                if (originalRequested) check(uri == MediaStore.Files.EXTERNAL_CONTENT_URI && mimeTypeFilter == "*/*")
                return AssetFileDescriptor(descriptor(), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                calls++
                return when (method) {
                    MediaStore.CREATE_CANCELLATION_SIGNAL_CALL -> Bundle().apply {
                        putBinder(MediaStore.CREATE_CANCELLATION_SIGNAL_RESULT, remoteCancellation)
                    }
                    MediaStore.OPEN_FILE_CALL -> {
                        val request = roundTrip(checkNotNull(extras).getParcelable(
                            MediaStore.EXTRA_OPEN_FILE_REQUEST, OpenFileRequest::class.java)!!, OpenFileRequest.CREATOR)
                        pending = request
                        if (cancelDuringOpen != null) cancelDuringOpen!!.cancel()
                        else if (failure != null) request.callback.onFailure(roundTrip(
                            ParcelableException(failure!!), ParcelableException.CREATOR))
                        else request.callback.onSuccess(descriptor())
                        Bundle.EMPTY
                    }
                    MediaStore.OPEN_ASSET_FILE_CALL -> {
                        val request = roundTrip(checkNotNull(extras).getParcelable(
                            MediaStore.EXTRA_OPEN_ASSET_FILE_REQUEST, OpenAssetFileRequest::class.java)!!,
                            OpenAssetFileRequest.CREATOR)
                        assetRequest = request
                        request.callback.onSuccess(AssetFileDescriptor(descriptor(), 6, 7))
                        Bundle.EMPTY
                    }
                    else -> error("Unexpected provider call: $method")
                }
            }
        }
        val resolver: ContentResolver = object : ContentResolver(context) {
            override fun acquireProvider(c: Context, authority: String): IContentProvider? {
                if (authority != MediaStore.AUTHORITY) return null
                acquisitions++
                return provider.getIContentProvider()
            }
            override fun acquireExistingProvider(c: Context, authority: String) = acquireProvider(c, authority)
            override fun acquireUnstableProvider(c: Context, authority: String) = acquireProvider(c, authority)
            override fun releaseProvider(provider: IContentProvider): Boolean { releases++; return true }
            override fun releaseUnstableProvider(provider: IContentProvider) = releaseProvider(provider)
            override fun unstableProviderDied(provider: IContentProvider) { error("Unexpected provider death") }
        }
        val context: Context = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = this@Fixture.resolver
        }
        init { provider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY; exported = true }) }
    }

    fun run() {
        val directory = java.nio.file.Files.createTempDirectory("artzig-mediastore-").toFile()
        try {
            val file = File(directory, "provider.bin").apply { writeText("prefixPAYLOADsuffix") }
            val fixture = Fixture(ActivityThread.currentActivityThread().systemContext, file)
            val resolver = fixture.resolver
            val cloud = Uri.parse("content://media/picker/0/dev.example.cloud/media/item")
            val local = Uri.parse("content://media/external/images/media/1")
            check(runCatching { MediaStore.openFileDescriptor(resolver, Uri.fromFile(file), "r", null) }
                .exceptionOrNull() is IllegalArgumentException)
            check(runCatching { MediaStore.openAssetFileDescriptor(resolver, cloud, "w", null) }
                .exceptionOrNull() is SecurityException)
            check(fixture.calls == 0)
            MediaStore.openFileDescriptor(resolver, local, "r", null)!!.let {
                check(ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().decodeToString() } == "prefixPAYLOADsuffix")
            }
            val signal = CancellationSignal()
            MediaStore.openFileDescriptor(resolver, cloud, "r", signal)!!.use { check(it.statSize == file.length()) }
            check(fixture.pending!!.uri == cloud && fixture.pending!!.cancellationSignal!!.asBinder() === fixture.remoteCancellation)
            val callsAfterSuccess = fixture.calls
            signal.cancel()
            check(fixture.cancellations == 0 && fixture.calls == callsAfterSuccess)
            for (failure in listOf(FileNotFoundException("missing"), SecurityException("denied"),
                IllegalArgumentException("invalid"), OperationCanceledException("cancelled"))) {
                fixture.failure = failure
                val error = runCatching { MediaStore.openFileDescriptor(resolver, cloud, "r", null) }.exceptionOrNull()
                check(error?.javaClass == failure.javaClass && error?.message == failure.message)
            }
            fixture.failure = null
            fixture.cancelDuringOpen = CancellationSignal()
            check(runCatching { MediaStore.openFileDescriptor(resolver, cloud, "r", fixture.cancelDuringOpen) }
                .exceptionOrNull() is OperationCanceledException)
            check(fixture.cancellations == 1)
            fixture.cancelDuringOpen = null
            val alreadyCancelled = CancellationSignal().apply { cancel() }
            val before = fixture.calls
            check(runCatching { MediaStore.openFileDescriptor(resolver, cloud, "r", alreadyCancelled) }
                .exceptionOrNull() is OperationCanceledException)
            check(fixture.calls == before)
            val options = Bundle().apply { putString("fixture", "kept") }
            MediaStore.openTypedAssetFileDescriptor(resolver, cloud, "image/*", options, null)!!.use {
                check(it.createInputStream().use { input -> input.readBytes().decodeToString() } == "PAYLOAD")
            }
            check(fixture.assetRequest!!.uri == cloud && fixture.assetRequest!!.mimeType == "image/*")
            check(fixture.assetRequest!!.opts!!.getString("fixture") == "kept")
            val input = File(directory, "input.bin").apply { writeText("different input") }
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val original = MediaStore.getOriginalMediaFormatFileDescriptor(fixture.context, pfd)
                check(ParcelFileDescriptor.AutoCloseInputStream(original).use { it.readBytes().decodeToString() } == "prefixPAYLOADsuffix")
                check(pfd.statSize == input.length())
            }
            check(fixture.originalRequested && fixture.releases == fixture.acquisitions)
            check(MediaStore.getVolumeName(local) == MediaStore.VOLUME_EXTERNAL)
            check(MediaStore.rewriteToLegacy(local).authority == MediaStore.AUTHORITY_LEGACY)
        } finally { directory.deleteRecursively() }
        println("stock MediaStore provider, descriptor and cancellation contracts: PASS")
    }
}
