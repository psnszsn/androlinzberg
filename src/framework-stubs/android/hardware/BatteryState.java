/* artzig stub (auto-generated placeholder; members added on demand). */
package android.hardware;
public abstract class BatteryState {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface BatteryStatus {}
    public static final int STATUS_UNKNOWN = 1, STATUS_CHARGING = 2, STATUS_DISCHARGING = 3,
            STATUS_NOT_CHARGING = 4, STATUS_FULL = 5;
    public abstract boolean isPresent();
    public abstract int getStatus();
    public abstract float getCapacity();
}
