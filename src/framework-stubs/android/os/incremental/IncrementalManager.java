/* artzig stub: no incremental installs on this host. */
package android.os.incremental;
public class IncrementalManager {
    public static boolean isIncrementalPath(String path) { return false; }
    public static boolean isIncrementalFileFd(java.io.FileDescriptor fd) { return false; }
    public static byte[] unsafeGetFileSignature(String path) { return null; }
}
