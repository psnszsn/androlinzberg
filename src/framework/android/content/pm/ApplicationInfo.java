/* artzig shim: android.content.pm.ApplicationInfo — minimal fields. */
package android.content.pm;
public class ApplicationInfo extends PackageItemInfo implements android.os.Parcelable {
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
    /** Application-level fallback consumed by AOSP ActivityInfo.getThemeResource(). */
    public int theme;
    public String processName;
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
    public ApplicationInfo() {}
    /** AOSP ApplicationInfo.writeToParcel order for the fields this shim has. */
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeInt(theme);
        dest.writeInt(flags);
        dest.writeInt(requiresSmallestWidthDp);
        dest.writeInt(compatibleWidthLimitDp);
        dest.writeInt(largestWidthLimitDp);
        dest.writeString8(sourceDir);
        dest.writeString8(publicSourceDir);
        dest.writeStringArray(splitNames);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeStringArray(resourceDirs);
        dest.writeStringArray(overlayPaths);
        dest.writeStringArray(sharedLibraryFiles);
        dest.writeString8(dataDir);
        dest.writeString8(nativeLibraryDir);
        dest.writeString8(processName);
        dest.writeInt(uid);
        dest.writeInt(minSdkVersion);
        dest.writeInt(targetSdkVersion);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(category);
        dest.writeFloat(maxAspectRatio);
    }
    protected ApplicationInfo(android.os.Parcel source) {
        super(source);
        theme = source.readInt();
        flags = source.readInt();
        requiresSmallestWidthDp = source.readInt();
        compatibleWidthLimitDp = source.readInt();
        largestWidthLimitDp = source.readInt();
        sourceDir = source.readString8();
        publicSourceDir = source.readString8();
        splitNames = source.createStringArray();
        splitSourceDirs = source.createStringArray();
        splitPublicSourceDirs = source.createStringArray();
        resourceDirs = source.createStringArray();
        overlayPaths = source.createStringArray();
        sharedLibraryFiles = source.createStringArray();
        dataDir = source.readString8();
        nativeLibraryDir = source.readString8();
        processName = source.readString8();
        uid = source.readInt();
        minSdkVersion = source.readInt();
        targetSdkVersion = source.readInt();
        enabled = source.readInt() != 0;
        category = source.readInt();
        maxAspectRatio = source.readFloat();
    }
    public static final android.os.Parcelable.Creator<ApplicationInfo> CREATOR =
            new android.os.Parcelable.Creator<ApplicationInfo>() {
        public ApplicationInfo createFromParcel(android.os.Parcel source) { return new ApplicationInfo(source); }
        public ApplicationInfo[] newArray(int size) { return new ApplicationInfo[size]; }
    };
    public static final int FLAG_DEBUGGABLE = 1 << 1;
    public static final int FLAG_TEST_ONLY = 1 << 8;
    public static final int FLAG_SUPPORTS_SMALL_SCREENS = 1 << 9, FLAG_SUPPORTS_NORMAL_SCREENS = 1 << 10,
            FLAG_SUPPORTS_LARGE_SCREENS = 1 << 11, FLAG_SUPPORTS_XLARGE_SCREENS = 1 << 19,
            FLAG_RESIZEABLE_FOR_SCREENS = 1 << 12, FLAG_SUPPORTS_SCREEN_DENSITIES = 1 << 13,
            FLAG_HAS_CODE = 1 << 2, FLAG_SYSTEM = 1 << 0;
}
