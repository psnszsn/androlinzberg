/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class FileUtils {
    public static final int S_IRWXU = 0700, S_IRUSR = 0400, S_IWUSR = 0200, S_IXUSR = 0100;
    public static final int S_IRWXG = 0070, S_IRGRP = 0040, S_IWGRP = 0020, S_IXGRP = 0010;
    public static final int S_IRWXO = 0007, S_IROTH = 0004, S_IWOTH = 0002, S_IXOTH = 0001;
    public static int setPermissions(String path, int mode, int uid, int gid) { return 0; }
    public static int setPermissions(java.io.File path, int mode, int uid, int gid) { return 0; }
    public static void closeQuietly(AutoCloseable closeable) {
        try { if (closeable != null) closeable.close(); } catch (Exception ignored) {}
    }
    public static boolean sync(java.io.FileOutputStream stream) { return true; }
    public static long copy(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); total += r; }
        return total;
    }
}
