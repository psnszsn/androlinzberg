/* artzig stub: android.provider.Settings — all defaults. */
package android.provider;
import android.content.ContentResolver;
public final class Settings {
    private Settings() {}
    public static final String ACTION_INPUT_METHOD_SUBTYPE_SETTINGS = "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS";
    public static final String ACTION_USER_DICTIONARY_INSERT = "com.android.settings.USER_DICTIONARY_INSERT";
    public static final String EXTRA_INPUT_METHOD_ID = "input_method_id";
    public static class SettingNotFoundException extends android.util.AndroidException {
        public SettingNotFoundException(String msg) { super(msg); }
    }
    public static final class Global {
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) { return def; }
        public static boolean putIntForUser(ContentResolver cr, String name, int value, int userHandle) { return false; }
        public static boolean putStringForUser(ContentResolver cr, String name, String value, int userHandle) { return false; }
        public static float getFloatForUser(ContentResolver cr, String name, float def, int userHandle) { return def; }
        public static android.net.Uri getUriFor(String name) { return android.net.Uri.parse("content://settings/" + name); }

        public static final String ADB_ENABLED = "adb_enabled";
        public static final String AIRPLANE_MODE_ON = "airplane_mode_on";
        public static final String AIRPLANE_MODE_RADIOS = "airplane_mode_radios";
        public static final String AIRPLANE_MODE_TOGGLEABLE_RADIOS = "airplane_mode_toggleable_radios";
        public static final String ALWAYS_FINISH_ACTIVITIES = "always_finish_activities";
        public static final String ANGLE_DEBUG_PACKAGE = "angle_debug_package";
        public static final String ANGLE_EGL_FEATURES = "angle_egl_features";
        public static final String ANGLE_GL_DRIVER_ALL_ANGLE = "angle_gl_driver_all_angle";
        public static final String ANGLE_GL_DRIVER_SELECTION_PKGS = "angle_gl_driver_selection_pkgs";
        public static final String ANGLE_GL_DRIVER_SELECTION_VALUES = "angle_gl_driver_selection_values";
        public static final String APPLY_RAMPING_RINGER = "apply_ramping_ringer";
        public static final String ASSISTED_GPS_ENABLED = "assisted_gps_enabled";
        public static final String AUTO_TIME = "auto_time";
        public static final String AUTO_TIME_ZONE = "auto_time_zone";
        public static final String BATTERY_DISCHARGE_DURATION_THRESHOLD = "battery_discharge_duration_threshold";
        public static final String BATTERY_DISCHARGE_THRESHOLD = "battery_discharge_threshold";
        public static final String BINDER_CALLS_STATS = "binder_calls_stats";
        public static final String BLUETOOTH_ON = "bluetooth_on";
        public static final String BUGREPORT_IN_POWER_MENU = "bugreport_in_power_menu";
        public static final String CALL_AUTO_RETRY = "call_auto_retry";
        public static final String CAPTIVE_PORTAL_DETECTION_ENABLED = "captive_portal_detection_enabled";
        public static final String CAPTIVE_PORTAL_SERVER = "captive_portal_server";
        public static final String CAR_DOCK_SOUND = "car_dock_sound";
        public static final String CAR_UNDOCK_SOUND = "car_undock_sound";
        public static final String CDMA_CELL_BROADCAST_SMS = "cdma_cell_broadcast_sms";
        public static final String CDMA_ROAMING_MODE = "cdma_roaming_mode";
        public static final String CDMA_SUBSCRIPTION_MODE = "cdma_subscription_mode";
        public static final String CERT_PIN_UPDATE_CONTENT_URL = "cert_pin_update_content_url";
        public static final String CERT_PIN_UPDATE_METADATA_URL = "cert_pin_update_metadata_url";
        public static final String CHAINED_BATTERY_ATTRIBUTION_ENABLED = "chained_battery_attribution_enabled";
        public static final String CHARGING_SOUNDS_ENABLED = "charging_sounds_enabled";
        public static final String CHARGING_VIBRATION_ENABLED = "charging_vibration_enabled";
        public static final String COMPATIBILITY_MODE = "compatibility_mode";
        public static final String CONNECTIVITY_CHANGE_DELAY = "connectivity_change_delay";
        public static final String CUSTOM_BUGREPORT_HANDLER_APP = "custom_bugreport_handler_app";
        public static final String CUSTOM_BUGREPORT_HANDLER_USER = "custom_bugreport_handler_user";
        public static final String DATA_ACTIVITY_TIMEOUT_MOBILE = "data_activity_timeout_mobile";
        public static final String DATA_ACTIVITY_TIMEOUT_WIFI = "data_activity_timeout_wifi";
        public static final String DATA_ROAMING = "data_roaming";
        public static final String DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS = "data_stall_alarm_aggressive_delay_in_ms";
        public static final String DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS = "data_stall_alarm_non_aggressive_delay_in_ms";
        public static final String DEBUG_APP = "debug_app";
        public static final String DEBUG_VIEW_ATTRIBUTES = "debug_view_attributes";
        public static final String DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE = "debug_view_attributes_application_package";
        public static final String DEFAULT_DNS_SERVER = "default_dns_server";
        public static final String DEFAULT_INSTALL_LOCATION = "default_install_location";
        public static final String DESK_DOCK_SOUND = "desk_dock_sound";
        public static final String DESK_UNDOCK_SOUND = "desk_undock_sound";
        public static final String DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES = "development_force_resizable_activities";
        public static final String DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES = "development_override_desktop_mode_features";
        public static final String DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR = "development_render_shadows_in_compositor";
        public static final String DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled";
        public static final String DEVICE_DEMO_MODE = "device_demo_mode";
        public static final String DEVICE_PROVISIONED = "device_provisioned";
        public static final String DISK_FREE_CHANGE_REPORTING_THRESHOLD = "disk_free_change_reporting_threshold";
        public static final String DISPLAY_SIZE_FORCED = "display_size_forced";
        public static final String DOCK_SOUNDS_ENABLED = "dock_sounds_enabled";
        public static final String DOWNLOAD_MAX_BYTES_OVER_MOBILE = "download_max_bytes_over_mobile";
        public static final String DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE = "download_recommended_max_bytes_over_mobile";
        public static final String DROPBOX_AGE_SECONDS = "dropbox_age_seconds";
        public static final String DROPBOX_MAX_FILES = "dropbox_max_files";
        public static final String DROPBOX_QUOTA_KB = "dropbox_quota_kb";
        public static final String DROPBOX_QUOTA_PERCENT = "dropbox_quota_percent";
        public static final String DROPBOX_RESERVE_PERCENT = "dropbox_reserve_percent";
        public static final String DROPBOX_TAG_PREFIX = "dropbox_tag_prefix";
        public static final String EMERGENCY_AFFORDANCE_NEEDED = "emergency_affordance_needed";
        public static final String EMERGENCY_TONE = "emergency_tone";
        public static final String ENABLE_GPU_DEBUG_LAYERS = "enable_gpu_debug_layers";
        public static final String EPHEMERAL_COOKIE_MAX_SIZE_BYTES = "ephemeral_cookie_max_size_bytes";
        public static final String ERROR_LOGCAT_PREFIX = "error_logcat_prefix";
        public static final String EUICC_FACTORY_RESET_TIMEOUT_MILLIS = "euicc_factory_reset_timeout_millis";
        public static final String EUICC_PROVISIONED = "euicc_provisioned";
        public static final String EUICC_REMOVING_INVISIBLE_PROFILES_TIMEOUT_MILLIS = "euicc_removing_invisible_profiles_timeout_millis";
        public static final String FANCY_IME_ANIMATIONS = "fancy_ime_animations";
        public static final String FORCE_ALLOW_ON_EXTERNAL = "force_allow_on_external";
        public static final String GLOBAL_HTTP_PROXY_EXCLUSION_LIST = "global_http_proxy_exclusion_list";
        public static final String GLOBAL_HTTP_PROXY_HOST = "global_http_proxy_host";
        public static final String GLOBAL_HTTP_PROXY_PORT = "global_http_proxy_port";
        public static final String GPRS_REGISTER_CHECK_PERIOD_MS = "gprs_register_check_period_ms";
        public static final String GPU_DEBUG_APP = "gpu_debug_app";
        public static final String GPU_DEBUG_LAYERS = "gpu_debug_layers";
        public static final String GPU_DEBUG_LAYERS_GLES = "gpu_debug_layers_gles";
        public static final String GPU_DEBUG_LAYER_APP = "gpu_debug_layer_app";
        public static final String HTTP_PROXY = "http_proxy";
        public static final String INET_CONDITION_DEBOUNCE_DOWN_DELAY = "inet_condition_debounce_down_delay";
        public static final String INET_CONDITION_DEBOUNCE_UP_DELAY = "inet_condition_debounce_up_delay";
        public static final String INSTALL_NON_MARKET_APPS = "install_non_market_apps";
        public static final String KERNEL_CPU_THREAD_READER = "kernel_cpu_thread_reader";
        public static final String LOCK_SOUND = "lock_sound";
        public static final String LOW_BATTERY_SOUND = "low_battery_sound";
        public static final String MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH = "maximum_obscuring_opacity_for_touch";
        public static final String MOBILE_DATA = "mobile_data";
        public static final String MODE_RINGER = "mode_ringer";
        public static final String NETSTATS_DEV_BUCKET_DURATION = "netstats_dev_bucket_duration";
        public static final String NETSTATS_DEV_DELETE_AGE = "netstats_dev_delete_age";
        public static final String NETSTATS_DEV_PERSIST_BYTES = "netstats_dev_persist_bytes";
        public static final String NETSTATS_DEV_ROTATE_AGE = "netstats_dev_rotate_age";
        public static final String NETSTATS_ENABLED = "netstats_enabled";
        public static final String NETSTATS_GLOBAL_ALERT_BYTES = "netstats_global_alert_bytes";
        public static final String NETSTATS_POLL_INTERVAL = "netstats_poll_interval";
        public static final String NETSTATS_SAMPLE_ENABLED = "netstats_sample_enabled";
        public static final String NETSTATS_TIME_CACHE_MAX_AGE = "netstats_time_cache_max_age";
        public static final String NETSTATS_UID_BUCKET_DURATION = "netstats_uid_bucket_duration";
        public static final String NETSTATS_UID_DELETE_AGE = "netstats_uid_delete_age";
        public static final String NETSTATS_UID_PERSIST_BYTES = "netstats_uid_persist_bytes";
        public static final String NETSTATS_UID_ROTATE_AGE = "netstats_uid_rotate_age";
        public static final String NETSTATS_UID_TAG_BUCKET_DURATION = "netstats_uid_tag_bucket_duration";
        public static final String NETSTATS_UID_TAG_DELETE_AGE = "netstats_uid_tag_delete_age";
        public static final String NETSTATS_UID_TAG_PERSIST_BYTES = "netstats_uid_tag_persist_bytes";
        public static final String NETSTATS_UID_TAG_ROTATE_AGE = "netstats_uid_tag_rotate_age";
        public static final String NETWORK_PREFERENCE = "network_preference";
        public static final String NITZ_UPDATE_DIFF = "nitz_update_diff";
        public static final String NITZ_UPDATE_SPACING = "nitz_update_spacing";
        public static final String NOTIFICATION_BUBBLES = "notification_bubbles";
        public static final String NTP_SERVER = "ntp_server";
        public static final String NTP_TIMEOUT = "ntp_timeout";
        public static final String PACKAGE_VERIFIER_DEFAULT_RESPONSE = "package_verifier_default_response";
        public static final String PACKAGE_VERIFIER_TIMEOUT = "package_verifier_timeout";
        public static final String PDP_WATCHDOG_ERROR_POLL_COUNT = "pdp_watchdog_error_poll_count";
        public static final String PDP_WATCHDOG_LONG_POLL_INTERVAL_MS = "pdp_watchdog_long_poll_interval_ms";
        public static final String PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT = "pdp_watchdog_max_pdp_reset_fail_count";
        public static final String PDP_WATCHDOG_POLL_INTERVAL_MS = "pdp_watchdog_poll_interval_ms";
        public static final String PDP_WATCHDOG_TRIGGER_PACKET_COUNT = "pdp_watchdog_trigger_packet_count";
        public static final String POWER_SOUNDS_ENABLED = "power_sounds_enabled";
        public static final String PREFERRED_NETWORK_MODE = "preferred_network_mode";
        public static final String RADIO_BLUETOOTH = "radio_bluetooth";
        public static final String RADIO_CELL = "radio_cell";
        public static final String RADIO_NFC = "radio_nfc";
        public static final String RADIO_WIFI = "radio_wifi";
        public static final String RADIO_WIMAX = "radio_wimax";
        public static final String READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT = "read_external_storage_enforced_default";
        public static final String REMOVE_GUEST_ON_EXIT = "remove_guest_on_exit";
        public static final String REPAIR_MODE_ACTIVE = "repair_mode_active";
        public static final String SECURE_FRP_MODE = "secure_frp_mode";
        public static final String SELINUX_UPDATE_CONTENT_URL = "selinux_update_content_url";
        public static final String SELINUX_UPDATE_METADATA_URL = "selinux_update_metadata_url";
        public static final String SEND_ACTION_APP_ERROR = "send_action_app_error";
        public static final String SETUP_PREPAID_DATA_SERVICE_URL = "setup_prepaid_data_service_url";
        public static final String SETUP_PREPAID_DETECTION_REDIR_HOST = "setup_prepaid_detection_redir_host";
        public static final String SETUP_PREPAID_DETECTION_TARGET_URL = "setup_prepaid_detection_target_url";
        public static final String SET_GLOBAL_HTTP_PROXY = "set_global_http_proxy";
        public static final String SET_INSTALL_LOCATION = "set_install_location";
        public static final String SHOW_ANGLE_IN_USE_DIALOG_BOX = "show_angle_in_use_dialog_box";
        public static final String SHOW_NEW_APP_INSTALLED_NOTIFICATION_ENABLED = "show_new_app_installed_notification_enabled";
        public static final String SHOW_PROCESSES = "show_processes";
        public static final String SMS_SHORT_CODES_UPDATE_CONTENT_URL = "sms_short_codes_update_content_url";
        public static final String SMS_SHORT_CODES_UPDATE_METADATA_URL = "sms_short_codes_update_metadata_url";
        public static final String STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in";
        public static final String STYLUS_EVER_USED = "stylus_ever_used";
        public static final String SYNC_MAX_RETRY_DELAY_IN_SECONDS = "sync_max_retry_delay_in_seconds";
        public static final String SYS_FREE_STORAGE_LOG_INTERVAL = "sys_free_storage_log_interval";
        public static final String SYS_STORAGE_FULL_THRESHOLD_BYTES = "sys_storage_full_threshold_bytes";
        public static final String SYS_STORAGE_THRESHOLD_MAX_BYTES = "sys_storage_threshold_max_bytes";
        public static final String SYS_STORAGE_THRESHOLD_PERCENTAGE = "sys_storage_threshold_percentage";
        public static final String TETHER_DUN_APN = "tether_dun_apn";
        public static final String TETHER_DUN_REQUIRED = "tether_dun_required";
        public static final String TETHER_SUPPORTED = "tether_supported";
        public static final String TZINFO_UPDATE_CONTENT_URL = "tzinfo_update_content_url";
        public static final String TZINFO_UPDATE_METADATA_URL = "tzinfo_update_metadata_url";
        public static final String UNLOCK_SOUND = "unlock_sound";
        public static final String UPDATABLE_DRIVER_ALL_APPS = "updatable_driver_all_apps";
        public static final String UPDATABLE_DRIVER_PRERELEASE_OPT_IN_APPS = "updatable_driver_prerelease_opt_in_apps";
        public static final String UPDATABLE_DRIVER_PRODUCTION_ALLOWLIST = "updatable_driver_production_allowlist";
        public static final String UPDATABLE_DRIVER_PRODUCTION_DENYLIST = "updatable_driver_production_denylist";
        public static final String UPDATABLE_DRIVER_PRODUCTION_OPT_IN_APPS = "updatable_driver_production_opt_in_apps";
        public static final String UPDATABLE_DRIVER_PRODUCTION_OPT_OUT_APPS = "updatable_driver_production_opt_out_apps";
        public static final String USB_MASS_STORAGE_ENABLED = "usb_mass_storage_enabled";
        public static final String USE_GOOGLE_MAIL = "use_google_mail";
        public static final String WAIT_FOR_DEBUGGER = "wait_for_debugger";
        public static final String WEBVIEW_DATA_REDUCTION_PROXY_KEY = "webview_data_reduction_proxy_key";
        public static final String WIFI_COUNTRY_CODE = "wifi_country_code";
        public static final String WIFI_ENHANCED_AUTO_JOIN = "wifi_enhanced_auto_join";
        public static final String WIFI_FRAMEWORK_SCAN_INTERVAL_MS = "wifi_framework_scan_interval_ms";
        public static final String WIFI_FREQUENCY_BAND = "wifi_frequency_band";
        public static final String WIFI_IDLE_MS = "wifi_idle_ms";
        public static final String WIFI_MAX_DHCP_RETRY_COUNT = "wifi_max_dhcp_retry_count";
        public static final String WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS = "wifi_mobile_data_transition_wakelock_timeout_ms";
        public static final String WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON = "wifi_networks_available_notification_on";
        public static final String WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY = "wifi_networks_available_repeat_delay";
        public static final String WIFI_NETWORK_SHOW_RSSI = "wifi_network_show_rssi";
        public static final String WIFI_NUM_OPEN_NETWORKS_KEPT = "wifi_num_open_networks_kept";
        public static final String WIFI_ON = "wifi_on";
        public static final String WIFI_P2P_DEVICE_NAME = "wifi_p2p_device_name";
        public static final String WIFI_P2P_PENDING_FACTORY_RESET = "wifi_p2p_pending_factory_reset";
        public static final String WIFI_SLEEP_POLICY = "wifi_sleep_policy";
        public static final String WIFI_SLEEP_POLICY_DEFAULT = "wifi_sleep_policy_default";
        public static final String WIFI_SLEEP_POLICY_NEVER = "wifi_sleep_policy_never";
        public static final String WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED = "wifi_sleep_policy_never_while_plugged";
        public static final String WIFI_SUPPLICANT_SCAN_INTERVAL_MS = "wifi_supplicant_scan_interval_ms";
        public static final String WIFI_VERBOSE_LOGGING_ENABLED = "wifi_verbose_logging_enabled";
        public static final String WIFI_WATCHDOG_ON = "wifi_watchdog_on";
        public static final String WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED = "wifi_watchdog_poor_network_test_enabled";
        public static final String WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON = "wimax_networks_available_notification_on";
        public static final String WTF_IS_FATAL = "wtf_is_fatal";
        public static final String ZEN_DURATION = "zen_duration";
        public static final String ZEN_MODE_ALARMS = "zen_mode_alarms";
        public static final String ZEN_MODE_IMPORTANT_INTERRUPTIONS = "zen_mode_important_interruptions";
        public static final String ZEN_MODE_NO_INTERRUPTIONS = "zen_mode_no_interruptions";
        public static final String ZEN_MODE_OFF = "zen_mode_off";
        public static final String ANIMATOR_DURATION_SCALE = "animator_duration_scale";
        public static final String TRANSITION_ANIMATION_SCALE = "transition_animation_scale";
        public static final String WINDOW_ANIMATION_SCALE = "window_animation_scale";
        public static final String SQLITE_COMPATIBILITY_WAL_FLAGS = "sqlite_compatibility_wal_flags";
        public static String getString(ContentResolver cr, String name) { return null; }
        public static float getFloat(ContentResolver cr, String name, float def) { return def; }
        public static int getInt(ContentResolver cr, String name, int def) { return def; }
        public static long getLong(ContentResolver cr, String name, long def) { return def; }
        public static boolean putInt(ContentResolver cr, String name, int value) { return false; }
        public static boolean putFloat(ContentResolver cr, String name, float value) { return false; }
        public static boolean putString(ContentResolver cr, String name, String value) { return false; }
        private Global() {}
    }
    public static final class System {
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) { return def; }
        public static boolean putIntForUser(ContentResolver cr, String name, int value, int userHandle) { return false; }
        public static String getStringForUser(ContentResolver cr, String name, int userHandle) { return null; }
        public static boolean putStringForUser(ContentResolver cr, String name, String value, int userHandle) { return false; }
        public static float getFloatForUser(ContentResolver cr, String name, float def, int userHandle) { return def; }
        public static android.net.Uri getUriFor(String name) { return android.net.Uri.parse("content://settings/" + name); }

        public static final String ACCELEROMETER_ROTATION = "accelerometer_rotation";
        public static final String FOLD_LOCK_BEHAVIOR = "fold_lock_behavior";
        public static final String MOUSE_REVERSE_VERTICAL_SCROLLING = "mouse_reverse_vertical_scrolling";
        public static final String MOUSE_SWAP_PRIMARY_BUTTON = "mouse_swap_primary_button";
        public static final String POINTER_SPEED = "pointer_speed";
        public static final String SCREEN_BRIGHTNESS = "screen_brightness";
        public static final String SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode";
        public static final String SCREEN_OFF_TIMEOUT = "screen_off_timeout";
        public static final String TEXT_AUTO_CAPS = "text_auto_caps";
        public static final String TEXT_AUTO_PUNCTUATE = "text_auto_punctuate";
        public static final String TEXT_AUTO_REPLACE = "text_auto_replace";
        public static final String TEXT_SHOW_PASSWORD = "text_show_password";
        public static final String TOUCHPAD_NATURAL_SCROLLING = "touchpad_natural_scrolling";
        public static final String TOUCHPAD_POINTER_SPEED = "touchpad_pointer_speed";
        public static final String TOUCHPAD_RIGHT_CLICK_ZONE = "touchpad_right_click_zone";
        public static final String TOUCHPAD_SYSTEM_GESTURES = "touchpad_system_gestures";
        public static final String TOUCHPAD_TAP_DRAGGING = "touchpad_tap_dragging";
        public static final String TOUCHPAD_TAP_TO_CLICK = "touchpad_tap_to_click";
        public static final String TOUCHPAD_THREE_FINGER_TAP_CUSTOMIZATION = "touchpad_three_finger_tap_customization";
        public static final String TOUCHPAD_VISUALIZER = "touchpad_visualizer";
        public static final String TIME_12_24 = "time_12_24";
        public static final android.net.Uri CONTENT_URI = android.net.Uri.parse("content://settings/system");
        public static final android.net.Uri DEFAULT_NOTIFICATION_URI = android.net.Uri.parse("content://settings/system/notification_sound");
        public static final android.net.Uri DEFAULT_RINGTONE_URI = android.net.Uri.parse("content://settings/system/ringtone");
        public static final android.net.Uri DEFAULT_ALARM_ALERT_URI = android.net.Uri.parse("content://settings/system/alarm_alert");
        public static final String HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY = "hide_rotation_lock_toggle_for_accessibility";
        public static String getString(ContentResolver cr, String name) { return null; }
        public static float getFloat(ContentResolver cr, String name, float def) { return def; }
        public static int getInt(ContentResolver cr, String name, int def) { return def; }
        public static boolean putInt(ContentResolver cr, String name, int value) { return false; }
        public static boolean putFloat(ContentResolver cr, String name, float value) { return false; }
        public static boolean putString(ContentResolver cr, String name, String value) { return false; }
        private System() {}
    }
    public static final class Secure {
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) { return def; }
        public static boolean putIntForUser(ContentResolver cr, String name, int value, int userHandle) { return false; }
        public static String getStringForUser(ContentResolver cr, String name, int userHandle) { return null; }
        public static boolean putStringForUser(ContentResolver cr, String name, String value, int userHandle) { return false; }
        public static float getFloatForUser(ContentResolver cr, String name, float def, int userHandle) { return def; }
        public static android.net.Uri getUriFor(String name) { return android.net.Uri.parse("content://settings/" + name); }

        public static final String ACCESSIBILITY_BOUNCE_KEYS = "accessibility_bounce_keys";
        public static final String ACCESSIBILITY_BUTTON_MODE = "accessibility_button_mode";
        public static final String ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU = "accessibility_button_mode_floating_menu";
        public static final String ACCESSIBILITY_BUTTON_MODE_GESTURE = "accessibility_button_mode_gesture";
        public static final String ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR = "accessibility_button_mode_navigation_bar";
        public static final String ACCESSIBILITY_BUTTON_TARGETS = "accessibility_button_targets";
        public static final String ACCESSIBILITY_BUTTON_TARGET_COMPONENT = "accessibility_button_target_component";
        public static final String ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR = "accessibility_captioning_background_color";
        public static final String ACCESSIBILITY_CAPTIONING_EDGE_COLOR = "accessibility_captioning_edge_color";
        public static final String ACCESSIBILITY_CAPTIONING_EDGE_TYPE = "accessibility_captioning_edge_type";
        public static final String ACCESSIBILITY_CAPTIONING_ENABLED = "accessibility_captioning_enabled";
        public static final String ACCESSIBILITY_CAPTIONING_FONT_SCALE = "accessibility_captioning_font_scale";
        public static final String ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR = "accessibility_captioning_foreground_color";
        public static final String ACCESSIBILITY_CAPTIONING_LOCALE = "accessibility_captioning_locale";
        public static final String ACCESSIBILITY_CAPTIONING_PRESET = "accessibility_captioning_preset";
        public static final String ACCESSIBILITY_CAPTIONING_TYPEFACE = "accessibility_captioning_typeface";
        public static final String ACCESSIBILITY_CAPTIONING_WINDOW_COLOR = "accessibility_captioning_window_color";
        public static final String ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
        public static final String ACCESSIBILITY_DISPLAY_INVERSION_ENABLED = "accessibility_display_inversion_enabled";
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED = "accessibility_display_magnification_navbar_enabled";
        public static final String ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED = "accessibility_force_invert_color_enabled";
        public static final String ACCESSIBILITY_GESTURE_TARGETS = "accessibility_gesture_targets";
        public static final String ACCESSIBILITY_KEY_GESTURE_TARGETS = "accessibility_key_gesture_targets";
        public static final String ACCESSIBILITY_MAGNIFICATION_MODE_ALL = "accessibility_magnification_mode_all";
        public static final String ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN = "accessibility_magnification_mode_fullscreen";
        public static final String ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW = "accessibility_magnification_mode_window";
        public static final String ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED = "accessibility_magnification_two_finger_triple_tap_enabled";
        public static final String ACCESSIBILITY_MOUSE_KEYS_ENABLED = "accessibility_mouse_keys_enabled";
        public static final String ACCESSIBILITY_QS_TARGETS = "accessibility_qs_targets";
        public static final String ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN = "accessibility_shortcut_dialog_shown";
        public static final String ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN = "accessibility_shortcut_on_lock_screen";
        public static final String ACCESSIBILITY_SHORTCUT_TARGET_SERVICE = "accessibility_shortcut_target_service";
        public static final String ACCESSIBILITY_SLOW_KEYS = "accessibility_slow_keys";
        public static final String ACCESSIBILITY_STICKY_KEYS = "accessibility_sticky_keys";
        public static final String ADAPTIVE_SLEEP = "adaptive_sleep";
        public static final String ANDROID_ID = "android_id";
        public static final String ASSISTANT = "assistant";
        public static final String ASSIST_DISCLOSURE_ENABLED = "assist_disclosure_enabled";
        public static final String BACK_GESTURE_INSET_SCALE_LEFT = "back_gesture_inset_scale_left";
        public static final String BACK_GESTURE_INSET_SCALE_RIGHT = "back_gesture_inset_scale_right";
        public static final String CAMERA_EXTENSIONS_FALLBACK = "camera_extensions_fallback";
        public static final String DOZE_ALWAYS_ON = "doze_always_on";
        public static final String DOZE_DOUBLE_TAP_GESTURE = "doze_double_tap_gesture";
        public static final String DOZE_ENABLED = "doze_enabled";
        public static final String DOZE_PICK_UP_GESTURE = "doze_pick_up_gesture";
        public static final String DOZE_PULSE_ON_LONG_PRESS = "doze_pulse_on_long_press";
        public static final String DOZE_QUICK_PICKUP_GESTURE = "doze_quick_pickup_gesture";
        public static final String DOZE_TAP_SCREEN_GESTURE = "doze_tap_screen_gesture";
        public static final String DOZE_WAKE_DISPLAY_GESTURE = "doze_wake_display_gesture";
        public static final String DOZE_WAKE_LOCK_SCREEN_GESTURE = "doze_wake_lock_screen_gesture";
        public static final String ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services";
        public static final String FACE_UNLOCK_RE_ENROLL = "face_unlock_re_enroll";
        public static final String FONT_WEIGHT_ADJUSTMENT = "font_weight_adjustment";
        public static final String HTTP_PROXY = "http_proxy";
        public static final String INSTALL_NON_MARKET_APPS = "install_non_market_apps";
        public static final String KEY_REPEAT_DELAY_MS = "key_repeat_delay_ms";
        public static final String KEY_REPEAT_ENABLED = "key_repeat_enabled";
        public static final String KEY_REPEAT_TIMEOUT_MS = "key_repeat_timeout_ms";
        public static final String LOCATION_PROVIDERS_ALLOWED = "location_providers_allowed";
        public static final String LOCK_BIOMETRIC_WEAK_FLAGS = "lock_biometric_weak_flags";
        public static final String LOCK_PATTERN_ENABLED = "lock_pattern_enabled";
        public static final String LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED = "lock_pattern_tactile_feedback_enabled";
        public static final String LOCK_PATTERN_VISIBLE = "lock_pattern_visible";
        public static final String LOCK_SCREEN_OWNER_INFO = "lock_screen_owner_info";
        public static final String LOCK_SCREEN_OWNER_INFO_ENABLED = "lock_screen_owner_info_enabled";
        public static final String LOGGING_ID = "logging_id";
        public static final String LONG_PRESS_TIMEOUT = "long_press_timeout";
        public static final String MATCH_CONTENT_FRAME_RATE = "match_content_frame_rate";
        public static final String MULTI_PRESS_TIMEOUT = "multi_press_timeout";
        public static final String NAV_BAR_FORCE_VISIBLE = "nav_bar_force_visible";
        public static final String NEARBY_SHARING_COMPONENT = "nearby_sharing_component";
        public static final String NIGHT_DISPLAY_ACTIVATED = "night_display_activated";
        public static final String NIGHT_DISPLAY_AUTO_MODE = "night_display_auto_mode";
        public static final String NIGHT_DISPLAY_COLOR_TEMPERATURE = "night_display_color_temperature";
        public static final String NIGHT_DISPLAY_CUSTOM_END_TIME = "night_display_custom_end_time";
        public static final String NIGHT_DISPLAY_CUSTOM_START_TIME = "night_display_custom_start_time";
        public static final String ODI_CAPTIONS_ENABLED = "odi_captions_enabled";
        public static final String ODI_CAPTIONS_VOLUME_UI_ENABLED = "odi_captions_volume_ui_enabled";
        public static final String ONE_HANDED_MODE_ACTIVATED = "one_handed_mode_activated";
        public static final String PARENTAL_CONTROL_ENABLED = "parental_control_enabled";
        public static final String PARENTAL_CONTROL_LAST_UPDATE = "parental_control_last_update";
        public static final String PARENTAL_CONTROL_REDIRECT_URL = "parental_control_redirect_url";
        public static final String REDUCE_BRIGHT_COLORS_ACTIVATED = "reduce_bright_colors_activated";
        public static final String RELEASE_COMPRESS_BLOCKS_ON_INSTALL = "release_compress_blocks_on_install";
        public static final String SCREENSAVER_ENABLED = "screensaver_enabled";
        public static final String SCREEN_OFF_UNLOCK_UDFPS_ENABLED = "screen_off_unlock_udfps_enabled";
        public static final String SECURE_FRP_MODE = "secure_frp_mode";
        public static final String SETTINGS_CLASSNAME = "settings_classname";
        public static final String SHOW_NOTIFICATION_SNOOZE = "show_notification_snooze";
        public static final String SKIP_FIRST_USE_HINTS = "skip_first_use_hints";
        public static final String SMS_DEFAULT_APPLICATION = "sms_default_application";
        public static final String STYLUS_POINTER_ICON_ENABLED = "stylus_pointer_icon_enabled";
        public static final String SUPPRESS_DOZE = "suppress_doze";
        public static final String SYNC_PARENT_SOUNDS = "sync_parent_sounds";
        public static final String TV_USER_SETUP_COMPLETE = "tv_user_setup_complete";
        public static final String USER_SETUP_COMPLETE = "user_setup_complete";
        public static final String USE_GOOGLE_MAIL = "use_google_mail";
        public static final String WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON = "wifi_networks_available_notification_on";
        public static final String WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY = "wifi_networks_available_repeat_delay";
        public static final String WIFI_NUM_OPEN_NETWORKS_KEPT = "wifi_num_open_networks_kept";
        public static final String WIFI_ON = "wifi_on";
        public static final String WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE = "wifi_watchdog_acceptable_packet_loss_percentage";
        public static final String WIFI_WATCHDOG_AP_COUNT = "wifi_watchdog_ap_count";
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS = "wifi_watchdog_background_check_delay_ms";
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED = "wifi_watchdog_background_check_enabled";
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS = "wifi_watchdog_background_check_timeout_ms";
        public static final String WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT = "wifi_watchdog_initial_ignored_ping_count";
        public static final String WIFI_WATCHDOG_MAX_AP_CHECKS = "wifi_watchdog_max_ap_checks";
        public static final String WIFI_WATCHDOG_ON = "wifi_watchdog_on";
        public static final String WIFI_WATCHDOG_PING_COUNT = "wifi_watchdog_ping_count";
        public static final String WIFI_WATCHDOG_PING_DELAY_MS = "wifi_watchdog_ping_delay_ms";
        public static final String WIFI_WATCHDOG_PING_TIMEOUT_MS = "wifi_watchdog_ping_timeout_ms";
        public static final String SELECTED_INPUT_METHOD_SUBTYPE = "selected_input_method_subtype";
        public static final String DEFAULT_INPUT_METHOD = "default_input_method";
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED = "accessibility_display_magnification_enabled";
        public static String getString(ContentResolver cr, String name) { return null; }
        public static int getInt(ContentResolver cr, String name, int def) { return def; }
        public static boolean putInt(ContentResolver cr, String name, int value) { return false; }
        public static boolean putString(ContentResolver cr, String name, String value) { return false; }
                public static float getFloat(ContentResolver cr, String name, float def) { return def; }
        public static float getFloat(ContentResolver cr, String name) throws SettingNotFoundException {
            throw new SettingNotFoundException(name);
        }
private Secure() {}
    }
}
