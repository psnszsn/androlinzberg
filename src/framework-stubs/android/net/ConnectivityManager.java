/* artzig stub: pretend to be online via the default network. */
package android.net;
public class ConnectivityManager {
    public NetworkInfo getActiveNetworkInfo() { return new NetworkInfo(); }
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
