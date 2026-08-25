/* artzig stub: Linux networking is used directly, without Android kernel accounting tags. */
package android.net;

import java.net.DatagramSocket;
import java.net.Socket;

public final class TrafficStats {
    public static final long UNSUPPORTED = -1;
    private static final ThreadLocal<Integer> TAG = new ThreadLocal<>();
    private TrafficStats() {}

    public static void setThreadStatsTag(int tag) { TAG.set(tag); }
    public static int getThreadStatsTag() { Integer tag = TAG.get(); return tag != null ? tag : -1; }
    public static void clearThreadStatsTag() { TAG.remove(); }
    public static void tagSocket(Socket socket) {}
    public static void untagSocket(Socket socket) {}
    public static void tagDatagramSocket(DatagramSocket socket) {}
    public static void untagDatagramSocket(DatagramSocket socket) {}
    public static long getUidRxBytes(int uid) { return 0; }
    public static long getUidTxBytes(int uid) { return 0; }
    public static long getTotalRxBytes() { return 0; }
    public static long getTotalTxBytes() { return 0; }
}
