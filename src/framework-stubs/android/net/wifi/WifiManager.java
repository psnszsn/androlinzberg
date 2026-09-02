/* artzig stub: wifi locks are no-ops (host network is always on). */
package android.net.wifi;
public class WifiManager {
    public static final int WIFI_MODE_FULL = 1;
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    public static final int WIFI_MODE_FULL_LOW_LATENCY = 4;
    public class WifiLock {
        public void acquire() {}
        public void release() {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean refCounted) {}
    }
    public class MulticastLock {
        public void acquire() {}
        public void release() {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean refCounted) {}
    }
    public WifiLock createWifiLock(int lockType, String tag) { return new WifiLock(); }
    public WifiLock createWifiLock(String tag) { return new WifiLock(); }
    public MulticastLock createMulticastLock(String tag) { return new MulticastLock(); }
    public boolean isWifiEnabled() { return true; }
    public int getWifiState() { return 3; }
    /** Broadcast actions BroadcastStickyCache keys on (AOSP values). */
    public static final String ACTION_WIFI_SCAN_AVAILABILITY_CHANGED = "android.net.wifi.action.WIFI_SCAN_AVAILABILITY_CHANGED";
    public static final String NETWORK_STATE_CHANGED_ACTION = "android.net.wifi.STATE_CHANGE";
    public static final String SUPPLICANT_STATE_CHANGED_ACTION = "android.net.wifi.supplicant.STATE_CHANGE";
    public static final String WIFI_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_STATE_CHANGED";
}
