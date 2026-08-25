/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class Debug {
    public static String getCallers(final int depth) { return ""; }
    public static String getCaller() { return ""; }
    public static long threadCpuTimeNanos() { return System.nanoTime(); }
    public static void startMethodTracing(String traceName) {}
    public static void stopMethodTracing() {}
    public static boolean isDebuggerConnected() { return false; }
    public static long countInstancesOfClass(Class cls) { return 0; }
    public static boolean waitingForDebugger() { return false; }
    public static long getNativeHeapSize() { return 0; }
    public static long getNativeHeapAllocatedSize() { return 0; }
    public static void getMemoryInfo(MemoryInfo memoryInfo) {}
    public static class MemoryInfo {
        public int dalvikPss, nativePss, otherPss;
        public int getTotalPss() { return 0; }
    }
}
