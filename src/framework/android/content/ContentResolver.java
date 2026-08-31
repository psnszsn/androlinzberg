/* Hosted adaptation of AOSP android.content.ContentResolver (reduced).
 *
 * Provider access rides android.content.IContentProvider (the verbatim AOSP
 * interface) over the hosted binder: subclasses supply acquireProvider —
 * ApplicationContentResolver resolves authorities through the session's
 * activity manager. Observer registration stays a no-op until the hosted
 * content service exists. AOSP's unstable-provider split and sync surface are
 * not carried: provider references are process-cached and death is handled by
 * the acquisition cache. */
package android.content;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Size;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
public abstract class ContentResolver {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface NotifyFlags {}
    public static final String SCHEME_CONTENT = "content", SCHEME_ANDROID_RESOURCE = "android.resource", SCHEME_FILE = "file";
    public static final int NOTIFY_SYNC_TO_NETWORK = 1, NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 2,
            NOTIFY_INSERT = 4, NOTIFY_UPDATE = 8, NOTIFY_DELETE = 16;
    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";
    public static final String QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args";
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";
    public static final String QUERY_ARG_LIMIT = "android:query-arg-limit";
    public static final String EXTRA_HONORED_ARGS = "android.content.extra.HONORED_ARGS";

    /** Resolves an authority to a live provider connection, or null (public
     * like AOSP's hidden acquireProvider — Settings.NameValueCache calls it). */
    public abstract IContentProvider acquireProvider(String authority);

    public final IContentProvider acquireProvider(Uri uri) {
        if (uri == null || !SCHEME_CONTENT.equals(uri.getScheme())) return null;
        String authority = uri.getAuthority();
        return authority != null ? acquireProvider(authority) : null;
    }

    /** The identity providers see; the transport ignores it in favor of the
     * broker-installed calling uid, but AOSP client code reads it. */
    public AttributionSource getAttributionSource() {
        return new AttributionSource(android.os.Process.myUid(), getPackageName(), null);
    }

    public String getPackageName() { return null; }

