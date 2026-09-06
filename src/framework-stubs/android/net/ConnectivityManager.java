/* artzig stub: pretend to be online via the default network. */
package android.net;
public class ConnectivityManager {
    private static ConnectivityManager sInstance;
    public static final int TYPE_NONE = -1;
    public static final int TYPE_WIFI = 1;
    // Pinned AOSP network type constants consumed by stock statistics templates.
    public static final int TYPE_MOBILE = 0;
    public static final int TYPE_MOBILE_MMS = 2;
    public static final int TYPE_MOBILE_SUPL = 3;
    public static final int TYPE_MOBILE_DUN = 4;
    public static final int TYPE_MOBILE_HIPRI = 5;
    public static final int TYPE_WIMAX = 6;
    public static final int TYPE_BLUETOOTH = 7;
    public static final int TYPE_DUMMY = 8;
    public static final int TYPE_ETHERNET = 9;
    public static final int TYPE_MOBILE_FOTA = 10;
    public static final int TYPE_MOBILE_IMS = 11;
    public static final int TYPE_MOBILE_CBS = 12;
    public static final int TYPE_WIFI_P2P = 13;
    public static final int TYPE_MOBILE_IA = 14;
    public static final int TYPE_MOBILE_EMERGENCY = 15;
    public static final int TYPE_PROXY = 16;
    public static final int TYPE_VPN = 17;
    public static final int TYPE_TEST = 18;
    /** AOSP ConnectivityManager.NETID_UNSET; the hosted process has no selected Android netId. */
    public static final int NETID_UNSET = 0;
    public static final int REQUEST_ID_UNSET = 0;
    public ConnectivityManager() { sInstance = this; }
    /** Pinned Network.openConnection helper; the hosted network has no configured proxy. */
    static ConnectivityManager getInstanceOrNull() { return sInstance; }
    /** Pinned Network.openConnection helper; the hosted network always routes directly. */
    public ProxyInfo getProxyForNetwork(Network network) { return null; }
    public static boolean isNetworkTypeValid(int networkType) {
        return networkType >= 0 && networkType <= 17;
    }
    public NetworkInfo getActiveNetworkInfo() { return new NetworkInfo(TYPE_WIFI, 0, "WIFI", ""); }
    public Network getActiveNetwork() { return new Network(100); }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return new NetworkCapabilities(); }
    public LinkProperties getLinkProperties(Network network) { return new LinkProperties(); }
    public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {
        if (callback != null) callback.onAvailable(getActiveNetwork());
    }
    public void registerDefaultNetworkCallback(NetworkCallback callback) {
        if (callback != null) callback.onAvailable(getActiveNetwork());
    }
    public void unregisterNetworkCallback(NetworkCallback callback) {}
    public boolean isActiveNetworkMetered() { return false; }
    public static class NetworkCallback {
        public void onAvailable(Network network) {}
        public void onLost(Network network) {}
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {}
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {}
    }
    public static final String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";
}
