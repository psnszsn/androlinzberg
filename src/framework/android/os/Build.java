/* artzig shim: android.os.Build — static device/version constants. */
package android.os;
public class Build {
    public static final String BRAND = "artzig", DEVICE = "artzig", MODEL = "artzig",
            MANUFACTURER = "artzig", PRODUCT = "artzig", HARDWARE = "artzig",
            BOARD = "artzig", DISPLAY = "artzig", FINGERPRINT = "artzig/artzig:16",
            ID = "artzig", TAGS = "release-keys", TYPE = "user", USER = "artzig",
            HOST = "artzig", BOOTLOADER = "unknown", RADIO = "unknown", SERIAL = "unknown";
    public static final long TIME = 0;
    public static final String[] SUPPORTED_ABIS = { "x86_64", "arm64-v8a" };
    public static final String[] SUPPORTED_64_BIT_ABIS = { "x86_64", "arm64-v8a" };
    public static final String[] SUPPORTED_32_BIT_ABIS = {};
    public static final String CPU_ABI = "x86_64", CPU_ABI2 = "";
    public static boolean IS_DEBUGGABLE = false;
    public static final boolean IS_ENG = false, IS_USER = true, IS_USERDEBUG = false;
    /** AOSP: ro.boot.qemu. The system process sets it so the reused ClipboardService
     * constructs the (hosted, Wayland-backed) EmulatorClipboardMonitor; app processes see false. */
    public static final boolean IS_EMULATOR = SystemProperties.getBoolean("ro.boot.qemu", false);
    public static final boolean IS_ARC = false;
    public static final int HW_TIMEOUT_MULTIPLIER = 1;
    public static class VERSION {
        public static final int SDK_INT = 35;
        public static final String SDK = "35";
        public static final String RELEASE = "15";
        public static final String RELEASE_OR_CODENAME = "15";
        public static final String CODENAME = "REL";
        public static final String INCREMENTAL = "artzig";
        public static final String SECURITY_PATCH = "2026-08-01";
        public static final int PREVIEW_SDK_INT = 0;
        public static final int MEDIA_PERFORMANCE_CLASS = 0;
        public static final int DEVICE_INITIAL_SDK_INT = 24;
        public static final String BASE_OS = "";
        public static final int RESOURCES_SDK_INT = SDK_INT;
    }
    public static class VERSION_CODES {
        public static final int CUR_DEVELOPMENT = 10000;
        public static final int BASE = 1, BASE_1_1 = 2, CUPCAKE = 3, DONUT = 4, ECLAIR = 5,
                ECLAIR_0_1 = 6, ECLAIR_MR1 = 7, FROYO = 8, GINGERBREAD = 9, GINGERBREAD_MR1 = 10,
                HONEYCOMB = 11, HONEYCOMB_MR1 = 12, HONEYCOMB_MR2 = 13, ICE_CREAM_SANDWICH = 14,
                ICE_CREAM_SANDWICH_MR1 = 15, JELLY_BEAN = 16, JELLY_BEAN_MR1 = 17,
                JELLY_BEAN_MR2 = 18, KITKAT = 19, KITKAT_WATCH = 20, L = 21, LOLLIPOP = 21,
                LOLLIPOP_MR1 = 22, M = 23, N = 24, N_MR1 = 25, O = 26, O_MR1 = 27, P = 28,
                Q = 29, R = 30, S = 31, S_V2 = 32, TIRAMISU = 33, UPSIDE_DOWN_CAKE = 34,
                VANILLA_ICE_CREAM = 35, BAKLAVA = 36;
    }
    public static String getSerial() { return SERIAL; }
    public static boolean isDebuggable() { return IS_DEBUGGABLE; }
}
