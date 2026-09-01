/* artzig host seam: android.util.StatsLog (packages/modules/StatsD
 * framework/java/android/util/StatsLog.java, android-16.0.0_r1). The hosted
 * runtime has no statsd daemon or libstats_jni, so the native writeImpl sink is
 * a no-op — exactly AOSP's own host behavior (loadNativeLibrary$ravenwood /
 * writeImpl$ravenwood on Ravenwood). The annotation-id constants and the
 * write(StatsEvent) contract (including the release() call) are preserved for
 * the generated FrameworkStatsLog; the IStatsd subscription/experiment APIs are
 * omitted (statsd-only). android.util.StatsEvent is the reused module source. */
package android.util;

import android.annotation.NonNull;

public final class StatsLog {
    private StatsLog() {}

    public static final byte ANNOTATION_ID_IS_UID = 1;
    public static final byte ANNOTATION_ID_TRUNCATE_TIMESTAMP = 2;
    public static final byte ANNOTATION_ID_PRIMARY_FIELD = 3;
    public static final byte ANNOTATION_ID_EXCLUSIVE_STATE = 4;
    public static final byte ANNOTATION_ID_PRIMARY_FIELD_FIRST_UID = 5;
    public static final byte ANNOTATION_ID_DEFAULT_STATE = 6;
    public static final byte ANNOTATION_ID_TRIGGER_STATE_RESET = 7;
    public static final byte ANNOTATION_ID_STATE_NESTED = 8;
    public static final byte ANNOTATION_ID_RESTRICTION_CATEGORY = 9;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_PERIPHERAL_DEVICE_INFO = 10;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_APP_USAGE = 11;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_APP_ACTIVITY = 12;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_HEALTH_CONNECT = 13;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_ACCESSIBILITY = 14;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_SYSTEM_SEARCH = 15;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_USER_ENGAGEMENT = 16;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_AMBIENT_SENSING = 17;
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_DEMOGRAPHIC_CLASSIFICATION = 18;

    /** Write an encoded StatsEvent; the host sink is a no-op. Releases the event. */
    public static void write(@NonNull final StatsEvent statsEvent) {
        writeImpl(statsEvent.getBytes(), statsEvent.getNumBytes(), statsEvent.getAtomId());
        statsEvent.release();
    }

    /** Raw-buffer variant of the AOSP API; host no-op. */
    public static void writeRaw(@NonNull byte[] buffer, int size) {
        writeImpl(buffer, size, 0);
    }

    private static void writeImpl(@NonNull byte[] buffer, int size, int atomId) {
        // No statsd on the host (AOSP writeImpl$ravenwood behavior).
    }
}
