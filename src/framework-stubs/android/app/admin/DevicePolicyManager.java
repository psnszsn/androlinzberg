/* artzig stub: no device policy service in a hosted session. */
package android.app.admin;
public class DevicePolicyManager {
    private final DevicePolicyResourcesManager mResources = new DevicePolicyResourcesManager();
    public DevicePolicyResourcesManager getResources() { return mResources; }
    // Password policy ids (KeyguardManager, LockPatternUtils and PasswordMetrics cite them).
    public static final int PASSWORD_QUALITY_UNSPECIFIED = 0;
    public static final int PASSWORD_QUALITY_BIOMETRIC_WEAK = 0x8000;
    public static final int PASSWORD_QUALITY_SOMETHING = 0x10000;
    public static final int PASSWORD_QUALITY_NUMERIC = 0x20000;
    public static final int PASSWORD_QUALITY_NUMERIC_COMPLEX = 0x30000;
    public static final int PASSWORD_QUALITY_ALPHABETIC = 0x40000;
    public static final int PASSWORD_QUALITY_ALPHANUMERIC = 0x50000;
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;
    public static final int PASSWORD_QUALITY_MANAGED = 0x80000;
    public static final int PASSWORD_COMPLEXITY_NONE = 0;
    public static final int PASSWORD_COMPLEXITY_LOW = 0x10000;
    public static final int PASSWORD_COMPLEXITY_MEDIUM = 0x30000;
    public static final int PASSWORD_COMPLEXITY_HIGH = 0x50000;
    public static final int MAX_PASSWORD_LENGTH = 16;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PasswordComplexity {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PasswordQuality {}
    /** No device or profile owner exists on this host. */
    public boolean isDeviceOwnerApp(String packageName) { return false; }
    public boolean isProfileOwnerApp(String packageName) { return false; }
    public int getPasswordComplexity() { return PASSWORD_COMPLEXITY_NONE; }
    public int getPasswordQuality(android.content.ComponentName admin) { return PASSWORD_QUALITY_UNSPECIFIED; }
    public int getPasswordQuality(android.content.ComponentName admin, int userHandle) { return PASSWORD_QUALITY_UNSPECIFIED; }
    public void reportFailedPasswordAttempt(int userHandle) {}
    // Password policy queries for a device with no admins (LockPatternUtils, PasswordMetrics).
    public PasswordMetrics getPasswordMinimumMetrics(int userHandle, boolean deviceWideOnly) { return new PasswordMetrics(PASSWORD_QUALITY_UNSPECIFIED); }
    public int getPasswordMinimumLength(android.content.ComponentName admin, int userHandle) { return 0; }
    public int getPasswordMaximumLength(int quality) { return MAX_PASSWORD_LENGTH; }
    public int getPasswordHistoryLength(android.content.ComponentName admin, int userHandle) { return 0; }
    public int getMaximumFailedPasswordsForWipe(android.content.ComponentName admin, int userHandle) { return 0; }
    public int getCurrentFailedPasswordAttempts(int userHandle) { return 0; }
    public int getAggregatedPasswordComplexityForUser(int userId, boolean deviceWideOnly) { return PASSWORD_COMPLEXITY_NONE; }
    public void reportSuccessfulPasswordAttempt(int userHandle) {}
    /** AOSP forwards a managed-profile contact lookup to the device policy service; no managed profile exists here. */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId, boolean isContactIdIgnored,
            long directoryId, android.content.Intent originalIntent) {}
}
