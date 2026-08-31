/* artzig stub: installer sessions are not hosted; only the constants and
 * annotation InstallSourceInfo references. */
package android.content.pm;
public class PackageInstaller {
    public static final int PACKAGE_SOURCE_UNSPECIFIED = 0;
    public static final int PACKAGE_SOURCE_OTHER = 1;
    public static final int PACKAGE_SOURCE_STORE = 2;
    public static final int PACKAGE_SOURCE_LOCAL_FILE = 3;
    public static final int PACKAGE_SOURCE_DOWNLOADED_FILE = 4;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PackageSourceType {}
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    /** Referenced by PackageManager javadoc and status mapping only. */
    public static class SessionParams {
        private SessionParams() {}
    }
    private PackageInstaller() {}
    /** ApplicationPackageManager constructs this; sessions are not hosted, so
     * every session API is absent and callers fail at the missing methods. */
    public PackageInstaller(IPackageInstaller installer, String installerPackageName,
            String installerAttributionTag, int userId) {}
}
