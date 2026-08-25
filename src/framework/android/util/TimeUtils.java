/* artzig shim: android.util.TimeUtils — duration formatting subset (real one needs com.android.i18n timezone data). */
package android.util;
import java.io.PrintWriter;
public class TimeUtils {
    public static final int HUNDRED_DAY_FIELD_LEN = 19;
    public static final long NANOS_PER_MS = 1000000;
    private TimeUtils() {}
    public static void formatDuration(long duration, StringBuilder builder) {
        if (duration < 0) { builder.append('-'); duration = -duration; }
        builder.append('+').append(duration).append("ms");
    }
    public static void formatDuration(long duration, PrintWriter pw) {
        StringBuilder sb = new StringBuilder();
        formatDuration(duration, sb);
        pw.print(sb);
    }
    public static void formatDuration(long duration, PrintWriter pw, int fieldLen) { formatDuration(duration, pw); }
    public static void formatDuration(long time, long now, PrintWriter pw) { formatDuration(time - now, pw); }
    public static String formatDuration(long duration) {
        StringBuilder sb = new StringBuilder();
        formatDuration(duration, sb);
        return sb.toString();
    }
    public static String formatUptime(long time) { return formatDuration(time); }
    public static String logTimeOfDay(long millis) { return String.valueOf(millis); }
    public static void formatForLogging(long millis, StringBuilder sb) { sb.append(millis); }
    public static String formatTimeForLogging(long millis) { return String.valueOf(millis); }
    public static void dumpTime(PrintWriter pw, long time) { pw.print(time); }
    public static void dumpTimeWithDelta(PrintWriter pw, long time, long now) { pw.print(time); }
}
