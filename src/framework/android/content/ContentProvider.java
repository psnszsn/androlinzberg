/* Hosted adaptation of AOSP android.content.ContentProvider (reduced).
 *
 * The classic abstract surface (query/getType/insert/delete/update/onCreate)
 * matches AOSP so APK providers work unchanged; the modern Bundle overloads
 * delegate per AOSP. The IPC half is Transport, an IHostedContentProvider
 * stub instead of AOSP's ContentProviderNative (whose server path needs the
 * whole AttributionSource/PermissionChecker stack): identity is the
 * broker-installed calling uid, permission gates follow
 * ContentProvider.enforceReadPermissionInner, and query() ships a real
 * CursorWindow through CursorToBulkCursorAdaptor. */
package android.content;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.CursorToBulkCursorAdaptor;
import android.database.BulkCursorDescriptor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;

public abstract class ContentProvider {
    private Context mContext;
    private ProviderInfo mInfo;
    private String mAuthority;
    private Transport mTransport;
    private final ThreadLocal<Integer> mCallingUid = new ThreadLocal<>();

    public ContentProvider() {}

    public final Context getContext() { return mContext; }

    public void attachInfo(Context context, ProviderInfo info) {
        mContext = context;
        mInfo = info;
        if (info != null && info.authority != null) {
            mAuthority = info.authority.split(";")[0];
        }
        onCreate();
    }

    public abstract boolean onCreate();

