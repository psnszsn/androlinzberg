/* artzig stub of AOSP android.provider.DocumentsContract: the real contract's
 * helpers ride ContentResolver/ContentProviderClient members the hosted
 * ContentResolver does not yet provide (android.app/content milestone), and
 * no DocumentsProvider is hosted. Constants only (Intent and StorageVolume
 * link against them). */
package android.provider;
import android.net.Uri;
public final class DocumentsContract {
    private DocumentsContract() {}
    public static final String EXTRA_PACKAGE_NAME = "android.content.extra.PACKAGE_NAME";
    public static final String EXTRA_SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED";
    public static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";
    public static final String EXTRA_PROMPT = "android.provider.extra.PROMPT";
    public static final String EXTRA_ORIENTATION = "android.provider.extra.ORIENTATION";
    public static final String EXTRA_INFO = "info";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_EXCLUDE_SELF = "android.provider.extra.EXCLUDE_SELF";
    public static final String ACTION_DOCUMENT_SETTINGS = "android.provider.action.DOCUMENT_SETTINGS";
    public static final String ACTION_MANAGE_DOCUMENT = "android.provider.action.MANAGE_DOCUMENT";
    public static final String EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents";
    public static final String EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID = "primary";
    private static final String PATH_ROOT = "root";
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_TREE = "tree";
    /** AOSP DocumentsContract.buildRootUri. */
    public static Uri buildRootUri(String authority, String rootId) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(PATH_ROOT).appendPath(rootId).build();
    }
    /** AOSP DocumentsContract.buildDocumentUri. */
    public static Uri buildDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(PATH_DOCUMENT).appendPath(documentId).build();
    }
    /** AOSP DocumentsContract.buildTreeDocumentUri. */
    public static Uri buildTreeDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(PATH_TREE).appendPath(documentId).build();
    }
    /** AOSP DocumentsContract.isDocumentUri: content://authority/document/id or /tree/id/document/id. */
    public static boolean isDocumentUri(android.content.Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        java.util.List<String> paths = uri.getPathSegments();
        return (paths.size() == 2 && PATH_DOCUMENT.equals(paths.get(0)))
                || (paths.size() == 4 && PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2)));
    }
    public static boolean isTreeUri(Uri uri) {
        java.util.List<String> paths = uri.getPathSegments();
        return paths.size() >= 2 && PATH_TREE.equals(paths.get(0));
    }
    public static String getDocumentId(Uri documentUri) {
        java.util.List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_DOCUMENT.equals(paths.get(0))) return paths.get(1);
        if (paths.size() >= 4 && PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2))) return paths.get(3);
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }
    public static String getTreeDocumentId(Uri documentUri) {
        java.util.List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_TREE.equals(paths.get(0))) return paths.get(1);
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }
    public static String getRootId(Uri rootUri) {
        java.util.List<String> paths = rootUri.getPathSegments();
        if (paths.size() >= 2 && PATH_ROOT.equals(paths.get(0))) return paths.get(1);
        throw new IllegalArgumentException("Invalid URI: " + rootUri);
    }
}