    /** The session's observer registry (dev.artzig.system.HostedContentService),
     * or null before the system process has published it. */
    private static dev.artzig.system.IHostedContentService contentService() {
        android.os.IBinder binder = android.os.ServiceManager.getService("content");
        return binder != null ? dev.artzig.system.IHostedContentService.Stub.asInterface(binder) : null;
    }
    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
            android.database.ContentObserver observer) {
        dev.artzig.system.IHostedContentService service = contentService();
        if (service == null) return;
        try { service.registerContentObserver(uri, notifyForDescendants, observer.getContentObserver()); }
        catch (RemoteException e) { /* session teardown */ }
    }
    public void unregisterContentObserver(android.database.ContentObserver observer) {
        dev.artzig.system.IHostedContentService service = contentService();
        if (service == null) return;
        try {
            android.database.IContentObserver transport = observer.releaseContentObserver();
            if (transport != null) service.unregisterContentObserver(transport);
        } catch (RemoteException e) { /* session teardown */ }
    }
    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
            android.database.ContentObserver observer, int userHandle) {
        registerContentObserver(uri, notifyForDescendants, observer);
    }
    public void notifyChange(Uri uri, android.database.ContentObserver observer) {
        notifyChange(uri, observer, 0);
    }
    public void notifyChange(Uri uri, android.database.ContentObserver observer, int flags) {
        dev.artzig.system.IHostedContentService service = contentService();
        if (service == null) return;
        // The skip-observer only suppresses self-notification for observers
        // that did not opt in (AOSP ContentObserver.deliverSelfNotifications).
        android.database.IContentObserver skip =
                observer != null && !observer.deliverSelfNotifications()
                        ? observer.getContentObserver() : null;
        try {
            service.notifyChange(uri, skip, flags);
        } catch (RemoteException e) { /* session teardown */ }
    }

    public String getType(Uri url) {
        IContentProvider provider = acquireProvider(url);
        if (provider == null) return null;
        try { return provider.getType(getAttributionSource(), url); }
        catch (RemoteException e) { return null; }
    }
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) {
        IContentProvider provider = acquireProvider(url);
        if (provider == null) return null;
        try { return provider.getStreamTypes(getAttributionSource(), url, mimeTypeFilter); }
        catch (RemoteException e) { return null; }
    }
    public int getUserId() { return 0; }
    public static void onDbCorruption(String tag, String message, Throwable stacktrace) {}

    public android.database.Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return query(uri, projection, createSqlQueryBundle(selection, selectionArgs, sortOrder), null);
    }
    public android.database.Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        Objects.requireNonNull(uri, "uri");
        if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) return null;
        try { return provider.query(getAttributionSource(), uri, projection, queryArgs, null); }
        catch (RemoteException e) { return null; }
    }
    public Uri insert(Uri url, ContentValues values) { return insert(url, values, null); }
    public Uri insert(Uri url, ContentValues values, Bundle extras) {
        Objects.requireNonNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) throw new IllegalArgumentException("Unknown URL " + url);
        try { return provider.insert(getAttributionSource(), url, values, extras); }
        catch (RemoteException e) { return null; }
    }
    public int update(Uri uri, ContentValues values, String where, String[] selectionArgs) {
        return update(uri, values, createSqlQueryBundle(where, selectionArgs));
    }
    public int update(Uri uri, ContentValues values, Bundle extras) {
        Objects.requireNonNull(uri, "uri");
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) throw new IllegalArgumentException("Unknown URI " + uri);
        try { return provider.update(getAttributionSource(), uri, values, extras); }
        catch (RemoteException e) { return -1; }
    }
    public int delete(Uri url, String where, String[] selectionArgs) {
        return delete(url, createSqlQueryBundle(where, selectionArgs));
    }
    public int delete(Uri url, Bundle extras) {
        Objects.requireNonNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) throw new IllegalArgumentException("Unknown URL " + url);
        try { return provider.delete(getAttributionSource(), url, extras); }
        catch (RemoteException e) { return -1; }
    }
    public Bundle call(Uri uri, String method, String arg, Bundle extras) {
        Objects.requireNonNull(uri, "uri");
        return call(uri.getAuthority(), method, arg, extras);
    }
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(method, "method");
        IContentProvider provider = acquireProvider(authority);
        if (provider == null) throw new IllegalArgumentException("Unknown authority " + authority);
        try { return provider.call(getAttributionSource(), authority, method, arg, extras); }
        catch (RemoteException e) { return null; }
    }
    public Uri canonicalize(Uri url) {
        Objects.requireNonNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) return null;
        try { return provider.canonicalize(getAttributionSource(), url); }
        catch (RemoteException e) { return null; }
    }
    public Uri uncanonicalize(Uri url) {
        Objects.requireNonNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) return null;
        try { return provider.uncanonicalize(getAttributionSource(), url); }
        catch (RemoteException e) { return null; }
    }

    public static class OpenResourceIdResult {
        public android.content.res.Resources r;
        public int id;
    }
    public OpenResourceIdResult getResourceId(Uri uri) throws java.io.FileNotFoundException {
        throw new java.io.FileNotFoundException(String.valueOf(uri));
    }
    public InputStream openInputStream(Uri uri) throws FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        if (SCHEME_FILE.equals(uri.getScheme())) return new java.io.FileInputStream(uri.getPath());
        if (SCHEME_CONTENT.equals(uri.getScheme())) {
            AssetFileDescriptor fd = openAssetFileDescriptor(uri, "r");
            if (fd == null) throw new FileNotFoundException("Provider returned no file for " + uri);
            try {
                return fd.createInputStream();
            } catch (IOException e) {
                throw new FileNotFoundException("Unable to create stream for " + uri);
            }
        }
        throw new FileNotFoundException(String.valueOf(uri));
    }
    public OutputStream openOutputStream(Uri uri) throws FileNotFoundException {
        return openOutputStream(uri, "w");
    }
    public OutputStream openOutputStream(Uri uri, String mode) throws FileNotFoundException {
        AssetFileDescriptor fd = openAssetFileDescriptor(uri, mode);
        if (fd == null) throw new FileNotFoundException("Provider returned no file for " + uri);
        try {
            return fd.createOutputStream();
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to create stream for " + uri);
        }
    }
    public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
        return openFileDescriptor(uri, mode, null);
    }
    public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode,
            CancellationSignal cancellationSignal) throws FileNotFoundException {
        AssetFileDescriptor afd = openAssetFileDescriptor(uri, mode, cancellationSignal);
        if (afd == null) return null;
        if (afd.getDeclaredLength() < 0) return afd.getParcelFileDescriptor();
        try { afd.close(); } catch (IOException ignored) {}
        throw new FileNotFoundException("Not a whole file for " + uri);
    }
    public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFileDescriptor(uri, mode, null);
    }
    public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode,
            CancellationSignal cancellationSignal) throws FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(mode, "mode");
        if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
        if (SCHEME_FILE.equals(uri.getScheme())) {
            try {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        new java.io.File(uri.getPath()), ParcelFileDescriptor.parseMode(mode));
                return new AssetFileDescriptor(pfd, 0, -1);
            } catch (IOException e) {
                throw new FileNotFoundException(String.valueOf(uri));
            }
        }
        if (!SCHEME_CONTENT.equals(uri.getScheme())) throw new FileNotFoundException(String.valueOf(uri));
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) throw new FileNotFoundException("No content provider for " + uri);
        try {
            return provider.openAssetFile(getAttributionSource(), uri, mode, null);
        } catch (RemoteException e) {
            throw new FileNotFoundException("Provider for " + uri + " is unreachable");
        }
    }
    public AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, Bundle opts)
            throws FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) throw new FileNotFoundException("No content provider for " + uri);
        try {
            return provider.openTypedAssetFile(getAttributionSource(), uri, mimeType, opts, null);
        } catch (RemoteException e) {
            throw new FileNotFoundException("Provider for " + uri + " is unreachable");
        }
    }

    /** AOSP helpers NameValueCache and the deprecated IContentProvider
     * defaults compile against. */
    public static Bundle createSqlQueryBundle(String selection, String[] selectionArgs) {
        return createSqlQueryBundle(selection, selectionArgs, null);
    }
    public static Bundle createSqlQueryBundle(String selection, String[] selectionArgs, String sortOrder) {
        if (selection == null && selectionArgs == null && sortOrder == null) return null;
        Bundle queryArgs = new Bundle();
        if (selection != null) queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
        if (selectionArgs != null) queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        if (sortOrder != null) queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
        return queryArgs;
    }

    /**
     * Hosted counterpart of AOSP ContentResolver.loadThumbnail. Provider-backed resolvers can
     * override this seam; the default implementation supports streams exposed by this resolver.
     */
    public Bitmap loadThumbnail(Uri uri, Size size, CancellationSignal cancellationSignal)
            throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(size, "size");
        if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
        try (InputStream input = openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IOException("Failed to decode thumbnail: " + uri);
            if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= size.getWidth() && height <= size.getHeight()) return bitmap;
            float scale = Math.min((float) size.getWidth() / width, (float) size.getHeight() / height);
            return Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), true);
        }
    }
}
