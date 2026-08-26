/* artzig stub. */
package android.content;
import android.net.Uri;
public abstract class ContentProvider {
    private Context mContext;
    public ContentProvider() {}
    public final Context getContext() { return mContext; }
    public void attachInfo(Context context, android.content.pm.ProviderInfo info) {
        mContext = context;
        onCreate();
    }
    public abstract boolean onCreate();
    public static Uri maybeAddUserId(Uri uri, int userId) { return uri; }
    public static int getUserIdFromUri(Uri uri, int defaultUserId) { return defaultUserId; }
    public static int getUserIdFromUri(Uri uri) { return 0; }
    public static Uri getUriWithoutUserId(Uri uri) { return uri; }
}
