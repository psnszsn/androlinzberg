/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class Debug {
    public static String getCallers(final int depth) { return ""; }
    public static String getCallers(final int start, final int depth) { return ""; }
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
    public static class MemoryInfo implements Parcelable {
        public int dalvikPss, nativePss, otherPss;
        public int getTotalPss() { return dalvikPss + nativePss + otherPss; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(dalvikPss); dest.writeInt(nativePss); dest.writeInt(otherPss);
        }
        public void readFromParcel(Parcel source) {
            dalvikPss = source.readInt(); nativePss = source.readInt(); otherPss = source.readInt();
        }
        public static final Creator<MemoryInfo> CREATOR = new Creator<MemoryInfo>() {
            @Override public MemoryInfo createFromParcel(Parcel source) { MemoryInfo m = new MemoryInfo(); m.readFromParcel(source); return m; }
            @Override public MemoryInfo[] newArray(int size) { return new MemoryInfo[size]; }
        };
    }
}
