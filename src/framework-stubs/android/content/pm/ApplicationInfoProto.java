/* artzig stub: proto ids. */
package android.content.pm;
public final class ApplicationInfoProto {
    public static final long CLASS_LOADER_NAME = 1;
    public static final long DATA_DIR = 2;
    public static final long DETAIL = 3;
    public static final long FLAGS = 4;
    public static final long KNOWN_ACTIVITY_EMBEDDING_CERTS = 5;
    public static final long OVERLAY_PATHS = 6;
    public static final long PACKAGE = 7;
    public static final long PERMISSION = 8;
    public static final long PRIVATE_FLAGS = 9;
    public static final long PROCESS_NAME = 10;
    public static final long PUBLIC_SOURCE_DIR = 11;
    public static final long RESOURCE_DIRS = 12;
    public static final long SOURCE_DIR = 13;
    public static final long SPLIT_CLASS_LOADER_NAMES = 14;
    public static final long SPLIT_PUBLIC_SOURCE_DIRS = 15;
    public static final long SPLIT_SOURCE_DIRS = 16;
    public static final long THEME = 17;
    public static final long UID = 18;
    public static final long VERSION = 19;
    public static final class Detail {
        public static final long ALLOW_CROSS_UID_ACTIVITY_SWITCH_FROM_BELOW = 1;
        public static final long CATEGORY = 2;
        public static final long CLASS_NAME = 3;
        public static final long COMPATIBLE_WIDTH_LIMIT_DP = 4;
        public static final long CONTENT = 5;
        public static final long CREDENTIAL_PROTECTED_DATA_DIR = 6;
        public static final long DESCRIPTION_RES = 7;
        public static final long DEVICE_PROTECTED_DATA_DIR = 8;
        public static final long ENABLE_GWP_ASAN = 9;
        public static final long ENABLE_MEMTAG = 10;
        public static final long ENABLE_PAGE_SIZE_APP_COMPAT = 11;
        public static final long IS_FULL_BACKUP = 12;
        public static final long LARGEST_WIDTH_LIMIT_DP = 13;
        public static final long MANAGE_SPACE_ACTIVITY_NAME = 14;
        public static final long NATIVE_HEAP_ZERO_INIT = 15;
        public static final long NETWORK_SECURITY_CONFIG_RES = 16;
        public static final long REQUIRES_SMALLEST_WIDTH_DP = 17;
        public static final long SEINFO = 18;
        public static final long SEINFO_USER = 19;
        public static final long SHARED_LIBRARY_FILES = 20;
        public static final long SUPPORTS_RTL = 21;
        public static final long TASK_AFFINITY = 22;
        public static final long UI_OPTIONS = 23;
        private Detail() {}
    }
    public static final class Version {
        public static final long ENABLED = 1;
        public static final long MIN_SDK_VERSION = 2;
        public static final long TARGET_SANDBOX_VERSION = 3;
        public static final long TARGET_SDK_VERSION = 4;
        public static final long VERSION_CODE = 5;
        private Version() {}
    }
    private ApplicationInfoProto() {}
}
