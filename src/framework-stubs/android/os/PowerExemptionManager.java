/* artzig stub of AOSP android.os.PowerExemptionManager: the real class drives the
 * device-idle controller (no such service on this host). BroadcastOptions links
 * against the reason codes and temp-allowlist types (AOSP values). */
package android.os;
public class PowerExemptionManager {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ReasonCode {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface TempAllowListType {}
    public static final int TEMPORARY_ALLOW_LIST_TYPE_NONE = -1;
    public static final int TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED = 0;
    public static final int TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED = 1;
    public static final int REASON_UNKNOWN = 0;
    public static final int REASON_PUSH_MESSAGING = 101;
    public static final int REASON_PUSH_MESSAGING_OVER_QUOTA = 102;
    private PowerExemptionManager() {}
}
