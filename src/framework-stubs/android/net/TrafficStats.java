/* artzig stub: Linux networking is used directly, without Android kernel accounting tags. */
package android.net;

import java.net.DatagramSocket;
import java.net.Socket;

public final class TrafficStats {
    public static final long UNSUPPORTED = -1;
    private static final ThreadLocal<Integer> TAG = new ThreadLocal<>();
    private TrafficStats() {}
    /** AOSP TrafficStats.attachSocketTagger; accounting is owned by the host seam. */
    public static void attachSocketTagger() { dev.artzig.HostedTrafficSocketTagger.install(); }


    public static void setThreadStatsTag(int tag) { TAG.set(tag); }
    public static int getThreadStatsTag() { Integer tag = TAG.get(); return tag != null ? tag : -1; }
    public static void clearThreadStatsTag() { TAG.remove(); }
    public static void tagSocket(Socket socket) throws java.net.SocketException { dalvik.system.SocketTagger.get().tag(socket); }
    public static void untagSocket(Socket socket) throws java.net.SocketException { dalvik.system.SocketTagger.get().untag(socket); }
    public static void tagDatagramSocket(DatagramSocket socket) throws java.net.SocketException { dalvik.system.SocketTagger.get().tag(socket); }
    public static void untagDatagramSocket(DatagramSocket socket) throws java.net.SocketException { dalvik.system.SocketTagger.get().untag(socket); }
    public static long getUidRxBytes(int uid) { return UNSUPPORTED; }
    public static long getUidTxBytes(int uid) { return UNSUPPORTED; }
    public static long getTotalRxBytes() { return UNSUPPORTED; }
    public static long getTotalTxBytes() { return UNSUPPORTED; }
}
