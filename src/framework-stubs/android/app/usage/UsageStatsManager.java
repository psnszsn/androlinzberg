/* artzig stub: a foreground desktop-hosted APK is never app-standby restricted. */
package android.app.usage;

public class UsageStatsManager {
    public static final int STANDBY_BUCKET_EXEMPTED = 5;
    public static final int STANDBY_BUCKET_ACTIVE = 10;
    public static final int STANDBY_BUCKET_WORKING_SET = 20;
    public static final int STANDBY_BUCKET_FREQUENT = 30;
    public static final int STANDBY_BUCKET_RARE = 40;
    public static final int STANDBY_BUCKET_RESTRICTED = 45;
    public int getAppStandbyBucket() { return STANDBY_BUCKET_ACTIVE; }
    public boolean isAppInactive(String packageName) { return false; }
}
