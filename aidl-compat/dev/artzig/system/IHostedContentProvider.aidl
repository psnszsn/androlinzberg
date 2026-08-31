package dev.artzig.system;

import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.BulkCursorDescriptor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Cross-process transport behind android.content.IContentProvider (the AOSP
 * interface rides ContentProviderNative's raw transaction codes and
 * client-asserted AttributionSource identity; here the broker-installed
 * calling uid is authoritative and identity is never an argument). Cursors
 * travel as real CursorWindows: query wires the caller's observer into a
 * CursorToBulkCursorAdaptor and returns its BulkCursorDescriptor.
 */
interface IHostedContentProvider {
    BulkCursorDescriptor query(in Uri url, in String[] projection, in Bundle queryArgs, IContentObserver observer);
    String getType(in Uri url);
    Uri insert(in Uri url, in ContentValues values, in Bundle extras);
    int bulkInsert(in Uri url, in ContentValues[] values);
    int delete(in Uri url, in Bundle extras);
    int update(in Uri url, in ContentValues values, in Bundle extras);
    Bundle call(String authority, String method, String arg, in Bundle extras);
    ParcelFileDescriptor openFile(in Uri url, String mode);
    AssetFileDescriptor openAssetFile(in Uri url, String mode);
    AssetFileDescriptor openTypedAssetFile(in Uri url, String mimeType, in Bundle opts);
    String[] getStreamTypes(in Uri url, String mimeTypeFilter);
    Uri canonicalize(in Uri url);
    Uri uncanonicalize(in Uri url);
}
