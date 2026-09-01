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
    /** AOSP: valid ext4 filename component. */
    public static boolean isValidExtFilename(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) return false;
        return name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }
    // AOSP FileUtils mode translation (ParcelFileDescriptor.open/parseMode).
    public static int translateModeStringToPosix(String mode) {
        for (int i = 0; i < mode.length(); i++) {
            switch (mode.charAt(i)) {
                case 'r': case 'w': case 't': case 'a': break;
                default: throw new IllegalArgumentException("Bad mode: " + mode);
            }
        }
        int res;
        if (mode.startsWith("rw")) res = android.system.OsConstants.O_RDWR | android.system.OsConstants.O_CREAT;
        else if (mode.startsWith("w")) res = android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_CREAT;
        else if (mode.startsWith("r")) res = android.system.OsConstants.O_RDONLY;
        else throw new IllegalArgumentException("Bad mode: " + mode);
        if (mode.indexOf('t') != -1) res |= android.system.OsConstants.O_TRUNC;
        if (mode.indexOf('a') != -1) res |= android.system.OsConstants.O_APPEND;
        return res;
    }
    public static int translateModePfdToPosix(int mode) {
        int res;
        if ((mode & ParcelFileDescriptor.MODE_READ_WRITE) == ParcelFileDescriptor.MODE_READ_WRITE) res = android.system.OsConstants.O_RDWR;
        else if ((mode & ParcelFileDescriptor.MODE_WRITE_ONLY) == ParcelFileDescriptor.MODE_WRITE_ONLY) res = android.system.OsConstants.O_WRONLY;
        else if ((mode & ParcelFileDescriptor.MODE_READ_ONLY) == ParcelFileDescriptor.MODE_READ_ONLY) res = android.system.OsConstants.O_RDONLY;
        else throw new IllegalArgumentException("Bad mode: " + mode);
        if ((mode & ParcelFileDescriptor.MODE_CREATE) == ParcelFileDescriptor.MODE_CREATE) res |= android.system.OsConstants.O_CREAT;
        if ((mode & ParcelFileDescriptor.MODE_TRUNCATE) == ParcelFileDescriptor.MODE_TRUNCATE) res |= android.system.OsConstants.O_TRUNC;
        if ((mode & ParcelFileDescriptor.MODE_APPEND) == ParcelFileDescriptor.MODE_APPEND) res |= android.system.OsConstants.O_APPEND;
        return res;
    }
    public static int translateModePosixToPfd(int mode) {
        int res;
        if ((mode & android.system.OsConstants.O_ACCMODE) == android.system.OsConstants.O_RDWR) res = ParcelFileDescriptor.MODE_READ_WRITE;
        else if ((mode & android.system.OsConstants.O_ACCMODE) == android.system.OsConstants.O_WRONLY) res = ParcelFileDescriptor.MODE_WRITE_ONLY;
        else if ((mode & android.system.OsConstants.O_ACCMODE) == android.system.OsConstants.O_RDONLY) res = ParcelFileDescriptor.MODE_READ_ONLY;
        else throw new IllegalArgumentException("Bad mode: " + mode);
        if ((mode & android.system.OsConstants.O_CREAT) == android.system.OsConstants.O_CREAT) res |= ParcelFileDescriptor.MODE_CREATE;
        if ((mode & android.system.OsConstants.O_TRUNC) == android.system.OsConstants.O_TRUNC) res |= ParcelFileDescriptor.MODE_TRUNCATE;
        if ((mode & android.system.OsConstants.O_APPEND) == android.system.OsConstants.O_APPEND) res |= ParcelFileDescriptor.MODE_APPEND;
        return res;
    }
}
