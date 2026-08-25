/* artzig shim: android.os.SystemClock. */
package android.os;
public final class SystemClock {
    private SystemClock() {}
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000; }
    public static long uptimeNanos() { return System.nanoTime(); }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000; }
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long currentThreadTimeMillis() { return System.nanoTime() / 1_000_000; }
    public static boolean setCurrentTimeMillis(long millis) { return false; }
    public static void sleep(long ms) {
        long end = uptimeMillis() + ms;
        for (;;) {
            long left = end - uptimeMillis();
            if (left <= 0) return;
            try { Thread.sleep(left); } catch (InterruptedException e) {}
        }
    }
}
