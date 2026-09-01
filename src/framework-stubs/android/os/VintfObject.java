/* artzig stub of AOSP android.os.VintfObject: its natives query libvintf
 * (vendor interface manifests), which has no meaning on this host. Build
 * .isBuildConsistent links against verifyBuildAtBoot; the host reports a
 * consistent (non-Treble) build. */
package android.os;
import java.util.Collections;
import java.util.Map;
public class VintfObject {
    public static String[] report() { return new String[0]; }
    public static int verifyBuildAtBoot() { return 0; }
    public static String[] getHalNamesAndVersions() { return new String[0]; }
    public static String getSepolicyVersion() { return ""; }
    public static String getPlatformSepolicyVersion() { return ""; }
    public static Map<String, String[]> getVndkSnapshots() { return Collections.emptyMap(); }
    public static Long getTargetFrameworkCompatibilityMatrixVersion() { return null; }
}
