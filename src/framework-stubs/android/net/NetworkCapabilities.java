/* artzig stub. */
package android.net;
public class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1, TRANSPORT_CELLULAR = 0, TRANSPORT_ETHERNET = 3;
    public static final int NET_CAPABILITY_INTERNET = 12, NET_CAPABILITY_NOT_METERED = 11, NET_CAPABILITY_VALIDATED = 16,
            NET_CAPABILITY_NOT_RESTRICTED = 13, NET_CAPABILITY_NOT_VPN = 15, NET_CAPABILITY_NOT_ROAMING = 18;
    public boolean hasTransport(int transportType) { return transportType == TRANSPORT_WIFI || transportType == TRANSPORT_ETHERNET; }
    public boolean hasCapability(int capability) { return true; }
}
