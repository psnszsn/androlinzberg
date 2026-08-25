/* artzig stub. */
package android.content;
import android.net.Uri;
public abstract class ContentProvider {
    public static Uri maybeAddUserId(Uri uri, int userId) { return uri; }
    public static int getUserIdFromUri(Uri uri, int defaultUserId) { return defaultUserId; }
    public static int getUserIdFromUri(Uri uri) { return 0; }
    public static Uri getUriWithoutUserId(Uri uri) { return uri; }
}
