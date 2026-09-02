/* artzig stub of AOSP android.provider.Downloads (the download provider's hidden
 * contract): the real class names NetworkPolicyManager and the download
 * provider, neither hosted. DocumentsContract links against the authority. */
package android.provider;
public final class Downloads {
    private Downloads() {}
    public static final class Impl {
        public static final String AUTHORITY = "downloads";
        public static final android.net.Uri CONTENT_URI = android.net.Uri.parse("content://downloads/my_downloads");
        public static final android.net.Uri ALL_DOWNLOADS_CONTENT_URI = android.net.Uri.parse("content://downloads/all_downloads");
        private Impl() {}
    }
}