    // Classic provider surface (what APK providers implement).
    public abstract Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder);
    public abstract String getType(Uri uri);
    public abstract Uri insert(Uri uri, ContentValues values);
    public abstract int delete(Uri uri, String selection, String[] selectionArgs);
    public abstract int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs);

    // Modern overloads, delegating per AOSP.
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, CancellationSignal cancellationSignal) {
        return query(uri, projection, selection, selectionArgs, sortOrder);
    }
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        if (queryArgs == null) queryArgs = Bundle.EMPTY;
        return query(uri, projection,
                queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
                cancellationSignal);
    }
    public Uri insert(Uri uri, ContentValues values, Bundle extras) { return insert(uri, values); }
    public int delete(Uri uri, Bundle extras) {
        if (extras == null) extras = Bundle.EMPTY;
        return delete(uri, extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }
    public int update(Uri uri, ContentValues values, Bundle extras) {
        if (extras == null) extras = Bundle.EMPTY;
        return update(uri, values, extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int count = 0;
        for (ContentValues v : values) {
            if (insert(uri, v) != null) count++;
        }
        return count;
    }
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return call(method, arg, extras);
    }
    public Bundle call(String method, String arg, Bundle extras) { return null; }
    public ContentProviderResult[] applyBatch(String authority,
            ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        return applyBatch(operations);
    }
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        int numOperations = operations.size();
        ContentProviderResult[] results = new ContentProviderResult[numOperations];
        for (int i = 0; i < numOperations; i++) {
            results[i] = operations.get(i).apply(this, results, i);
        }
        return results;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        throw new FileNotFoundException("No files supported by provider at " + uri);
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return openFile(uri, mode);
    }
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor fd = openFile(uri, mode);
        return fd != null ? new AssetFileDescriptor(fd, 0, -1) : null;
    }
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return openAssetFile(uri, mode);
    }
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        if ("*/*".equals(mimeTypeFilter)) return openAssetFile(uri, "r");
        String baseType = getType(uri);
        if (baseType != null && ClipDescription.compareMimeTypes(baseType, mimeTypeFilter)) {
            return openAssetFile(uri, "r");
        }
        throw new FileNotFoundException("Can't open " + uri + " as type " + mimeTypeFilter);
    }
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal) throws FileNotFoundException {
        return openTypedAssetFile(uri, mimeTypeFilter, opts);
    }
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) { return null; }
    public Uri canonicalize(Uri url) { return null; }
    public Uri uncanonicalize(Uri url) { return url; }
    public boolean refresh(Uri uri, Bundle extras, CancellationSignal cancellationSignal) {
        return false;
    }

    /** AOSP helper: resolve the _data column of `uri` and open it. */
    protected final ParcelFileDescriptor openFileHelper(Uri uri, String mode)
            throws FileNotFoundException {
        Cursor c = query(uri, new String[] { "_data" }, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            if (c != null) c.close();
            throw new FileNotFoundException(count == 0
                    ? "No entry for " + uri : "Multiple items at " + uri);
        }
        c.moveToFirst();
        int i = c.getColumnIndex("_data");
        String path = (i >= 0 ? c.getString(i) : null);
        c.close();
        if (path == null) throw new FileNotFoundException("Column _data not found.");
        try {
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.parseMode(mode));
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("Cannot open " + path);
        }
    }

    /** Package of the caller of the current transport call, best-effort (the
     * broker uid is authoritative; the name comes from the package manager). */
    public String getCallingPackage() {
        Integer uid = mCallingUid.get();
        if (uid == null) return null;
        PackageManager pm = mContext != null ? mContext.getPackageManager() : null;
        if (pm == null) return null;
        String[] packages = pm.getPackagesForUid(uid);
        return packages != null && packages.length > 0 ? packages[0] : null;
    }

    public AttributionSource getCallingAttributionSource() {
        Integer uid = mCallingUid.get();
        if (uid == null) return null;
        return new AttributionSource(uid, getCallingPackage(), null);
    }

    /** The ProviderInfo this provider was attached with (hosted accessor for
     * the application runtime's publication path). */
    public ProviderInfo providerInfo() { return mInfo; }

    /** The AOSP-shaped client interface for this provider; local callers get
     * the in-process short-circuit inside the proxy. */
    public IContentProvider getIContentProvider() {
        return new HostedContentProviderProxy(transport());
    }

    private synchronized Transport transport() {
        if (mTransport == null) mTransport = new Transport();
        return mTransport;
    }

    /**
     * IPC half (source note: AOSP ContentProvider.Transport +
     * enforceReadPermissionInner). Runs on binder threads; sets the
     * calling-uid thread-local around each dispatch.
     */
    /** AOSP ContentProvider.attachInfoForTesting: attach without the process-wide registration. */
    public void attachInfoForTesting(Context context, ProviderInfo info) {
        attachInfo(context, info);
    }

    /**
     * AOSP ContentProvider.enforceReadPermissionInner: the calling uid (the
     * broker's, authoritative here; the attribution source is informational)
     * must hold the provider's read permission. Returns PERMISSION_GRANTED or
     * throws SecurityException, as AOSP does for the caller's own checks.
     */
    protected int enforceReadPermissionInner(Uri uri, AttributionSource attributionSource)
            throws SecurityException {
        enforcePermissionInner(false, "read " + uri);
        return PackageManager.PERMISSION_GRANTED;
    }

    /** AOSP ContentProvider.enforceWritePermissionInner; see enforceReadPermissionInner. */
    protected int enforceWritePermissionInner(Uri uri, AttributionSource attributionSource)
            throws SecurityException {
        enforcePermissionInner(true, "write " + uri);
        return PackageManager.PERMISSION_GRANTED;
    }

    private void enforcePermissionInner(boolean write, String what) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return;
        ProviderInfo info = mInfo;
        if (info == null) return;
        if (!info.exported) {
            throw new SecurityException("Provider " + mAuthority
                    + " is not exported (uid " + uid + ", " + what + ")");
        }
        String permission = write ? info.writePermission : info.readPermission;
        if (permission == null) return;
        if (android.app.HostedServiceClient.checkUidPermission(permission, uid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Provider " + mAuthority + " requires "
                    + permission + " for " + what + " (uid " + uid + ")");
        }
    }

    final class Transport extends dev.artzig.system.IHostedContentProvider.Stub {
        ContentProvider provider() { return ContentProvider.this; }

        private void enforcePermission(boolean write, String what) {
            enforcePermissionInner(write, what);
        }

        /** Exported-ness alone (AOSP serves mime types without a read
         * permission, but never from a non-exported provider). */
        private void enforceExported(String what) {
            int uid = Binder.getCallingUid();
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return;
            ProviderInfo info = mInfo;
            if (info != null && !info.exported) {
                throw new SecurityException("Provider " + mAuthority
                        + " is not exported (uid " + uid + ", " + what + ")");
            }
        }

        /** Returns the previous identity so nested dispatches on one thread
         * restore rather than wipe it (AOSP saves a CallingIdentity). */
        private Integer setCaller() {
            Integer previous = mCallingUid.get();
            mCallingUid.set(Binder.getCallingUid());
            return previous;
        }

        private void clearCaller(Integer previous) {
            if (previous == null) mCallingUid.remove();
            else mCallingUid.set(previous);
        }

        @Override
        public BulkCursorDescriptor query(Uri url, String[] projection, Bundle queryArgs,
                IContentObserver observer) {
            enforcePermission(false, "query");
            Integer previousCaller = setCaller();
            try {
                Cursor cursor = ContentProvider.this.query(url, projection, queryArgs, null);
                if (cursor == null) return null;
                CursorToBulkCursorAdaptor adaptor =
                        new CursorToBulkCursorAdaptor(cursor, observer, String.valueOf(mAuthority));
                return adaptor.getBulkCursorDescriptor();
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public String getType(Uri url) {
            // AOSP serves getType without a permission gate (mime types are
            // not protected); exported-ness still applies.
            enforceExported("getType");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.getType(url);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public Uri insert(Uri url, ContentValues values, Bundle extras) {
            enforcePermission(true, "insert");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.insert(url, values, extras);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public int bulkInsert(Uri url, ContentValues[] values) {
            enforcePermission(true, "bulkInsert");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.bulkInsert(url, values);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public int delete(Uri url, Bundle extras) {
            enforcePermission(true, "delete");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.delete(url, extras);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public int update(Uri url, ContentValues values, Bundle extras) {
            enforcePermission(true, "update");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.update(url, values, extras);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public Bundle call(String authority, String method, String arg, Bundle extras) {
            // Per AOSP, call() enforces only exported-ness; the provider
            // implements its own method-level policy.
            enforcePermission(false, "call");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.call(authority, method, arg, extras);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public ParcelFileDescriptor openFile(Uri url, String mode) {
            enforcePermission(mode != null && mode.indexOf('w') >= 0, "openFile");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.openFile(url, mode);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(e.getMessage());
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public AssetFileDescriptor openAssetFile(Uri url, String mode) {
            enforcePermission(mode != null && mode.indexOf('w') >= 0, "openAssetFile");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.openAssetFile(url, mode);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(e.getMessage());
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public AssetFileDescriptor openTypedAssetFile(Uri url, String mimeType, Bundle opts) {
            enforcePermission(false, "openTypedAssetFile");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.openTypedAssetFile(url, mimeType, opts);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(e.getMessage());
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public String[] getStreamTypes(Uri url, String mimeTypeFilter) {
            enforceExported("getStreamTypes");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.getStreamTypes(url, mimeTypeFilter);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public Uri canonicalize(Uri url) {
            enforcePermission(false, "canonicalize");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.canonicalize(url);
            } finally {
                clearCaller(previousCaller);
            }
        }

        @Override
        public Uri uncanonicalize(Uri url) {
            enforcePermission(false, "uncanonicalize");
            Integer previousCaller = setCaller();
            try {
                return ContentProvider.this.uncanonicalize(url);
            } finally {
                clearCaller(previousCaller);
            }
        }
    }

    public static Uri maybeAddUserId(Uri uri, int userId) { return uri; }
    public static int getUserIdFromUri(Uri uri, int defaultUserId) { return defaultUserId; }
    public static int getUserIdFromUri(Uri uri) { return 0; }
    public static Uri getUriWithoutUserId(Uri uri) { return uri; }
}
