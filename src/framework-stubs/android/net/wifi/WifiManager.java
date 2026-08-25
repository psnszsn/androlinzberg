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
}
