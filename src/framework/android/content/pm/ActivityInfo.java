/* artzig shim: android.content.pm.ActivityInfo — CONFIG_* constants + @Config only. */
package android.content.pm;
public class ActivityInfo extends ComponentInfo implements android.os.Parcelable {
    public static final int CONFIG_MCC = 0x0001, CONFIG_MNC = 0x0002, CONFIG_LOCALE = 0x0004,
            CONFIG_TOUCHSCREEN = 0x0008, CONFIG_KEYBOARD = 0x0010, CONFIG_KEYBOARD_HIDDEN = 0x0020,
            CONFIG_NAVIGATION = 0x0040, CONFIG_ORIENTATION = 0x0080, CONFIG_SCREEN_LAYOUT = 0x0100,
            CONFIG_UI_MODE = 0x0200, CONFIG_SCREEN_SIZE = 0x0400, CONFIG_SMALLEST_SCREEN_SIZE = 0x0800,
            CONFIG_DENSITY = 0x1000, CONFIG_LAYOUT_DIRECTION = 0x2000, CONFIG_COLOR_MODE = 0x4000,
            CONFIG_GRAMMATICAL_GENDER = 0x8000, CONFIG_FONT_SCALE = 0x40000000,
            CONFIG_ASSETS_PATHS = 0x80000000, CONFIG_RESOURCES_UNUSED = 0x00008000,
            CONFIG_WINDOW_CONFIGURATION = 0x20000000, CONFIG_FONT_WEIGHT_ADJUSTMENT = 0x10000000;
    /** Native config bits (androidfw ResTable_config) -> java CONFIG_* */
    public static int activityInfoConfigNativeToJava(int input) {
        int output = 0;
        if ((input & 0x0001) != 0) output |= CONFIG_MCC;
        if ((input & 0x0002) != 0) output |= CONFIG_MNC;
        if ((input & 0x0004) != 0) output |= CONFIG_LOCALE;
        if ((input & 0x0008) != 0) output |= CONFIG_TOUCHSCREEN;
        if ((input & 0x0010) != 0) output |= CONFIG_KEYBOARD;
        if ((input & 0x0020) != 0) output |= CONFIG_KEYBOARD_HIDDEN;
        if ((input & 0x0040) != 0) output |= CONFIG_NAVIGATION;
        if ((input & 0x0080) != 0) output |= CONFIG_ORIENTATION;
        if ((input & 0x0100) != 0) output |= CONFIG_DENSITY;
        if ((input & 0x0200) != 0) output |= CONFIG_SCREEN_SIZE;
        if ((input & 0x0400) != 0) output |= CONFIG_SMALLEST_SCREEN_SIZE;
        if ((input & 0x0800) != 0) output |= CONFIG_SCREEN_LAYOUT;
        if ((input & 0x1000) != 0) output |= CONFIG_UI_MODE;
        if ((input & 0x2000) != 0) output |= CONFIG_LAYOUT_DIRECTION;
        if ((input & 0x4000) != 0) output |= CONFIG_COLOR_MODE;
        if ((input & 0x8000) != 0) output |= CONFIG_GRAMMATICAL_GENDER;
        if ((input & 0x40000000) != 0) output |= CONFIG_FONT_SCALE;
        return output;
    }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface Config {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ColorMode {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ScreenOrientation {}
    public static String colorModeToString(int colorMode) { return Integer.toString(colorMode); }
    public static final int COLOR_MODE_DEFAULT = 0, COLOR_MODE_WIDE_COLOR_GAMUT = 1, COLOR_MODE_HDR = 2, COLOR_MODE_A8 = 4;
    public static final int SCREEN_ORIENTATION_UNSET = -2, SCREEN_ORIENTATION_UNSPECIFIED = -1,
            SCREEN_ORIENTATION_LANDSCAPE = 0, SCREEN_ORIENTATION_PORTRAIT = 1,
            SCREEN_ORIENTATION_USER = 2, SCREEN_ORIENTATION_BEHIND = 3,
            SCREEN_ORIENTATION_SENSOR = 4, SCREEN_ORIENTATION_NOSENSOR = 5,
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6, SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7,
            SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8, SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9,
            SCREEN_ORIENTATION_FULL_SENSOR = 10, SCREEN_ORIENTATION_USER_LANDSCAPE = 11,
            SCREEN_ORIENTATION_USER_PORTRAIT = 12, SCREEN_ORIENTATION_FULL_USER = 13,
            SCREEN_ORIENTATION_LOCKED = 14;
    public static String screenOrientationToString(int orientation) { return Integer.toString(orientation); }
    public static boolean isFixedOrientation(int orientation) { return false; }
    public static boolean isFixedOrientationLandscape(int orientation) {
        return orientation == SCREEN_ORIENTATION_LANDSCAPE || orientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_REVERSE_LANDSCAPE || orientation == SCREEN_ORIENTATION_USER_LANDSCAPE;
    }
    public static boolean isFixedOrientationPortrait(int orientation) {
        return orientation == SCREEN_ORIENTATION_PORTRAIT || orientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == SCREEN_ORIENTATION_REVERSE_PORTRAIT || orientation == SCREEN_ORIENTATION_USER_PORTRAIT;
    }
    public int theme, flags, launchMode, screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
    public int configChanges, softInputMode, colorMode = COLOR_MODE_DEFAULT;
    public String targetActivity, permission;
    public String parentActivityName;
    public String taskAffinity;
    public int documentLaunchMode;
    public int persistableMode;
    public int maxRecents;
    public int uiOptions;
    public int rotationAnimation = -1;
    public int lockTaskLaunchMode;
    public WindowLayout windowLayout;
    public String requestedVrComponent;
    public int requiredDisplayCategory = -1;
    public static final class WindowLayout {
        public int width, height;
        public float widthFraction, heightFraction;
        public int gravity;
        public int minWidth, minHeight;
    }
    public boolean hasOnBackInvokedCallbackEnabled() { return false; }
    public boolean isOnBackInvokedCallbackEnabled() { return false; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<ActivityInfo> CREATOR =
            new android.os.Parcelable.Creator<ActivityInfo>() {
        public ActivityInfo createFromParcel(android.os.Parcel in) { return new ActivityInfo(); }
        public ActivityInfo[] newArray(int size) { return new ActivityInfo[size]; }
    };
}
