/* artzig shim: android.os.Environment — host paths from env. */
package android.os;
import java.io.File;
public class Environment {
    public static final String MEDIA_MOUNTED = "mounted", MEDIA_UNMOUNTED = "unmounted";
    private static File dataDir() {
        String d = System.getenv("ARTZIG_DATA_DIR");
        if (d == null || d.isEmpty()) {
            String home = System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) home = "/tmp";
            d = home + "/.local/share/artzig";
        }
        File f = new File(d);
        f.mkdirs();
        return f;
    }
    public static File getDataDirectory() { return dataDir(); }
    public static File getRootDirectory() { return new File("/"); }
    public static File getExternalStorageDirectory() { return new File(dataDir(), "external"); }
    public static File getDownloadCacheDirectory() { return new File(dataDir(), "cache"); }
    public static File getStorageDirectory() { return getExternalStorageDirectory(); }
    public static String getExternalStorageState() { return MEDIA_MOUNTED; }
    public static String getExternalStorageState(File f) { return MEDIA_MOUNTED; }
    public static boolean isExternalStorageEmulated() { return true; }
    public static boolean isExternalStorageRemovable() { return false; }
    public static File getExternalStoragePublicDirectory(String type) { return new File(getExternalStorageDirectory(), type); }
    public static final String DIRECTORY_DOWNLOADS = "Download", DIRECTORY_PICTURES = "Pictures",
            DIRECTORY_MOVIES = "Movies", DIRECTORY_MUSIC = "Music", DIRECTORY_DCIM = "DCIM";
    /** AOSP: /data/user/<userId>/<package> (credential-encrypted). */
    public static java.io.File getDataUserCePackageDirectory(String volumeUuid, int userId, String packageName) {
        return new java.io.File(getDataDirectory(), "user/" + userId + "/" + packageName);
    }
    public static java.io.File getDataUserDePackageDirectory(String volumeUuid, int userId, String packageName) {
        return new java.io.File(getDataDirectory(), "user_de/" + userId + "/" + packageName);
    }
    public static String getDataDirectoryPath(String volumeUuid) {
        return getDataDirectory().getAbsolutePath();
    }
    public static final String DIR_USER_CE = "user";
    public static final String DIR_USER_DE = "user_de";
    public static java.io.File getDataSystemDirectory() {
        return new java.io.File(getDataDirectory(), "system");
    }
}
