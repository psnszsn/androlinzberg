/* artzig shim: android.content.ContentResolver — scheme constants + open stubs. */
package android.content;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Size;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;
public abstract class ContentResolver {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface NotifyFlags {}
    public static final String SCHEME_CONTENT = "content", SCHEME_ANDROID_RESOURCE = "android.resource", SCHEME_FILE = "file";
    public static final int NOTIFY_SYNC_TO_NETWORK = 1, NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 2,
            NOTIFY_INSERT = 4, NOTIFY_UPDATE = 8, NOTIFY_DELETE = 16;
    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
            android.database.ContentObserver observer) {}
    public void unregisterContentObserver(android.database.ContentObserver observer) {}
    public void registerContentObserver(Uri uri, boolean notifyForDescendants,
            android.database.ContentObserver observer, int userHandle) {}
    public void notifyChange(Uri uri, android.database.ContentObserver observer) {}
    public void notifyChange(Uri uri, android.database.ContentObserver observer, int flags) {}
    public String getType(Uri url) { return null; }
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) { return null; }
    public int getUserId() { return 0; }
    public static void onDbCorruption(String tag, String message, Throwable stacktrace) {}
    public android.database.Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    public Uri insert(Uri url, ContentValues values) { return null; }
    public int update(Uri uri, ContentValues values, String where, String[] selectionArgs) { return 0; }
    public int delete(Uri url, String where, String[] selectionArgs) { return 0; }
    public static class OpenResourceIdResult {
        public android.content.res.Resources r;
        public int id;
    }
    public OpenResourceIdResult getResourceId(Uri uri) throws java.io.FileNotFoundException {
        throw new java.io.FileNotFoundException(String.valueOf(uri));
    }
    public InputStream openInputStream(Uri uri) throws FileNotFoundException {
        if (uri != null && "file".equals(uri.getScheme())) return new java.io.FileInputStream(uri.getPath());
        throw new FileNotFoundException(String.valueOf(uri));
    }
    public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
        throw new FileNotFoundException(String.valueOf(uri));
    }
    public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode,
            CancellationSignal cancellationSignal) throws FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(mode, "mode");
        if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
        return openAssetFileDescriptor(uri, mode);
    }
    public AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, android.os.Bundle opts) throws FileNotFoundException {
        throw new FileNotFoundException(String.valueOf(uri));
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
