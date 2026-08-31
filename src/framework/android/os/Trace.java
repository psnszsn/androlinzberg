/* artzig shim: android.os.Trace — no-op. */
package android.os;
public final class Trace {
    public static final long TRACE_TAG_NEVER = 0, TRACE_TAG_ALWAYS = 1 << 0, TRACE_TAG_GRAPHICS = 1 << 1,
            TRACE_TAG_INPUT = 1 << 2, TRACE_TAG_VIEW = 1 << 3, TRACE_TAG_APP = 1 << 12,
            TRACE_TAG_RESOURCES = 1 << 13, TRACE_TAG_DALVIK = 1 << 14, TRACE_TAG_ACTIVITY_MANAGER = 1 << 6,
            TRACE_TAG_WINDOW_MANAGER = 1 << 5, TRACE_TAG_RRO = 1L << 26, TRACE_TAG_AIDL = 1L << 24,
            TRACE_TAG_DATABASE = 1 << 20, TRACE_TAG_PACKAGE_MANAGER = 1 << 21, TRACE_TAG_POWER = 1 << 17, TRACE_TAG_SYSTEM_SERVER = 1 << 19;
    public static final int MAX_SECTION_NAME_LEN = 127;
    private Trace() {}
    public static boolean isEnabled() { return false; }
    public static boolean isTagEnabled(long tag) { return false; }
    public static void traceBegin(long tag, String name) {}
    public static void traceEnd(long tag) {}
    public static void asyncTraceBegin(long tag, String name, int cookie) {}
    public static void asyncTraceEnd(long tag, String name, int cookie) {}
    public static void asyncTraceForTrackBegin(long tag, String track, String name, int cookie) {}
    public static void asyncTraceForTrackEnd(long tag, String track, int cookie) {}
    public static void instant(long tag, String name) {}
    public static void instantForTrack(long tag, String track, String name) {}
    public static void traceCounter(long tag, String name, int value) {}
    public static void setCounter(String name, long value) {}
    public static void beginSection(String name) {}
    public static void endSection() {}
    public static void beginAsyncSection(String name, int cookie) {}
    public static void endAsyncSection(String name, int cookie) {}
}
