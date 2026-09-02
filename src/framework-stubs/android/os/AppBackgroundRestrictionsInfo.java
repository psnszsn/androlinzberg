/* artzig stub: the constants of the statsd AppBackgroundRestrictionsInfo atom
 * (frameworks/proto_logging/stats/atoms.proto), which PowerExemptionManager maps
 * exemption reasons to. atoms.proto is not run through the proto generator on this
 * host (no statsd); the enum values below are copied verbatim from the pinned proto. */
package android.os;
public final class AppBackgroundRestrictionsInfo {
    private AppBackgroundRestrictionsInfo() {}
    /** RestrictionLevel */
    public static final int LEVEL_UNKNOWN = 0;
    /** RestrictionLevel */
    public static final int LEVEL_UNRESTRICTED = 1;
    /** RestrictionLevel */
    public static final int LEVEL_EXEMPTED = 2;
    /** RestrictionLevel */
    public static final int LEVEL_ADAPTIVE_BUCKET = 3;
    /** RestrictionLevel */
    public static final int LEVEL_RESTRICTED_BUCKET = 4;
    /** RestrictionLevel */
    public static final int LEVEL_BACKGROUND_RESTRICTED = 5;
    /** RestrictionLevel */
    public static final int LEVEL_HIBERNATION = 6;
    /** Threshold */
    public static final int THRESHOLD_UNKNOWN = 0;
    /** Threshold */
    public static final int THRESHOLD_RESTRICTED = 1;
    /** Threshold */
    public static final int THRESHOLD_USER = 2;
    /** StateTracker */
    public static final int UNKNOWN_TRACKER = 0;
    /** StateTracker */
    public static final int BATTERY_TRACKER = 1;
    /** StateTracker */
    public static final int BATTERY_EXEMPTION_TRACKER = 2;
    /** StateTracker */
    public static final int FGS_TRACKER = 3;
    /** StateTracker */
    public static final int MEDIA_SESSION_TRACKER = 4;
    /** StateTracker */
    public static final int PERMISSION_TRACKER = 5;
    /** StateTracker */
    public static final int BROADCAST_EVENTS_TRACKER = 6;
    /** StateTracker */
    public static final int BIND_SERVICE_EVENTS_TRACKER = 7;
    /** ExemptionReason */
    public static final int REASON_UNKNOWN = 0;
    /** ExemptionReason */
    public static final int REASON_DENIED = 1;
    /** ExemptionReason */
    public static final int REASON_OTHER = 2;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_PERSISTENT = 10;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_PERSISTENT_UI = 11;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_TOP = 12;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_BTOP = 13;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_FGS = 14;
    /** ExemptionReason */
    public static final int REASON_PROC_STATE_BFGS = 15;
    /** ExemptionReason */
    public static final int REASON_UID_VISIBLE = 50;
    /** ExemptionReason */
    public static final int REASON_SYSTEM_UID = 51;
    /** ExemptionReason */
    public static final int REASON_ACTIVITY_STARTER = 52;
    /** ExemptionReason */
    public static final int REASON_START_ACTIVITY_FLAG = 53;
    /** ExemptionReason */
    public static final int REASON_FGS_BINDING = 54;
    /** ExemptionReason */
    public static final int REASON_DEVICE_OWNER = 55;
    /** ExemptionReason */
    public static final int REASON_PROFILE_OWNER = 56;
    /** ExemptionReason */
    public static final int REASON_COMPANION_DEVICE_MANAGER = 57;
    /** ExemptionReason */
    public static final int REASON_BACKGROUND_ACTIVITY_PERMISSION = 58;
    /** ExemptionReason */
    public static final int REASON_BACKGROUND_FGS_PERMISSION = 59;
    /** ExemptionReason */
    public static final int REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION = 60;
    /** ExemptionReason */
    public static final int REASON_INSTR_BACKGROUND_FGS_PERMISSION = 61;
    /** ExemptionReason */
    public static final int REASON_SYSTEM_ALERT_WINDOW_PERMISSION = 62;
    /** ExemptionReason */
    public static final int REASON_DEVICE_DEMO_MODE = 63;
    /** ExemptionReason */
    public static final int REASON_ALLOWLISTED_PACKAGE = 65;
    /** ExemptionReason */
    public static final int REASON_APPOP = 66;
    /** ExemptionReason */
    public static final int REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD = 67;
    /** ExemptionReason */
    public static final int REASON_OP_ACTIVATE_VPN = 68;
    /** ExemptionReason */
    public static final int REASON_OP_ACTIVATE_PLATFORM_VPN = 69;
    /** ExemptionReason */
    public static final int REASON_TEMP_ALLOWED_WHILE_IN_USE = 70;
    /** ExemptionReason */
    public static final int REASON_CURRENT_INPUT_METHOD = 71;
    /** ExemptionReason */
    public static final int REASON_GEOFENCING = 100;
    /** ExemptionReason */
    public static final int REASON_PUSH_MESSAGING = 101;
    /** ExemptionReason */
    public static final int REASON_PUSH_MESSAGING_OVER_QUOTA = 102;
    /** ExemptionReason */
    public static final int REASON_ACTIVITY_RECOGNITION = 103;
    /** ExemptionReason */
    public static final int REASON_ACCOUNT_TRANSFER = 104;
    /** ExemptionReason */
    public static final int REASON_BOOT_COMPLETED = 200;
    /** ExemptionReason */
    public static final int REASON_PRE_BOOT_COMPLETED = 201;
    /** ExemptionReason */
    public static final int REASON_LOCKED_BOOT_COMPLETED = 202;
    /** ExemptionReason */
    public static final int REASON_BLUETOOTH_BROADCAST = 203;
    /** ExemptionReason */
    public static final int REASON_TIMEZONE_CHANGED = 204;
    /** ExemptionReason */
    public static final int REASON_TIME_CHANGED = 205;
    /** ExemptionReason */
    public static final int REASON_LOCALE_CHANGED = 206;
    /** ExemptionReason */
    public static final int REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED = 207;
    /** ExemptionReason */
    public static final int REASON_REFRESH_SAFETY_SOURCES = 208;
    /** ExemptionReason */
    public static final int REASON_SYSTEM_ALLOW_LISTED = 300;
    /** ExemptionReason */
    public static final int REASON_ALARM_MANAGER_ALARM_CLOCK = 301;
    /** ExemptionReason */
    public static final int REASON_ALARM_MANAGER_WHILE_IDLE = 302;
    /** ExemptionReason */
    public static final int REASON_SERVICE_LAUNCH = 303;
    /** ExemptionReason */
    public static final int REASON_KEY_CHAIN = 304;
    /** ExemptionReason */
    public static final int REASON_PACKAGE_VERIFIER = 305;
    /** ExemptionReason */
    public static final int REASON_SYNC_MANAGER = 306;
    /** ExemptionReason */
    public static final int REASON_DOMAIN_VERIFICATION_V1 = 307;
    /** ExemptionReason */
    public static final int REASON_DOMAIN_VERIFICATION_V2 = 308;
    /** ExemptionReason */
    public static final int REASON_VPN = 309;
    /** ExemptionReason */
    public static final int REASON_NOTIFICATION_SERVICE = 310;
    /** ExemptionReason */
    public static final int REASON_PACKAGE_REPLACED = 311;
    /** ExemptionReason */
    public static final int REASON_LOCATION_PROVIDER = 312;
    /** ExemptionReason */
    public static final int REASON_MEDIA_BUTTON = 313;
    /** ExemptionReason */
    public static final int REASON_EVENT_SMS = 314;
    /** ExemptionReason */
    public static final int REASON_EVENT_MMS = 315;
    /** ExemptionReason */
    public static final int REASON_SHELL = 316;
    /** ExemptionReason */
    public static final int REASON_MEDIA_SESSION_CALLBACK = 317;
    /** ExemptionReason */
    public static final int REASON_ROLE_DIALER = 318;
    /** ExemptionReason */
    public static final int REASON_ROLE_EMERGENCY = 319;
    /** ExemptionReason */
    public static final int REASON_SYSTEM_MODULE = 320;
    /** ExemptionReason */
    public static final int REASON_CARRIER_PRIVILEGED_APP = 321;
    /** ExemptionReason */
    public static final int REASON_DPO_PROTECTED_APP = 322;
    /** ExemptionReason */
    public static final int REASON_DISALLOW_APPS_CONTROL = 323;
    /** ExemptionReason */
    public static final int REASON_ACTIVE_DEVICE_ADMIN = 324;
    /** ExemptionReason */
    public static final int REASON_MEDIA_NOTIFICATION_TRANSFER = 325;
    /** ExemptionReason */
    public static final int REASON_PACKAGE_INSTALLER = 326;
    /** ExemptionReason */
    public static final int REASON_SYSTEM_EXEMPT_APP_OP = 327;
    /** ExemptionReason */
    public static final int REASON_PACKAGE_UNARCHIVE = 328;
    /** ExemptionReason */
    public static final int REASON_TILE_ONCLICK = 329;
    /** ExemptionReason */
    public static final int REASON_OPT_OUT_REQUESTED = 1000;
    /** OptimizationLevel */
    public static final int UNKNOWN = 0;
    /** OptimizationLevel */
    public static final int OPTIMIZED = 1;
    /** OptimizationLevel */
    public static final int BACKGROUND_RESTRICTED = 2;
    /** OptimizationLevel */
    public static final int NOT_OPTIMIZED = 3;
    /** TargetSdk */
    public static final int SDK_UNKNOWN = 0;
    /** TargetSdk */
    public static final int SDK_PRE_S = 1;
    /** TargetSdk */
    public static final int SDK_S = 2;
    /** TargetSdk */
    public static final int SDK_T = 3;
}
