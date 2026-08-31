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
    private PackageInstaller() {}
}
