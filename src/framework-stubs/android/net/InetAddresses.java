/* artzig stub of AOSP android.net.InetAddresses: the Connectivity module tree is not
 * vendored. The AOSP class delegates to libcore's InetAddressUtils, which the host
 * runtime provides; this stub keeps that delegation. */
package android.net;

import java.net.InetAddress;

public class InetAddresses {
    private InetAddresses() {}

    public static boolean isNumericAddress(String address) {
        return libcore.net.InetAddressUtils.isNumericAddress(address);
    }

    public static InetAddress parseNumericAddress(String address) {
        return libcore.net.InetAddressUtils.parseNumericAddress(address);
    }
}
