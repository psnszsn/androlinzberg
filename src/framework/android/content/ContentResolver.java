/* artzig shim: android.content.ContentResolver — scheme constants + open stubs. */
package android.content;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import java.io.FileNotFoundException;
import java.io.InputStream;
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
    public AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, android.os.Bundle opts) throws FileNotFoundException {
        throw new FileNotFoundException(String.valueOf(uri));
    }
}
