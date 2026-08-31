/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class PowerManager {
    public static final float BRIGHTNESS_INVALID_FLOAT = Float.NaN;
    public static final int BRIGHTNESS_INVALID = -1, BRIGHTNESS_ON = 255, BRIGHTNESS_OFF = 0;
    public static final float BRIGHTNESS_MAX = 1.0f, BRIGHTNESS_MIN = 0.0f;
    public static final int PARTIAL_WAKE_LOCK = 1;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface GoToSleepReason {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface WakeReason {}
    public static final int GO_TO_SLEEP_REASON_MIN = 0, GO_TO_SLEEP_REASON_APPLICATION = 0,
            GO_TO_SLEEP_REASON_DEVICE_ADMIN = 1, GO_TO_SLEEP_REASON_TIMEOUT = 2,
            GO_TO_SLEEP_REASON_LID_SWITCH = 3, GO_TO_SLEEP_REASON_POWER_BUTTON = 4,
            GO_TO_SLEEP_REASON_HDMI = 5, GO_TO_SLEEP_REASON_SLEEP_BUTTON = 6,
            GO_TO_SLEEP_REASON_ACCESSIBILITY = 7, GO_TO_SLEEP_REASON_FORCE_SUSPEND = 8,
            GO_TO_SLEEP_REASON_INATTENTIVE = 9, GO_TO_SLEEP_REASON_QUIESCENT = 10,
            GO_TO_SLEEP_REASON_MAX = 10;
    public static final int WAKE_REASON_UNKNOWN = 0, WAKE_REASON_POWER_BUTTON = 1,
            WAKE_REASON_PLUGGED_IN = 3, WAKE_REASON_GESTURE = 4, WAKE_REASON_CAMERA_LAUNCH = 5,
            WAKE_REASON_WAKE_KEY = 6, WAKE_REASON_WAKE_MOTION = 7, WAKE_REASON_HDMI = 8,
            WAKE_REASON_LID = 9, WAKE_REASON_APPLICATION = 2, WAKE_REASON_DISPLAY_GROUP_ADDED = 10,
            WAKE_REASON_TAP = 11, WAKE_REASON_DISPLAY_GROUP_TURNED_ON = 12,
            WAKE_REASON_UNFOLD_DEVICE = 13, WAKE_REASON_DREAM_FINISHED = 14,
            WAKE_REASON_TILT = 15, WAKE_REASON_BIOMETRIC = 16, WAKE_REASON_LIFT = 17;
    public boolean isInteractive() { return true; }
    public boolean isPowerSaveMode() { return false; }
    public boolean isDeviceIdleMode() { return false; }
    public boolean isIgnoringBatteryOptimizations(String packageName) { return true; }
    public boolean isWakeLockLevelSupported(int level) { return true; }
    public WakeLock newWakeLock(int levelAndFlags, String tag) { return new WakeLock(); }
    public static class WakeLock {
        public void acquire() {}
        public void acquire(long timeout) {}
        public void release() {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean value) {}
    }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface AutoPowerSaveModeTriggers {}
}
