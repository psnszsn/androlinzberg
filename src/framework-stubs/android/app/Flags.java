/* artzig stub (aconfig flags: all off). */
package android.app;
public final class Flags {
    public static final String FLAG_MODES_API = "android.app.modes_api";
    public static final String FLAG_API_RICH_ONGOING = "android.app.api_rich_ongoing";
    public static boolean systemTermsOfAddressEnabled() { return false; }
    // PropertyInvalidatedCache behavior flags: sysprop nonces, no uid
    // isolation, no null caching, no shared memory.
    public static boolean picUsesSharedMemory() { return false; }
    public static boolean picIsolateCacheByUid() { return false; }
    public static boolean picIsolatedCacheStatistics() { return false; }
    public static boolean picCacheNulls() { return false; }
    public static boolean picSeparatePermissionNotifications() { return false; }
    public static boolean enforcePicTestmodeProtocol() { return false; }
    private Flags() {}
}
