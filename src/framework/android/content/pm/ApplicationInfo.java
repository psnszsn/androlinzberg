/* artzig shim: android.content.pm.ApplicationInfo — minimal fields. */
package android.content.pm;
public class ApplicationInfo extends PackageItemInfo {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface Category {}
    public static final int CATEGORY_UNDEFINED = -1, CATEGORY_GAME = 0, CATEGORY_AUDIO = 1,
            CATEGORY_VIDEO = 2, CATEGORY_IMAGE = 3, CATEGORY_SOCIAL = 4, CATEGORY_NEWS = 5,
            CATEGORY_MAPS = 6, CATEGORY_PRODUCTIVITY = 7, CATEGORY_ACCESSIBILITY = 8;
    public int category = CATEGORY_UNDEFINED;
    public int targetSdkVersion = 35;
    public boolean enabled = true;
    public int minSdkVersion = 23;
    public int flags;
    public String sourceDir, dataDir, nativeLibraryDir, publicSourceDir;
    public String[] splitSourceDirs, splitPublicSourceDirs, resourceDirs, overlayPaths, sharedLibraryFiles;
    public String[] splitNames;
    public String[] getSplitCodePaths() { return splitSourceDirs; }
    public boolean hasRtlSupport() { return true; }
    public boolean isChangeEnabled(long changeId) { return true; }
    public boolean isOnBackInvokedCallbackEnabled() { return false; }
    public String getBaseResourcePath() { return sourceDir; }
    public int requiresSmallestWidthDp, compatibleWidthLimitDp, largestWidthLimitDp;
    public int uid;
    public float maxAspectRatio;
    public static final int FLAG_DEBUGGABLE = 1 << 1;
    public static final int FLAG_SUPPORTS_SMALL_SCREENS = 1 << 9, FLAG_SUPPORTS_NORMAL_SCREENS = 1 << 10,
            FLAG_SUPPORTS_LARGE_SCREENS = 1 << 11, FLAG_SUPPORTS_XLARGE_SCREENS = 1 << 19,
            FLAG_RESIZEABLE_FOR_SCREENS = 1 << 12, FLAG_SUPPORTS_SCREEN_DENSITIES = 1 << 13,
            FLAG_HAS_CODE = 1 << 2, FLAG_SYSTEM = 1 << 0;
}
