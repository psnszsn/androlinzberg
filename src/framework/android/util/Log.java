/* artzig shim: android.util.Log — stderr logging, no liblog. */
package android.util;
import java.io.PrintWriter;
import java.io.StringWriter;
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    public static final int LOG_ID_MAIN = 0, LOG_ID_RADIO = 1, LOG_ID_EVENTS = 2,
            LOG_ID_SYSTEM = 3, LOG_ID_CRASH = 4;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface Level {}
    public interface TerribleFailureHandler { void onTerribleFailure(String tag, TerribleFailure what, boolean system); }
    public static class TerribleFailure extends Exception {
        public TerribleFailure(String msg, Throwable cause) { super(msg, cause); }
    }
    public static int println_native(int bufID, int priority, String tag, String msg) {
        return println(priority, tag, msg);
    }
    public static int wtf(int logId, String tag, String msg, Throwable tr, boolean localStack, boolean system) {
        return println(ASSERT, tag, msg + (tr != null ? '\n' + getStackTraceString(tr) : ""));
    }
    public static final String LIBANDROID_LOADING_HOOK = "android.util.Log$NoPreloadHolder";
    public static int wtfQuiet(int logId, String tag, String msg, boolean system) { return println(ASSERT, tag, msg); }
    public static int logToRadioBuffer(int priority, String tag, String msg) { return println(priority, tag, msg); }
    private Log() {}
    public static int v(String tag, String msg) { return println(VERBOSE, tag, msg); }
    public static int v(String tag, String msg, Throwable tr) { return println(VERBOSE, tag, msg + '\n' + getStackTraceString(tr)); }
    public static int d(String tag, String msg) { return println(DEBUG, tag, msg); }
    public static int d(String tag, String msg, Throwable tr) { return println(DEBUG, tag, msg + '\n' + getStackTraceString(tr)); }
    public static int i(String tag, String msg) { return println(INFO, tag, msg); }
    public static int i(String tag, String msg, Throwable tr) { return println(INFO, tag, msg + '\n' + getStackTraceString(tr)); }
    public static int w(String tag, String msg) { return println(WARN, tag, msg); }
    public static int w(String tag, String msg, Throwable tr) { return println(WARN, tag, msg + '\n' + getStackTraceString(tr)); }
    public static int w(String tag, Throwable tr) { return println(WARN, tag, getStackTraceString(tr)); }
    public static int e(String tag, String msg) { return println(ERROR, tag, msg); }
    public static int e(String tag, String msg, Throwable tr) { return println(ERROR, tag, msg + '\n' + getStackTraceString(tr)); }
    public static int wtf(String tag, String msg) { return println(ASSERT, tag, msg); }
    public static int wtf(String tag, Throwable tr) { return println(ASSERT, tag, getStackTraceString(tr)); }
    public static int wtf(String tag, String msg, Throwable tr) { return println(ASSERT, tag, msg + '\n' + getStackTraceString(tr)); }
    public static boolean isLoggable(String tag, int level) { return level >= INFO; }
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
    public static int println(int priority, String tag, String msg) {
        try {
            ArtzigTestDiagnostics.recordLog(priority, tag, msg);
        } catch (Throwable ignored) {
            // Diagnostics must never change application logging behavior.
        }
        System.err.println("VDIWEA".charAt(Math.max(0, Math.min(priority - VERBOSE, 5))) + "/" + tag + ": " + msg);
        return 0;
    }
}
