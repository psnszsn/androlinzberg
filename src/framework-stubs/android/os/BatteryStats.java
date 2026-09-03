/* artzig stub of AOSP android.os.BatteryStats: the real abstract class is the battery-stats
 * history model (telephony signal strengths, Bluetooth and Wi-Fi energy info from unvendored
 * module trees). BatteryManager needs only the service name. Pending the battery family
 * (managers over hosted services). */
package android.os;

public abstract class BatteryStats {
    /** The service name the battery stats binder is published under. */
    public static final String SERVICE_NAME = "batterystats";
}
