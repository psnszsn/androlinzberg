/* Hosted adaptation of android.content.pm.PackageManager: a client of the
 * session's package manager service (AOSP's ApplicationPackageManager shape).
 * Package state lives in the system process (dev.artzig.system
 * .HostedPackageManagerService); this class registers the packages parsed in
 * this process and answers every query from the service, keeping only the
 * records this process itself registered as a local cache. */
package android.content.pm;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedValue;
import com.android.internal.annotations.VisibleForTesting;
import java.util.Objects;
public abstract class PackageManager {
    /** AOSP PackageItemInfo.loadIcon/loadLabel resolve drawables/text through
     * this; resources of other packages are not served yet. */
    public android.graphics.drawable.Drawable getDrawable(String packageName, int resId,
            ApplicationInfo appInfo) { return null; }
    public android.content.res.XmlResourceParser getXml(String packageName, int resId,
            ApplicationInfo appInfo) { return null; }
    public android.graphics.drawable.Drawable loadItemIcon(PackageItemInfo itemInfo,
            ApplicationInfo appInfo) { return loadUnbadgedItemIcon(itemInfo, appInfo); }
    public android.graphics.drawable.Drawable loadUnbadgedItemIcon(PackageItemInfo itemInfo,
            ApplicationInfo appInfo) { return null; }
    public android.graphics.drawable.Drawable getUserBadgedIcon(
            android.graphics.drawable.Drawable drawable, android.os.UserHandle user) { return drawable; }
    public int getUserId() { return 0; }
    public android.graphics.drawable.Drawable getInstantAppIcon(String packageName) { return null; }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface DistractionRestriction {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface UninstallReason {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface InstallReason {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface EnabledState {}
    public static final boolean APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE = true;
    public static final boolean ENABLE_SHARED_UID_MIGRATION = true;
    public static final String PROPERTY_MEDIA_CAPABILITIES = "android.media.PROPERTY_MEDIA_CAPABILITIES";
    public static final String PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES = "android.net.PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES";
    public static final String PROPERTY_USE_RESTRICTED_BACKUP_MODE = "android.app.backup.PROPERTY_USE_RESTRICTED_BACKUP_MODE";
    public static final String PROPERTY_NO_APP_DATA_STORAGE = "android.internal.PROPERTY_NO_APP_DATA_STORAGE";
    public static final String PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE";
    public static final String PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT = "android.camera.PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT";
    public static final String PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST = "android.app.PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST";
    public static final String PROPERTY_ANDROID_SAFETY_LABEL = "android.content.PROPERTY_ANDROID_SAFETY_LABEL";
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_ACTIVITY = 1;
    public static final int TYPE_RECEIVER = 2;
    public static final int TYPE_SERVICE = 3;
    public static final int TYPE_PROVIDER = 4;
    public static final int TYPE_APPLICATION = 5;
    public static final int GET_DISABLED_COMPONENTS = 0x00000200;
    public static final int GET_UNINSTALLED_PACKAGES = 0x00002000;
    public static final int GET_CONFIGURATIONS = 0x00004000;
    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;
    public static final int MATCH_ALL = 0x00020000;
    public static final int MATCH_FACTORY_ONLY = 0x00200000;
    public static final int MATCH_ANY_USER = 0x00400000;
    public static final int MATCH_KNOWN_PACKAGES = 0x00002000 | 0x00400000;
    public static final int MATCH_INSTANT = 0x00800000;
    public static final int MATCH_VISIBLE_TO_INSTANT_APP_ONLY = 0x01000000;
    public static final int MATCH_EXPLICITLY_VISIBLE_ONLY = 0x02000000;
    public static final int MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000;
    public static final int MATCH_DIRECT_BOOT_AUTO = 0x10000000;
    public static final int MATCH_DEBUG_TRIAGED_MISSING = MATCH_DIRECT_BOOT_AUTO;
    public static final int MATCH_CLONE_PROFILE = 0x20000000;
    public static final int MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS = 0x20000000;
    public static final int GET_ATTRIBUTIONS = 0x80000000;
    public static final long GET_ATTRIBUTIONS_LONG = 0x80000000L;
    public static final long MATCH_ARCHIVED_PACKAGES = 1L << 32;
    public static final long MATCH_QUARANTINED_COMPONENTS = 1L << 33;
    public static final long MATCH_CLONE_PROFILE_LONG = 1L << 34;
    public static final int SKIP_CURRENT_PROFILE = 0x00000002;
    public static final int ONLY_IF_NO_MATCH_FOUND = 0x00000004;
    public static final int MODULE_APEX_NAME = 0x00000001;
    public static final int SIGNATURE_MATCH = 0;
    public static final int SIGNATURE_NEITHER_SIGNED = 1;
    public static final int SIGNATURE_FIRST_NOT_SIGNED = -1;
    public static final int SIGNATURE_SECOND_NOT_SIGNED = -2;
    public static final int SIGNATURE_NO_MATCH = -3;
    public static final int SIGNATURE_UNKNOWN_PACKAGE = -4;
    public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0;
    public static final int COMPONENT_ENABLED_STATE_ENABLED = 1;
    public static final int COMPONENT_ENABLED_STATE_DISABLED = 2;
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER = 3;
    public static final int COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED = 4;
    public static final int ROLLBACK_DATA_POLICY_RESTORE = 0;
    public static final int ROLLBACK_DATA_POLICY_WIPE = 1;
    public static final int ROLLBACK_DATA_POLICY_RETAIN = 2;
    public static final int ROLLBACK_USER_IMPACT_LOW = 0;
    public static final int ROLLBACK_USER_IMPACT_HIGH = 1;
    public static final int ROLLBACK_USER_IMPACT_ONLY_MANUAL = 2;
    public static final int INSTALL_REPLACE_EXISTING = 0x00000002;
    public static final int INSTALL_ALLOW_TEST = 0x00000004;
    public static final int INSTALL_INTERNAL = 0x00000010;
    public static final int INSTALL_FROM_ADB = 0x00000020;
    public static final int INSTALL_ALL_USERS = 0x00000040;
    public static final int INSTALL_REQUEST_DOWNGRADE = 0x00000080;
    public static final int INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS = 0x00000100;
    public static final int INSTALL_FORCE_VOLUME_UUID = 0x00000200;
    public static final int INSTALL_FORCE_PERMISSION_PROMPT = 0x00000400;
    public static final int INSTALL_INSTANT_APP = 0x00000800;
    public static final int INSTALL_DONT_KILL_APP = 0x00001000;
    public static final int INSTALL_FULL_APP = 0x00004000;
    public static final int INSTALL_ALLOCATE_AGGRESSIVE = 0x00008000;
    public static final int INSTALL_VIRTUAL_PRELOAD = 0x00010000;
    public static final int INSTALL_APEX = 0x00020000;
    public static final int INSTALL_ENABLE_ROLLBACK = 0x00040000;
    public static final int INSTALL_DISABLE_VERIFICATION = 0x00080000;
    public static final int INSTALL_ALLOW_DOWNGRADE = 0x00100000;
    public static final int INSTALL_STAGED = 0x00200000;
    public static final int INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS = 0x00400000;
    public static final int INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK = 0x00800000;
    public static final int INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK = 0x01000000;
    public static final int INSTALL_REQUEST_UPDATE_OWNERSHIP = 1 << 25;
    public static final int INSTALL_FROM_MANAGED_USER_OR_PROFILE = 1 << 26;
    public static final int INSTALL_ARCHIVED = 1 << 27;
    public static final int INSTALL_IGNORE_DEXOPT_PROFILE = 1 << 28;
    public static final int INSTALL_UNARCHIVE_DRAFT = 1 << 29;
    public static final int INSTALL_UNARCHIVE = 1 << 30;
    public static final int DONT_KILL_APP = 0x00000001;
    public static final int SYNCHRONOUS = 0x00000002;
    public static final int FLAG_SUSPEND_QUARANTINED = 0x00000001;
    public static final int UNINSTALL_REASON_UNKNOWN = 0;
    public static final int UNINSTALL_REASON_USER_TYPE = 1;
    public static final int NO_NATIVE_LIBRARIES = -114;
    public static final int USER_MIN_ASPECT_RATIO_UNSET = 0;
    public static final int USER_MIN_ASPECT_RATIO_SPLIT_SCREEN = 1;
    public static final int USER_MIN_ASPECT_RATIO_DISPLAY_SIZE = 2;
    public static final int USER_MIN_ASPECT_RATIO_4_3 = 3;
    public static final int USER_MIN_ASPECT_RATIO_16_9 = 4;
    public static final int USER_MIN_ASPECT_RATIO_3_2 = 5;
    public static final int USER_MIN_ASPECT_RATIO_FULLSCREEN = 6;
    public static final int USER_MIN_ASPECT_RATIO_APP_DEFAULT = 7;
    public static final int DELETE_KEEP_DATA = 0x00000001;
    public static final int DELETE_ALL_USERS = 0x00000002;
    public static final int DELETE_SYSTEM_APP = 0x00000004;
    public static final int DELETE_DONT_KILL_APP = 0x00000008;
    public static final int DELETE_ARCHIVE = 0x00000010;
    public static final int DELETE_CHATTY = 0x80000000;
    public static final int DELETE_SUCCEEDED = 1;
    public static final int DELETE_FAILED_INTERNAL_ERROR = -1;
    public static final int DELETE_FAILED_DEVICE_POLICY_MANAGER = -2;
    public static final int DELETE_FAILED_USER_RESTRICTED = -3;
    public static final int DELETE_FAILED_OWNER_BLOCKED = -4;
    public static final int DELETE_FAILED_ABORTED = -5;
    public static final int DELETE_FAILED_USED_SHARED_LIBRARY = -6;
    public static final int DELETE_FAILED_APP_PINNED = -7;
    public static final int DELETE_FAILED_FOR_CHILD_PROFILE = -8;
    public static final int MOVE_SUCCEEDED = -100;
    public static final int MOVE_FAILED_INSUFFICIENT_STORAGE = -1;
    public static final int MOVE_FAILED_DOESNT_EXIST = -2;
    public static final int MOVE_FAILED_SYSTEM_PACKAGE = -3;
    public static final int MOVE_FAILED_INVALID_LOCATION = -5;
    public static final int MOVE_FAILED_INTERNAL_ERROR = -6;
    public static final int MOVE_FAILED_OPERATION_PENDING = -7;
    public static final int MOVE_FAILED_DEVICE_ADMIN = -8;
    public static final int MOVE_FAILED_3RD_PARTY_NOT_ALLOWED_ON_INTERNAL = -9;
    public static final int MOVE_FAILED_LOCKED_USER = -10;
    public static final int MOVE_INTERNAL = 0x00000001;
    public static final int MOVE_EXTERNAL_MEDIA = 0x00000002;
    public static final String EXTRA_MOVE_ID = "android.content.pm.extra.MOVE_ID";
    public static final String EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT = "android.content.pm.extra.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT";
    public static final int VERIFICATION_ALLOW_WITHOUT_SUFFICIENT = 2;
    public static final int VERIFICATION_ALLOW = 1;
    public static final int VERIFICATION_REJECT = -1;
    public static final int INTENT_FILTER_VERIFICATION_SUCCESS = 1;
    public static final int INTENT_FILTER_VERIFICATION_FAILURE = -1;
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED = 0;
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK = 1;
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS = 2;
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER = 3;
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK = 4;
    public static final int APP_METADATA_SOURCE_UNKNOWN = 0;
    public static final int APP_METADATA_SOURCE_APK = 1;
    public static final int APP_METADATA_SOURCE_INSTALLER = 2;
    public static final int APP_METADATA_SOURCE_SYSTEM_IMAGE = 3;
    public static final String FEATURE_AUDIO_LOW_LATENCY = "android.hardware.audio.low_latency";
    public static final String FEATURE_AUDIO_OUTPUT = "android.hardware.audio.output";
    public static final String FEATURE_AUDIO_PRO = "android.hardware.audio.pro";
    public static final String FEATURE_AUDIO_SPATIAL_HEADTRACKING_LOW_LATENCY = "android.hardware.audio.spatial.headtracking.low_latency";
    public static final String FEATURE_BLUETOOTH = "android.hardware.bluetooth";
    public static final String FEATURE_BLUETOOTH_LE = "android.hardware.bluetooth_le";
    public static final String FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING = "android.hardware.bluetooth_le.channel_sounding";
    public static final String FEATURE_CAMERA = "android.hardware.camera";
    public static final String FEATURE_CAMERA_AUTOFOCUS = "android.hardware.camera.autofocus";
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any";
    public static final String FEATURE_CAMERA_EXTERNAL = "android.hardware.camera.external";
    public static final String FEATURE_CAMERA_FLASH = "android.hardware.camera.flash";
    public static final String FEATURE_CAMERA_FRONT = "android.hardware.camera.front";
    public static final String FEATURE_CAMERA_LEVEL_FULL = "android.hardware.camera.level.full";
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR = "android.hardware.camera.capability.manual_sensor";
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING = "android.hardware.camera.capability.manual_post_processing";
    public static final String FEATURE_CAMERA_CAPABILITY_RAW = "android.hardware.camera.capability.raw";
    public static final String FEATURE_CAMERA_AR = "android.hardware.camera.ar";
    public static final String FEATURE_CAMERA_CONCURRENT = "android.hardware.camera.concurrent";
    public static final String FEATURE_CONSUMER_IR = "android.hardware.consumerir";
    public static final String FEATURE_CONTEXT_HUB = "android.hardware.context_hub";
    public static final String FEATURE_CTS = "android.software.cts";
    public static final String FEATURE_CAR_TEMPLATES_HOST = "android.software.car.templates_host";
    public static final String FEATURE_CAR_SPLITSCREEN_MULTITASKING = "android.software.car.splitscreen_multitasking";
    public static final String FEATURE_CAR_DISPLAY_COMPATIBILITY = "android.software.car.display_compatibility";
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE = "android.hardware.identity_credential";
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS = "android.hardware.identity_credential_direct_access";
    public static final String FEATURE_LOCATION = "android.hardware.location";
    public static final String FEATURE_LOCATION_GPS = "android.hardware.location.gps";
    public static final String FEATURE_LOCATION_NETWORK = "android.hardware.location.network";
    public static final String FEATURE_FELICA = "android.hardware.felica";
    public static final String FEATURE_RAM_LOW = "android.hardware.ram.low";
    public static final String FEATURE_RAM_NORMAL = "android.hardware.ram.normal";
    public static final String FEATURE_MICROPHONE = "android.hardware.microphone";
    public static final String FEATURE_NFC = "android.hardware.nfc";
    public static final String FEATURE_NFC_HCE = "android.hardware.nfc.hce";
    public static final String FEATURE_NFC_HOST_CARD_EMULATION = "android.hardware.nfc.hce";
    public static final String FEATURE_NFC_HOST_CARD_EMULATION_NFCF = "android.hardware.nfc.hcef";
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC = "android.hardware.nfc.uicc";
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE = "android.hardware.nfc.ese";
    public static final String FEATURE_NFC_CHARGING = "android.hardware.nfc.charging";
    public static final String FEATURE_NFC_BEAM = "android.sofware.nfc.beam";
    public static final String FEATURE_NFC_ANY = "android.hardware.nfc.any";
    public static final String FEATURE_SDK_SANDBOX_WORK_PROFILE_INSTALL = "android.software.sdksandbox.sdk_install_work_profile";
    public static final String FEATURE_SE_OMAPI_UICC = "android.hardware.se.omapi.uicc";
    public static final String FEATURE_SE_OMAPI_ESE = "android.hardware.se.omapi.ese";
    public static final String FEATURE_SE_OMAPI_SD = "android.hardware.se.omapi.sd";
    public static final String FEATURE_SECURITY_MODEL_COMPATIBLE = "android.hardware.security.model.compatible";
    public static final String FEATURE_OPENGLES_EXTENSION_PACK = "android.hardware.opengles.aep";
    public static final String FEATURE_VIRTUALIZATION_FRAMEWORK = "android.software.virtualization_framework";
    public static final String FEATURE_VULKAN_HARDWARE_LEVEL = "android.hardware.vulkan.level";
    public static final String FEATURE_VULKAN_HARDWARE_COMPUTE = "android.hardware.vulkan.compute";
    public static final String FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version";
    public static final String FEATURE_VULKAN_DEQP_LEVEL = "android.software.vulkan.deqp.level";
    public static final String FEATURE_OPENGLES_DEQP_LEVEL = "android.software.opengles.deqp.level";
    public static final String FEATURE_BROADCAST_RADIO = "android.hardware.broadcastradio";
    public static final String FEATURE_SECURE_LOCK_SCREEN = "android.software.secure_lock_screen";
    public static final String FEATURE_SENSOR_BAROMETER = "android.hardware.sensor.barometer";
    public static final String FEATURE_SENSOR_COMPASS = "android.hardware.sensor.compass";
    public static final String FEATURE_SENSOR_GYROSCOPE = "android.hardware.sensor.gyroscope";
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES = "android.hardware.sensor.accelerometer_limited_axes";
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES = "android.hardware.sensor.gyroscope_limited_axes";
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED = "android.hardware.sensor.accelerometer_limited_axes_uncalibrated";
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES_UNCALIBRATED = "android.hardware.sensor.gyroscope_limited_axes_uncalibrated";
    public static final String FEATURE_SENSOR_LIGHT = "android.hardware.sensor.light";
    public static final String FEATURE_SENSOR_PROXIMITY = "android.hardware.sensor.proximity";
    public static final String FEATURE_SENSOR_STEP_COUNTER = "android.hardware.sensor.stepcounter";
    public static final String FEATURE_SENSOR_STEP_DETECTOR = "android.hardware.sensor.stepdetector";
    public static final String FEATURE_SENSOR_HEART_RATE = "android.hardware.sensor.heartrate";
    public static final String FEATURE_SENSOR_HEART_RATE_ECG = "android.hardware.sensor.heartrate.ecg";
    public static final String FEATURE_SENSOR_RELATIVE_HUMIDITY = "android.hardware.sensor.relative_humidity";
    public static final String FEATURE_SENSOR_AMBIENT_TEMPERATURE = "android.hardware.sensor.ambient_temperature";
    public static final String FEATURE_SENSOR_HINGE_ANGLE = "android.hardware.sensor.hinge_angle";
    public static final String FEATURE_SENSOR_HEADING = "android.hardware.sensor.heading";
    public static final String FEATURE_SENSOR_DYNAMIC_HEAD_TRACKER = "android.hardware.sensor.dynamic.head_tracker";
    public static final String FEATURE_HIFI_SENSORS = "android.hardware.sensor.hifi_sensors";
    public static final String FEATURE_ASSIST_GESTURE = "android.hardware.sensor.assist";
    public static final String FEATURE_TELEPHONY = "android.hardware.telephony";
    public static final String FEATURE_TELEPHONY_CDMA = "android.hardware.telephony.cdma";
    public static final String FEATURE_TELEPHONY_GSM = "android.hardware.telephony.gsm";
    public static final String FEATURE_TELEPHONY_CARRIERLOCK = "android.hardware.telephony.carrierlock";
    public static final String FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc";
    public static final String FEATURE_TELEPHONY_EUICC_MEP = "android.hardware.telephony.euicc.mep";
    public static final String FEATURE_TELEPHONY_MBMS = "android.hardware.telephony.mbms";
    public static final String FEATURE_TELEPHONY_IMS = "android.hardware.telephony.ims";
    public static final String FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION = "android.hardware.telephony.ims.singlereg";
    public static final String FEATURE_TELECOM = "android.software.telecom";
    public static final String FEATURE_TELEPHONY_CALLING = "android.hardware.telephony.calling";
    public static final String FEATURE_TELEPHONY_DATA = "android.hardware.telephony.data";
    public static final String FEATURE_TELEPHONY_MESSAGING = "android.hardware.telephony.messaging";
    public static final String FEATURE_TELEPHONY_RADIO_ACCESS = "android.hardware.telephony.radio.access";
    public static final String FEATURE_TELEPHONY_SATELLITE = "android.hardware.telephony.satellite";
    public static final String FEATURE_TELEPHONY_SUBSCRIPTION = "android.hardware.telephony.subscription";
    public static final String FEATURE_THREAD_NETWORK = "android.hardware.thread_network";
    public static final String FEATURE_UWB = "android.hardware.uwb";
    public static final String FEATURE_USB_HOST = "android.hardware.usb.host";
    public static final String FEATURE_USB_ACCESSORY = "android.hardware.usb.accessory";
    public static final String FEATURE_SIP = "android.software.sip";
    public static final String FEATURE_SIP_VOIP = "android.software.sip.voip";
    public static final String FEATURE_CONNECTION_SERVICE = "android.software.connectionservice";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH = "android.hardware.touchscreen.multitouch";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND = "android.hardware.touchscreen.multitouch.jazzhand";
    public static final String FEATURE_FAKETOUCH = "android.hardware.faketouch";
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT = "android.hardware.faketouch.multitouch.distinct";
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND = "android.hardware.faketouch.multitouch.jazzhand";
    public static final String FEATURE_FACE = "android.hardware.biometrics.face";
    public static final String FEATURE_IRIS = "android.hardware.biometrics.iris";
    public static final String FEATURE_LIVE_WALLPAPER = "android.software.live_wallpaper";
    public static final String FEATURE_APP_WIDGETS = "android.software.app_widgets";
    public static final String FEATURE_CANT_SAVE_STATE = "android.software.cant_save_state";
    public static final String FEATURE_GAME_SERVICE = "android.software.game_service";
    public static final String FEATURE_VOICE_RECOGNIZERS = "android.software.voice_recognizers";
    public static final String FEATURE_HOME_SCREEN = "android.software.home_screen";
    public static final String FEATURE_DEVICE_ADMIN = "android.software.device_admin";
    public static final String FEATURE_LEANBACK_ONLY = "android.software.leanback_only";
    public static final String FEATURE_LIVE_TV = "android.software.live_tv";
    public static final String FEATURE_WIFI = "android.hardware.wifi";
    public static final String FEATURE_WIFI_DIRECT = "android.hardware.wifi.direct";
    public static final String FEATURE_WIFI_AWARE = "android.hardware.wifi.aware";
    public static final String FEATURE_WIFI_PASSPOINT = "android.hardware.wifi.passpoint";
    public static final String FEATURE_WIFI_RTT = "android.hardware.wifi.rtt";
    public static final String FEATURE_LOWPAN = "android.hardware.lowpan";
    public static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";
    public static final String FEATURE_EMBEDDED = "android.hardware.type.embedded";
    public static final String FEATURE_PC = "android.hardware.type.pc";
    public static final String FEATURE_PRINTING = "android.software.print";
    public static final String FEATURE_BACKUP = "android.software.backup";
    public static final String FEATURE_MANAGED_USERS = "android.software.managed_users";
    public static final String FEATURE_MANAGED_PROFILES = "android.software.managed_users";
    public static final String FEATURE_VERIFIED_BOOT = "android.software.verified_boot";
    public static final String FEATURE_WEBVIEW = "android.software.webview";
    public static final String FEATURE_ETHERNET = "android.hardware.ethernet";
    public static final String FEATURE_HDMI_CEC = "android.hardware.hdmi.cec";
    public static final String FEATURE_GAMEPAD = "android.hardware.gamepad";
    public static final String FEATURE_MIDI = "android.software.midi";
    public static final String FEATURE_VR_MODE = "android.software.vr.mode";
    public static final String FEATURE_VR_HEADTRACKING = "android.hardware.vr.headtracking";
    public static final String FEATURE_HARDWARE_KEYSTORE = "android.hardware.hardware_keystore";
    public static final String FEATURE_STRONGBOX_KEYSTORE = "android.hardware.strongbox_keystore";
    public static final String FEATURE_SLICES_DISABLED = "android.software.slices_disabled";
    public static final String FEATURE_DEVICE_UNIQUE_ATTESTATION = "android.hardware.device_unique_attestation";
    public static final String FEATURE_DEVICE_ID_ATTESTATION = "android.software.device_id_attestation";
    public static final String FEATURE_IPSEC_TUNNELS = "android.software.ipsec_tunnels";
    public static final String FEATURE_IPSEC_TUNNEL_MIGRATION = "android.software.ipsec_tunnel_migration";
    public static final String FEATURE_CONTROLS = "android.software.controls";
    public static final String FEATURE_REBOOT_ESCROW = "android.hardware.reboot_escrow";
    public static final String FEATURE_INCREMENTAL_DELIVERY = "android.software.incremental_delivery";
    public static final String FEATURE_EROFS_LEGACY = "android.software.erofs_legacy";
    public static final String FEATURE_EROFS = "android.software.erofs";
    public static final String FEATURE_TUNER = "android.hardware.tv.tuner";
    public static final String FEATURE_APP_ENUMERATION = "android.software.app_enumeration";
    public static final String FEATURE_KEYSTORE_SINGLE_USE_KEY = "android.hardware.keystore.single_use_key";
    public static final String FEATURE_KEYSTORE_LIMITED_USE_KEY = "android.hardware.keystore.limited_use_key";
    public static final String FEATURE_KEYSTORE_APP_ATTEST_KEY = "android.hardware.keystore.app_attest_key";
    public static final String FEATURE_APP_COMPAT_OVERRIDES = "android.software.app_compat_overrides";
    public static final String FEATURE_COMMUNAL_MODE = "android.software.communal_mode";
    public static final String FEATURE_DREAM_OVERLAY = "android.software.dream_overlay";
    public static final String FEATURE_WINDOW_MAGNIFICATION = "android.software.window_magnification";
    public static final String FEATURE_CREDENTIALS = "android.software.credentials";
    public static final String FEATURE_DEVICE_LOCK = "android.software.device_lock";
    public static final String FEATURE_WALLET_LOCATION_BASED_SUGGESTIONS = "android.software.wallet_location_based_suggestions";
    public static final String FEATURE_ROTARY_ENCODER_LOW_RES = "android.hardware.rotaryencoder.lowres";
    public static final String FEATURE_XR_INPUT_CONTROLLER = "android.hardware.xr.input.controller";
    public static final String FEATURE_XR_INPUT_HAND_TRACKING = "android.hardware.xr.input.hand_tracking";
    public static final String FEATURE_XR_INPUT_EYE_TRACKING = "android.hardware.xr.input.eye_tracking";
    public static final String FEATURE_XR_API_OPENXR = "android.software.xr.api.openxr";
    public static final String FEATURE_XR_API_SPATIAL = "android.software.xr.api.spatial";
    public static final boolean APP_ENUMERATION_ENABLED_BY_DEFAULT = true;
    public static final String EXTRA_VERIFICATION_URI = "android.content.pm.extra.VERIFICATION_URI";
    public static final String EXTRA_VERIFICATION_ID = "android.content.pm.extra.VERIFICATION_ID";
    public static final String EXTRA_VERIFICATION_LONG_VERSION_CODE = "android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE";
    public static final String EXTRA_VERIFICATION_ROOT_HASH = "android.content.pm.extra.VERIFICATION_ROOT_HASH";
    public static final String ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS";
    public static final String ACTION_REQUEST_PERMISSIONS_FOR_OTHER = "android.content.pm.action.REQUEST_PERMISSIONS_FOR_OTHER";
    public static final String EXTRA_REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    public static final String EXTRA_REQUEST_PERMISSIONS_DEVICE_ID = "android.content.pm.extra.REQUEST_PERMISSIONS_DEVICE_ID";
    public static final int FLAG_PERMISSION_USER_SET = 1 << 0;
    public static final int FLAG_PERMISSION_USER_FIXED = 1 << 1;
    public static final int FLAG_PERMISSION_POLICY_FIXED = 1 << 2;
    public static final int FLAG_PERMISSION_REVOKE_ON_UPGRADE = 1 << 3;
    public static final int FLAG_PERMISSION_SYSTEM_FIXED = 1 << 4;
    public static final int FLAG_PERMISSION_GRANTED_BY_DEFAULT = 1 << 5;
    public static final int FLAG_PERMISSION_REVIEW_REQUIRED = 1 << 6;
    public static final int FLAG_PERMISSION_REVOKE_WHEN_REQUESTED = 1 << 7;
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED = 1 << 8;
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED = 1 << 9;
    public static final int FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT = 1 << 11;
    public static final int FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT = 1 << 12;
    public static final int FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT = 1 << 13;
    public static final int FLAG_PERMISSION_APPLY_RESTRICTION = 1 << 14;
    public static final int FLAG_PERMISSION_GRANTED_BY_ROLE = 1 << 15;
    public static final int FLAG_PERMISSION_REVOKED_COMPAT = FLAG_PERMISSION_REVOKE_ON_UPGRADE;
    public static final int FLAG_PERMISSION_ONE_TIME = 1 << 16;
    public static final int FLAG_PERMISSION_AUTO_REVOKED = 1 << 17;
    public static final int FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY = 1 << 19;
    public static final int FLAGS_PERMISSION_RESERVED_PERMISSION_CONTROLLER = 1 << 28 | 1 << 29 | 1 << 30 | 1 << 31;
    public static final int FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT = FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT | FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
    public static final int MASK_PERMISSION_FLAGS = 0xFF;
    public static final int MASK_PERMISSION_FLAGS_ALL = FLAG_PERMISSION_USER_SET | FLAG_PERMISSION_USER_FIXED | FLAG_PERMISSION_POLICY_FIXED | FLAG_PERMISSION_REVOKE_ON_UPGRADE | FLAG_PERMISSION_SYSTEM_FIXED | FLAG_PERMISSION_GRANTED_BY_DEFAULT | FLAG_PERMISSION_REVIEW_REQUIRED | FLAG_PERMISSION_REVOKE_WHEN_REQUESTED | FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED | FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED | FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT | FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT | FLAG_PERMISSION_APPLY_RESTRICTION | FLAG_PERMISSION_GRANTED_BY_ROLE | FLAG_PERMISSION_REVOKED_COMPAT | FLAG_PERMISSION_ONE_TIME | FLAG_PERMISSION_AUTO_REVOKED;
    public static final int FLAG_PERMISSION_WHITELIST_SYSTEM = 1 << 0;
    public static final int FLAG_PERMISSION_WHITELIST_INSTALLER = 1 << 1;
    public static final int FLAG_PERMISSION_WHITELIST_UPGRADE = 1 << 2;
    public static final String SYSTEM_SHARED_LIBRARY_SERVICES = "android.ext.services";
    public static final String SYSTEM_SHARED_LIBRARY_SHARED = "android.ext.shared";
    public static final int NOTIFY_PACKAGE_USE_ACTIVITY = 0;
    public static final int NOTIFY_PACKAGE_USE_SERVICE = 1;
    public static final int NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE = 2;
    public static final int NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER = 3;
    public static final int NOTIFY_PACKAGE_USE_CONTENT_PROVIDER = 4;
    public static final int NOTIFY_PACKAGE_USE_BACKUP = 5;
    public static final int NOTIFY_PACKAGE_USE_CROSS_PACKAGE = 6;
    public static final int NOTIFY_PACKAGE_USE_INSTRUMENTATION = 7;
    public static final int NOTIFY_PACKAGE_USE_REASONS_COUNT = 8;
    public static final int VERSION_CODE_HIGHEST = -1;
    public static final long FILTER_APPLICATION_QUERY = 135549675L;
    public static final int SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN = 0;
    public static final int SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_VISIBLE = 1;
    public static final int SYSTEM_APP_STATE_INSTALLED = 2;
    public static final int SYSTEM_APP_STATE_UNINSTALLED = 3;
    public static final String PROPERTY_ALLOW_ADB_BACKUP = "android.backup.ALLOW_ADB_BACKUP";
    public static final int RESTRICTION_NONE = 0x0;
    public static final int RESTRICTION_HIDE_FROM_SUGGESTIONS = 0x00000001;
    public static final int RESTRICTION_HIDE_NOTIFICATIONS = 0x00000002;
    public static final int CERT_INPUT_RAW_X509 = 0;
    public static final int CERT_INPUT_SHA256 = 1;
    public static final int GET_RECEIVERS = 0x00000002;
    public static final int GET_PROVIDERS = 0x00000008;
    public static final int GET_INSTRUMENTATION = 0x00000010;
    public static final int GET_SIGNATURES = 0x00000040;
    public static final int GET_URI_PERMISSION_PATTERNS = 0x00000800;
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;
    // ----- interim verbatim-AOSP block
    public static final class ComponentEnabledSetting implements Parcelable {
        /**
         * The package name of the application to enable the setting.
         */
        private final String mPackageName;

        /**
         * The component name of the application to enable the setting.
         */
        private final ComponentName mComponentName;

        /**
         * The new enabled state
         */
        private final int mEnabledState;

        /**
         * The optional behavior flag
         */
        private final @EnabledFlags int mEnabledFlags;

        /**
         * Create an instance of the ComponentEnabledSetting for the component level's enabled
         * setting update.
         *
         * @param componentName The component name to update the enabled setting.
         * @param newState The new enabled state.
         * @param flags The optional behavior flags.
         */
        public ComponentEnabledSetting(ComponentName componentName,
                int newState, @EnabledFlags int flags) {
            mPackageName = null;
            mComponentName = Objects.requireNonNull(componentName);
            mEnabledState = newState;
            mEnabledFlags = flags;
        }

        /**
         * Create an instance of the ComponentEnabledSetting for the application level's enabled
         * setting update.
         *
         * @param packageName The package name to update the enabled setting.
         * @param newState The new enabled state.
         * @param flags The optional behavior flags.
         * @hide
         */
        public ComponentEnabledSetting(String packageName,
                int newState, @EnabledFlags int flags) {
            mPackageName = Objects.requireNonNull(packageName);
            mComponentName = null;
            mEnabledState = newState;
            mEnabledFlags = flags;
        }

        /**
         * Returns the package name of the setting.
         *
         * @return the package name.
         * @hide
         */
        public String getPackageName() {
            if (isComponent()) {
                return mComponentName.getPackageName();
            }
            return mPackageName;
        }

        /**
         * Returns the component class name of the setting.
         *
         * @return the class name.
         * @hide
         */
        public String getClassName() {
            if (isComponent()) {
                return mComponentName.getClassName();
            }
            return null;
        }

        /**
         * Whether or not this is for the component level's enabled setting update.
         *
         * @return {@code true} if it's the component level enabled setting update.
         * @hide
         */
        public boolean isComponent() {
            return mComponentName != null;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/PackageManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * The component name of the application to enable the setting.
         */
        public ComponentName getComponentName() {
            return mComponentName;
        }

        /**
         * The new enabled state
         */
        public int getEnabledState() {
            return mEnabledState;
        }

        /**
         * The optional behavior flag
         */
        public @EnabledFlags int getEnabledFlags() {
            return mEnabledFlags;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mPackageName != null) flg |= 0x1;
            if (mComponentName != null) flg |= 0x2;
            dest.writeByte(flg);
            if (mPackageName != null) dest.writeString(mPackageName);
            if (mComponentName != null) dest.writeTypedObject(mComponentName, flags);
            dest.writeInt(mEnabledState);
            dest.writeInt(mEnabledFlags);
        }

        @Override
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        /* package-private */ ComponentEnabledSetting(Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            String packageName = (flg & 0x1) == 0 ? null : in.readString();
            ComponentName componentName = (flg & 0x2) == 0 ? null : (ComponentName) in.readTypedObject(ComponentName.CREATOR);
            int enabledState = in.readInt();
            int enabledFlags = in.readInt();

            this.mPackageName = packageName;
            this.mComponentName = componentName;
            this.mEnabledState = enabledState;
            com.android.internal.util.AnnotationValidations.validate(
                    EnabledState.class, null, mEnabledState);
            this.mEnabledFlags = enabledFlags;
            com.android.internal.util.AnnotationValidations.validate(
                    EnabledFlags.class, null, mEnabledFlags);

            // onConstructed(); // You can define this method to get a callback
        }

        public static final Parcelable.Creator<ComponentEnabledSetting> CREATOR
                = new Parcelable.Creator<ComponentEnabledSetting>() {
            @Override
            public ComponentEnabledSetting[] newArray(int size) {
                return new ComponentEnabledSetting[size];
            }

            @Override
            public ComponentEnabledSetting createFromParcel(Parcel in) {
                return new ComponentEnabledSetting(in);
            }
        };

        //@formatter:on
        // End of generated code

    }
    // ----- interim verbatim-AOSP block continued (deleted with this file in Phase 5c):
    // the AOSP parsing stack references these members of the real
    // PackageManager, which this hosted client stands in for until the swap.
    public static final String APP_DETAILS_ACTIVITY_CLASS_NAME = "android.app.AppDetailsActivity";
    public static final int INSTALL_DEVELOPMENT_FORCE_NON_STAGED_APEX_UPDATE = 1;
    public static final int INSTALL_REASON_UNKNOWN = 0;
    public static final int INSTALL_REASON_POLICY = 1;
    public static final int INSTALL_REASON_DEVICE_RESTORE = 2;
    public static final int INSTALL_REASON_DEVICE_SETUP = 3;
    public static final int INSTALL_REASON_USER = 4;
    public static final int INSTALL_REASON_ROLLBACK = 5;
    public static final int INSTALL_SCENARIO_DEFAULT = 0;
    public static final int INSTALL_SCENARIO_FAST = 1;
    public static final int INSTALL_SCENARIO_BULK = 2;
    public static final int INSTALL_SCENARIO_BULK_SECONDARY = 3;
    public static final int INSTALL_UNKNOWN = 0;
    public static final int INSTALL_SUCCEEDED = 1;
    public static final int INSTALL_FAILED_ALREADY_EXISTS = -1;
    public static final int INSTALL_FAILED_INVALID_APK = -2;
    public static final int INSTALL_FAILED_INVALID_URI = -3;
    public static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;
    public static final int INSTALL_FAILED_DUPLICATE_PACKAGE = -5;
    public static final int INSTALL_FAILED_NO_SHARED_USER = -6;
    public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;
    public static final int INSTALL_FAILED_SHARED_USER_INCOMPATIBLE = -8;
    public static final int INSTALL_FAILED_MISSING_SHARED_LIBRARY = -9;
    public static final int INSTALL_FAILED_REPLACE_COULDNT_DELETE = -10;
    public static final int INSTALL_FAILED_DEXOPT = -11;
    public static final int INSTALL_FAILED_OLDER_SDK = -12;
    public static final int INSTALL_FAILED_CONFLICTING_PROVIDER = -13;
    public static final int INSTALL_FAILED_NEWER_SDK = -14;
    public static final int INSTALL_FAILED_TEST_ONLY = -15;
    public static final int INSTALL_FAILED_CPU_ABI_INCOMPATIBLE = -16;
    public static final int INSTALL_FAILED_MISSING_FEATURE = -17;
    public static final int INSTALL_FAILED_CONTAINER_ERROR = -18;
    public static final int INSTALL_FAILED_INVALID_INSTALL_LOCATION = -19;
    public static final int INSTALL_FAILED_MEDIA_UNAVAILABLE = -20;
    public static final int INSTALL_FAILED_VERIFICATION_TIMEOUT = -21;
    public static final int INSTALL_FAILED_VERIFICATION_FAILURE = -22;
    public static final int INSTALL_FAILED_PACKAGE_CHANGED = -23;
    public static final int INSTALL_FAILED_UID_CHANGED = -24;
    public static final int INSTALL_FAILED_VERSION_DOWNGRADE = -25;
    public static final int INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE = -26;
    public static final int INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE = -27;
    public static final int INSTALL_FAILED_MISSING_SPLIT = -28;
    public static final int INSTALL_FAILED_DEPRECATED_SDK_VERSION = -29;
    public static final int INSTALL_PARSE_FAILED_NOT_APK = -100;
    public static final int INSTALL_PARSE_FAILED_BAD_MANIFEST = -101;
    public static final int INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION = -102;
    public static final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;
    public static final int INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES = -104;
    public static final int INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING = -105;
    public static final int INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -106;
    public static final int INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID = -107;
    public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;
    public static final int INSTALL_PARSE_FAILED_MANIFEST_EMPTY = -109;
    public static final int INSTALL_FAILED_INTERNAL_ERROR = -110;
    public static final int INSTALL_FAILED_USER_RESTRICTED = -111;
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION = -112;
    public static final int INSTALL_FAILED_NO_MATCHING_ABIS = -113;
    public static final int INSTALL_FAILED_ABORTED = -115;
    public static final int INSTALL_FAILED_SESSION_INVALID = -116;
    public static final int INSTALL_FAILED_BAD_DEX_METADATA = -117;
    public static final int INSTALL_FAILED_BAD_SIGNATURE = -118;
    public static final int INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS = -119;
    public static final int INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY = -120;
    public static final int INSTALL_FAILED_WRONG_INSTALLED_VERSION = -121;
    public static final int INSTALL_FAILED_PROCESS_NOT_DEFINED = -122;
    public static final int INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED = -124;
    public static final int INSTALL_PARSE_FAILED_SKIPPED = -125;
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP = -126;
    public static final int INSTALL_FAILED_BAD_PERMISSION_GROUP = -127;
    public static final int INSTALL_ACTIVATION_FAILED = -128;
    public static final int INSTALL_FAILED_PRE_APPROVAL_NOT_AVAILABLE = -129;
    public static final int INSTALL_FAILED_SHARED_LIBRARY_BAD_CERTIFICATE_DIGEST = -130;
    public static final int INSTALL_FAILED_MULTI_ARCH_NOT_MATCH_ALL_NATIVE_ABIS = -131;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface EnabledFlags {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ComponentInfoFlagsBits {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ApplicationInfoFlagsBits {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PackageInfoFlagsBits {}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ResolveInfoFlagsBits {}

    public static final class Property implements Parcelable {
        private static final int TYPE_BOOLEAN = 1;
        private static final int TYPE_FLOAT = 2;
        private static final int TYPE_INTEGER = 3;
        private static final int TYPE_RESOURCE = 4;
        private static final int TYPE_STRING = 5;
        private final String mName;
        private final int mType;
        private final String mClassName;
        private final String mPackageName;
        private boolean mBooleanValue;
        private float mFloatValue;
        private int mIntegerValue;
        private String mStringValue;

        /** @hide */
        @VisibleForTesting
        public Property(String name, int type,
                String packageName, String className) {
            if (type < TYPE_BOOLEAN || type > TYPE_STRING) {
                throw new IllegalArgumentException("Invalid type");
            }
            this.mName = Objects.requireNonNull(name);
            this.mType = type;
            this.mPackageName = Objects.requireNonNull(packageName);
            this.mClassName = className;
        }
        /** @hide */
        public Property(String name, boolean value,
                String packageName, String className) {
            this(name, TYPE_BOOLEAN, packageName, className);
            mBooleanValue = value;
        }
        /** @hide */
        public Property(String name, float value,
                String packageName, String className) {
            this(name, TYPE_FLOAT, packageName, className);
            mFloatValue = value;
        }
        /** @hide */
        public Property(String name, int value, boolean isResource,
                String packageName, String className) {
            this(name, isResource ? TYPE_RESOURCE : TYPE_INTEGER, packageName, className);
            mIntegerValue = value;
        }
        /** @hide */
        public Property(String name, String value,
                String packageName, String className) {
            this(name, TYPE_STRING, packageName, className);
            mStringValue = value;
        }

        /** @hide */
        @VisibleForTesting
        public int getType() {
            return mType;
        }

        /**
         * Returns the name of the property.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the name of the package where this this property was defined.
         */
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Returns the classname of the component where this property was defined.
         * <p>If the property was defined within and &lt;application&gt; tag, returns
         * {@code null}
         */
        public String getClassName() {
            return mClassName;
        }

        /**
         * Returns the boolean value set for the property.
         * <p>If the property is not of a boolean type, returns {@code false}.
         */
        public boolean getBoolean() {
            return mBooleanValue;
        }

        /**
         * Returns {@code true} if the property is a boolean type. Otherwise {@code false}.
         */
        public boolean isBoolean() {
            return mType == TYPE_BOOLEAN;
        }

        /**
         * Returns the float value set for the property.
         * <p>If the property is not of a float type, returns {@code 0.0}.
         */
        public float getFloat() {
            return mFloatValue;
        }

        /**
         * Returns {@code true} if the property is a float type. Otherwise {@code false}.
         */
        public boolean isFloat() {
            return mType == TYPE_FLOAT;
        }

        /**
         * Returns the integer value set for the property.
         * <p>If the property is not of an integer type, returns {@code 0}.
         */
        public int getInteger() {
            return mType == TYPE_INTEGER ? mIntegerValue : 0;
        }

        /**
         * Returns {@code true} if the property is an integer type. Otherwise {@code false}.
         */
        public boolean isInteger() {
            return mType == TYPE_INTEGER;
        }

        /**
         * Returns the a resource id set for the property.
         * <p>If the property is not of a resource id type, returns {@code 0}.
         */
        public int getResourceId() {
            return mType == TYPE_RESOURCE ? mIntegerValue : 0;
        }

        /**
         * Returns {@code true} if the property is a resource id type. Otherwise {@code false}.
         */
        public boolean isResourceId() {
            return mType == TYPE_RESOURCE;
        }

        /**
         * Returns the a String value set for the property.
         * <p>If the property is not a String type, returns {@code null}.
         */
        public String getString() {
            return mStringValue;
        }

        /**
         * Returns {@code true} if the property is a String type. Otherwise {@code false}.
         */
        public boolean isString() {
            return mType == TYPE_STRING;
        }

        /**
         * Adds a mapping from the given key to this property's value in the provided
         * {@link android.os.Bundle}. If the provided {@link android.os.Bundle} is
         * {@code null}, creates a new {@link android.os.Bundle}.
         * @hide
         */
        public Bundle toBundle(Bundle outBundle) {
            final Bundle b = outBundle == null || outBundle == Bundle.EMPTY
                    ? new Bundle() : outBundle;
            if (mType == TYPE_BOOLEAN) {
                b.putBoolean(mName, mBooleanValue);
            } else if (mType == TYPE_FLOAT) {
                b.putFloat(mName, mFloatValue);
            } else if (mType == TYPE_INTEGER) {
                b.putInt(mName, mIntegerValue);
            } else if (mType == TYPE_RESOURCE) {
                b.putInt(mName, mIntegerValue);
            } else if (mType == TYPE_STRING) {
                b.putString(mName, mStringValue);
            }
            return b;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mName);
            dest.writeInt(mType);
            dest.writeString(mPackageName);
            dest.writeString(mClassName);
            if (mType == TYPE_BOOLEAN) {
                dest.writeBoolean(mBooleanValue);
            } else if (mType == TYPE_FLOAT) {
                dest.writeFloat(mFloatValue);
            } else if (mType == TYPE_INTEGER) {
                dest.writeInt(mIntegerValue);
            } else if (mType == TYPE_RESOURCE) {
                dest.writeInt(mIntegerValue);
            } else if (mType == TYPE_STRING) {
                dest.writeString(mStringValue);
            }
        }

        
        public static final Creator<Property> CREATOR = new Creator<Property>() {
            @Override
            public Property createFromParcel(Parcel source) {
                final String name = source.readString();
                final int type = source.readInt();
                final String packageName = source.readString();
                final String className = source.readString();
                if (type == TYPE_BOOLEAN) {
                    return new Property(name, source.readBoolean(), packageName, className);
                } else if (type == TYPE_FLOAT) {
                    return new Property(name, source.readFloat(), packageName, className);
                } else if (type == TYPE_INTEGER) {
                    return new Property(name, source.readInt(), false, packageName, className);
                } else if (type == TYPE_RESOURCE) {
                    return new Property(name, source.readInt(), true, packageName, className);
                } else if (type == TYPE_STRING) {
                    return new Property(name, source.readString(), packageName, className);
                }
                return null;
            }

            @Override
            public Property[] newArray(int size) {
                return new Property[size];
            }
        };

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Property)) {
                return false;
            }
            final Property property = (Property) obj;
            return mType == property.mType &&
                    Objects.equals(mName, property.mName) &&
                    Objects.equals(mClassName, property.mClassName) &&
                    Objects.equals(mPackageName, property.mPackageName) &&
                    (mType == TYPE_BOOLEAN ? mBooleanValue == property.mBooleanValue :
                     mType == TYPE_FLOAT ? Float.compare(mFloatValue, property.mFloatValue) == 0 :
                     mType == TYPE_INTEGER ? mIntegerValue == property.mIntegerValue :
                     mType == TYPE_RESOURCE ? mIntegerValue == property.mIntegerValue :
                     mStringValue.equals(property.mStringValue));
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(mName, mType, mClassName, mPackageName);
            if (mType == TYPE_BOOLEAN) {
                result = 31 * result + (mBooleanValue ? 1 : 0);
            } else if (mType == TYPE_FLOAT) {
                result = 31 * result + Float.floatToIntBits(mFloatValue);
            } else if (mType == TYPE_INTEGER) {
                result = 31 * result + mIntegerValue;
            } else if (mType == TYPE_RESOURCE) {
                result = 31 * result + mIntegerValue;
            } else if (mType == TYPE_STRING) {
                result = 31 * result + mStringValue.hashCode();
            }
            return result;
        }
    }


    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface PermissionResult {}
    // ------------------------------------------------------------------ PMS client
    private static volatile dev.artzig.system.IHostedPackageManager sService;
    private static dev.artzig.system.IHostedPackageManager service() {
        dev.artzig.system.IHostedPackageManager s = sService;
        if (s != null) return s;
        android.os.IBinder binder = android.os.ServiceManager.waitForService("package");
        if (binder == null) {
            throw new IllegalStateException("no hosted package manager in this session");
        }
        s = dev.artzig.system.IHostedPackageManager.Stub.asInterface(binder);
        sService = s;
        return s;
    }
    /** Packages this process registered, by name: the local cache (never stale —
     * the registering process is the source of truth for its own packages). */
    private static final java.util.concurrent.ConcurrentHashMap<String, dev.artzig.system.HostedInstalledPackage>
            sRegisteredHere = new java.util.concurrent.ConcurrentHashMap<>();
    private static dev.artzig.system.HostedInstalledPackage installedPackage(String packageName) {
        if (packageName == null) return null;
        dev.artzig.system.HostedInstalledPackage local = sRegisteredHere.get(packageName);
        if (local != null) return local;
        try {
            return service().getPackage(packageName);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }

    // ------------------------------------------------------- registration staging
    /* The configureHosted* entry points stage one package's parse results and
     * (re-)register the staged package with the service after every call, so
     * incremental callers (ApkHost, smokes) keep their call shape. */
    private static final Object sStageLock = new Object();
    private static String sStagedPackageName;
    private static int sStagedVersionCode;
    private static String sStagedVersionName;
    private static String sStagedSourceDir;
    private static String sStagedDataDir;
    private static int sStagedApplicationTheme;
    private static String sStagedApplicationClass;
    private static String sStagedLauncherActivity;
    private static java.util.List<ActivityInfo> sStagedActivities = new java.util.ArrayList<>();
    private static java.util.List<ProviderInfo> sStagedProviders = new java.util.ArrayList<>();
    private static java.util.List<ServiceInfo> sStagedServices = new java.util.ArrayList<>();
    private static java.util.Map<String, java.util.Set<String>> sStagedServiceActions =
            new java.util.HashMap<>();
    private static java.util.Set<String> sStagedRequestedPermissions =
            java.util.Collections.emptySet();
    private static java.util.Set<String> sStagedGrantedPermissions =
            java.util.Collections.emptySet();

    public static void configureHostedPackage(String packageName, int versionCode,
            String versionName, String sourceDir, String dataDir) {
        synchronized (sStageLock) {
            sStagedPackageName = packageName;
            sStagedVersionCode = versionCode;
            sStagedVersionName = versionName;
            sStagedSourceDir = sourceDir;
            sStagedDataDir = dataDir;
            sStagedApplicationTheme = 0;
            sStagedApplicationClass = null;
            sStagedLauncherActivity = null;
            sStagedActivities = new java.util.ArrayList<>();
            sStagedProviders = new java.util.ArrayList<>();
            sStagedServices = new java.util.ArrayList<>();
            sStagedServiceActions = new java.util.HashMap<>();
            sStagedRequestedPermissions = java.util.Collections.emptySet();
            sStagedGrantedPermissions = java.util.Collections.emptySet();
            registerStagedLocked();
        }
    }
    public static void configureHostedLauncher(String activityName) {
        synchronized (sStageLock) {
            sStagedLauncherActivity = activityName;
            registerStagedLocked();
        }
    }
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir) {
        configureHostedPackage(hostedPackage, sourceDir, dataDir,
                java.util.Collections.emptyMap());
    }
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        synchronized (sStageLock) {
            sStagedPackageName = hostedPackage.getPackageName();
            sStagedVersionCode = hostedPackage.getVersionCode();
            sStagedVersionName = hostedPackage.getVersionName();
            sStagedSourceDir = sourceDir;
            sStagedDataDir = dataDir;
            sStagedApplicationTheme = hostedPackage.getApplicationTheme();
            sStagedApplicationClass = hostedPackage.getApplicationClassName();
            sStagedLauncherActivity = hostedPackage.getLauncherActivityName();
            java.util.ArrayList<ActivityInfo> activities = new java.util.ArrayList<>();
            for (ActivityInfo activity : hostedPackage.getActivities().values()) {
                activities.add(new ActivityInfo(activity));
            }
            sStagedActivities = activities;
            java.util.ArrayList<ProviderInfo> providers = new java.util.ArrayList<>();
            for (ProviderInfo provider : hostedPackage.getProviders()) {
                providers.add(new ProviderInfo(provider));
            }
            sStagedProviders = providers;
            java.util.ArrayList<ServiceInfo> services = new java.util.ArrayList<>();
            for (ServiceInfo service : hostedPackage.getServices()) {
                services.add(new ServiceInfo(service));
            }
            sStagedServices = services;
            java.util.HashMap<String, java.util.Set<String>> actions = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, java.util.Set<String>> entry
                    : hostedPackage.getServiceActions().entrySet()) {
                actions.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
            }
            sStagedServiceActions = actions;
            stagePermissionsLocked(hostedPackage.getRequestedPermissions(),
                    hostedPackage.getDeclaredPermissionProtectionLevels(),
                    platformPermissionProtectionLevels);
            registerStagedLocked();
        }
    }
    /**
     * Hosted adaptation of PackageManagerService's install-time permission grant.
     * The full service cannot run without package installation state and the
     * permission controller, so the host grants only requested permissions whose
     * declaring manifest marks them with AOSP's normal protection base.
     */
    public static void configureHostedPermissions(java.util.Set<String> requestedPermissions,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        synchronized (sStageLock) {
            stagePermissionsLocked(requestedPermissions, packagePermissionProtectionLevels,
                    platformPermissionProtectionLevels);
            registerStagedLocked();
        }
    }
    private static void stagePermissionsLocked(java.util.Set<String> requestedPermissions,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        java.util.LinkedHashSet<String> requested = new java.util.LinkedHashSet<>();
        if (requestedPermissions != null) requested.addAll(requestedPermissions);
        sStagedRequestedPermissions = java.util.Collections.unmodifiableSet(requested);
        sStagedGrantedPermissions = java.util.Collections.unmodifiableSet(computeGrantedPermissions(
                requested, packagePermissionProtectionLevels, platformPermissionProtectionLevels));
    }
    public static java.util.LinkedHashSet<String> computeGrantedPermissions(
            java.util.Set<String> requested,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        java.util.HashMap<String, Integer> definitions = new java.util.HashMap<>();
        if (platformPermissionProtectionLevels != null) {
            definitions.putAll(platformPermissionProtectionLevels);
        }
        if (packagePermissionProtectionLevels != null) {
            definitions.putAll(packagePermissionProtectionLevels);
        }
        java.util.LinkedHashSet<String> granted = new java.util.LinkedHashSet<>();
        for (String permission : requested) {
            Integer protectionLevel = definitions.get(permission);
            if (protectionLevel != null && (protectionLevel.intValue() & 0x0f) == 0) {
                granted.add(permission);
            }
        }
        return granted;
    }
    public static void configureHostedProviders(java.util.List<ProviderInfo> providers) {
        synchronized (sStageLock) {
            java.util.ArrayList<ProviderInfo> copy = new java.util.ArrayList<>();
            if (providers != null) {
                for (ProviderInfo provider : providers) copy.add(new ProviderInfo(provider));
            }
            sStagedProviders = copy;
            registerStagedLocked();
        }
    }
    public static void configureHostedServices(java.util.List<ServiceInfo> services,
            java.util.Map<String, java.util.Set<String>> actionsByClassName) {
        synchronized (sStageLock) {
            java.util.ArrayList<ServiceInfo> serviceCopy = new java.util.ArrayList<>();
            if (services != null) {
                for (ServiceInfo service : services) serviceCopy.add(new ServiceInfo(service));
            }
            java.util.HashMap<String, java.util.Set<String>> actionCopy = new java.util.HashMap<>();
            if (actionsByClassName != null) {
                for (java.util.Map.Entry<String, java.util.Set<String>> entry
                        : actionsByClassName.entrySet()) {
                    actionCopy.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
                }
            }
            sStagedServices = serviceCopy;
            sStagedServiceActions = actionCopy;
            registerStagedLocked();
        }
    }
    private static void registerStagedLocked() {
        if (sStagedPackageName == null) return;
        PackageInfo info = new PackageInfo();
        info.packageName = sStagedPackageName;
        info.versionCode = sStagedVersionCode;
        info.versionName = sStagedVersionName;
        info.activities = sStagedActivities.toArray(new ActivityInfo[0]);
        info.services = sStagedServices.toArray(new ServiceInfo[0]);
        info.providers = sStagedProviders.toArray(new ProviderInfo[0]);
        info.requestedPermissions = sStagedRequestedPermissions.toArray(new String[0]);
        // The real AOSP ComponentInfo.writeToParcel requires applicationInfo.
        ApplicationInfo stagedApplication = new ApplicationInfo();
        stagedApplication.packageName = info.packageName;
        stagedApplication.processName = info.packageName;
        stagedApplication.theme = sStagedApplicationTheme;
        stagedApplication.sourceDir = sStagedSourceDir;
        stagedApplication.publicSourceDir = sStagedSourceDir;
        stagedApplication.dataDir = sStagedDataDir;
        info.applicationInfo = stagedApplication;
        for (ActivityInfo activity : info.activities) {
            if (activity.applicationInfo == null) activity.applicationInfo = stagedApplication;
        }
        for (ServiceInfo service : info.services) {
            if (service.applicationInfo == null) service.applicationInfo = stagedApplication;
        }
        for (ProviderInfo provider : info.providers) {
            if (provider.applicationInfo == null) provider.applicationInfo = stagedApplication;
        }
        android.os.Bundle actions = new android.os.Bundle();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry
                : sStagedServiceActions.entrySet()) {
            actions.putStringArrayList(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        dev.artzig.system.HostedInstalledPackage installed =
                new dev.artzig.system.HostedInstalledPackage(info, sStagedApplicationClass,
                        sStagedLauncherActivity, sStagedApplicationTheme, sStagedSourceDir,
                        sStagedDataDir, actions, new java.util.ArrayList<>(sStagedGrantedPermissions));
        try {
            service().registerPackage(installed);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
        sRegisteredHere.put(installed.packageInfo.packageName, installed);
    }
    /** Builds the installed-package record from a parse; used by the service's own installPackage. */
    public static dev.artzig.system.HostedInstalledPackage toInstalledPackage(
            HostedPackage hosted, String sourceDir, String dataDir,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        PackageInfo info = new PackageInfo();
        info.packageName = hosted.getPackageName();
        info.versionCode = hosted.getVersionCode();
        info.versionName = hosted.getVersionName();
        info.activities = hosted.getActivities().values().toArray(new ActivityInfo[0]);
        info.services = hosted.getServices().toArray(new ServiceInfo[0]);
        info.providers = hosted.getProviders().toArray(new ProviderInfo[0]);
        info.requestedPermissions = hosted.getRequestedPermissions().toArray(new String[0]);
        // The real AOSP ComponentInfo parcels applicationInfo (nested writes
        // elide it via PARCELABLE_ELIDE_DUPLICATES, bare ones need it set).
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = info.packageName;
        application.processName = info.packageName;
        application.theme = hosted.getApplicationTheme();
        application.sourceDir = sourceDir;
        application.publicSourceDir = sourceDir;
        application.dataDir = dataDir;
        info.applicationInfo = application;
        for (ActivityInfo activity : info.activities) activity.applicationInfo = application;
        for (ServiceInfo service : info.services) service.applicationInfo = application;
        for (ProviderInfo provider : info.providers) provider.applicationInfo = application;
        android.os.Bundle actions = new android.os.Bundle();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry
                : hosted.getServiceActions().entrySet()) {
            actions.putStringArrayList(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        java.util.LinkedHashSet<String> granted = computeGrantedPermissions(hosted.getRequestedPermissions(),
                hosted.getDeclaredPermissionProtectionLevels(), platformPermissionProtectionLevels);
        return new dev.artzig.system.HostedInstalledPackage(info,
                hosted.getApplicationClassName(), hosted.getLauncherActivityName(),
                hosted.getApplicationTheme(), sourceDir, dataDir, actions,
                new java.util.ArrayList<>(granted));
    }

    // ---------------------------------------------------------------- constants
    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;
    public static final int GET_SHARED_LIBRARY_FILES = 0x00000400;
    public static final int MATCH_DEFAULT_ONLY = 0x00010000;
    public static final int PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1;
    public static final int MATCH_DIRECT_BOOT_AWARE = 0x00080000, MATCH_DIRECT_BOOT_UNAWARE = 0x00040000;
    public static final int GET_META_DATA = 0x00000080, MATCH_SYSTEM_ONLY = 0x00100000;
    public static final String FEATURE_AUTOFILL = "android.software.autofill";
    public static final String FEATURE_INPUT_METHODS = "android.software.input_methods";
    public static final String FEATURE_TOUCHSCREEN = "android.hardware.touchscreen";
    public static final String FEATURE_SENSOR_ACCELEROMETER = "android.hardware.sensor.accelerometer";
    public static final String FEATURE_SCREEN_PORTRAIT = "android.hardware.screen.portrait";
    public static final String FEATURE_SCREEN_LANDSCAPE = "android.hardware.screen.landscape";
    public static final String FEATURE_FINGERPRINT = "android.hardware.fingerprint";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT = "android.hardware.touchscreen.multitouch.distinct";
    public static final int MATCH_DISABLED_COMPONENTS = 0x00000200;
    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;
    public static final int GET_ACTIVITIES = 0x00000001, GET_SERVICES = 0x00000004;
    public static final int GET_PERMISSIONS = 0x00001000;
    public static final class ResolveInfoFlags {
        private final long mValue;
        private ResolveInfoFlags(long value) { mValue = value; }
        public static ResolveInfoFlags of(long value) { return new ResolveInfoFlags(value); }
        public long getValue() { return mValue; }
    }
    public static final long MATCH_APEX = 0x40000000;
    public static final class PackageInfoFlags {
        private final long mValue;
        private PackageInfoFlags(long value) { mValue = value; }
        public static PackageInfoFlags of(long value) { return new PackageInfoFlags(value); }
        public long getValue() { return mValue; }
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, ResolveInfoFlags flags) {
        return queryIntentActivities(intent, (int) flags.getValue());
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, ResolveInfoFlags flags) {
        return queryIntentServices(intent, (int) flags.getValue());
    }
    public java.util.List<ResolveInfo> queryIntentServicesAsUser(android.content.Intent intent,
            ResolveInfoFlags flags, int userId) {
        return queryIntentServices(intent, (int) flags.getValue());
    }
    public static final String FEATURE_WATCH = "android.hardware.type.watch";
    public static final String FEATURE_LEANBACK = "android.software.leanback";
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";
    public static final String FEATURE_EXPANDED_PICTURE_IN_PICTURE =
            "android.software.expanded_picture_in_picture";
    public boolean hasSystemFeature(String featureName) { return false; }
    public boolean hasSystemFeature(String featureName, int version) { return false; }
    public FeatureInfo[] getSystemAvailableFeatures() { return new FeatureInfo[0]; }
    public boolean isInstantApp() { return false; }
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return new android.graphics.drawable.ColorDrawable(0);
    }
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) { return null; }

    // ------------------------------------------------------------------ queries
    public ResolveInfo resolveService(android.content.Intent intent, int flags) {
        java.util.List<ResolveInfo> matches = queryIntentServices(intent, flags);
        return matches.isEmpty() ? null : matches.get(0);
    }
    /**
     * Hosted adaptation of PackageManagerService intent resolution. Manifest activities are
     * resolved through the session's package manager; implicit desktop operations cross the
     * ActivityThread host seam, whose production adapter and in-memory test adapter provide
     * the two resolver implementations.
     */
    public ResolveInfo resolveActivity(android.content.Intent intent, int flags) {
        if (intent == null) return null;
        android.content.ComponentName component = intent.getComponent();
        if (component == null) {
            component = android.app.ActivityThread.resolveActivityInProcess(intent);
        }
        if (component == null) return null;

        ActivityInfo activity = findActivity(component);
        if (activity != null) {
            if (!activity.enabled && (flags & MATCH_DISABLED_COMPONENTS) == 0) return null;
            activity = new ActivityInfo(activity);
        } else {
            activity = new ActivityInfo();
            activity.name = component.getClassName();
            activity.packageName = component.getPackageName();
            activity.applicationInfo = installedApplicationInfo(component.getPackageName());
        }
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = activity;
        return result;
    }
    private static ActivityInfo findActivity(android.content.ComponentName component) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg == null || pkg.packageInfo.activities == null) return null;
        for (ActivityInfo activity : pkg.packageInfo.activities) {
            if (component.getClassName().equals(activity.name)) return activity;
        }
        return null;
    }
    public ActivityInfo getReceiverInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(component));
    }
    public java.util.List<ResolveInfo> queryIntentActivitiesAsUser(android.content.Intent intent,
            int flags, int userId) {
        return queryIntentActivities(intent, flags);
    }
    public java.util.List<ResolveInfo> queryIntentActivityOptions(android.content.ComponentName caller,
            android.content.Intent[] specifics, android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, int flags) {
        java.util.ArrayList<ResolveInfo> result = new java.util.ArrayList<>();
        ResolveInfo resolved = resolveActivity(intent, flags);
        if (resolved != null) result.add(resolved);
        return result;
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, int flags) {
        if (intent == null) return new java.util.ArrayList<>();
        try {
            return service().queryIntentServices(intent, flags);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public ServiceInfo getServiceInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg != null && pkg.packageInfo.services != null) {
            for (ServiceInfo service : pkg.packageInfo.services) {
                if (component.getClassName().equals(service.name)
                        && (service.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
                    return new ServiceInfo(service);
                }
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public android.content.Intent getLaunchIntentForPackage(String packageName) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        if (pkg == null || pkg.launcherActivityName == null) return null;
        return new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setPackage(packageName)
                .setComponent(new android.content.ComponentName(
                        packageName, pkg.launcherActivityName))
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
    public String[] getPackagesForUid(int uid) {
        try {
            return service().getPackagesForUid(uid);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public ActivityInfo getActivityInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        ActivityInfo activity = findActivity(component);
        if (activity != null && (activity.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
            return new ActivityInfo(activity);
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    private static ApplicationInfo installedApplicationInfo(String packageName) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        ApplicationInfo result = new ApplicationInfo();
        result.packageName = packageName;
        if (pkg != null) {
            result.theme = pkg.applicationTheme;
            result.sourceDir = pkg.apkPath;
            result.publicSourceDir = pkg.apkPath;
            result.dataDir = pkg.dataDir;
        }
        return result;
    }
    public ProviderInfo getProviderInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg != null && pkg.packageInfo.providers != null) {
            for (ProviderInfo provider : pkg.packageInfo.providers) {
                if (component.getClassName().equals(provider.name)) {
                    return new ProviderInfo(provider);
                }
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        if (pkg != null) {
            pi.versionCode = pkg.packageInfo.versionCode;
            pi.versionName = pkg.packageInfo.versionName;
        } else {
            pi.versionName = "1.0";
        }
        if (pkg != null && (flags & GET_PERMISSIONS) != 0
                && pkg.packageInfo.requestedPermissions != null) {
            pi.requestedPermissions = pkg.packageInfo.requestedPermissions.clone();
            pi.requestedPermissionsFlags = new int[pi.requestedPermissions.length];
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                pi.requestedPermissionsFlags[i] = PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                if (pkg.grantedPermissions.contains(pi.requestedPermissions[i])) {
                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
            }
        }
        pi.signingInfo = new SigningInfo();
        pi.signatures = new Signature[] { new Signature(new byte[] { 4, 2 }) };
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = packageName;
        pi.applicationInfo.processName = packageName;
        if (pkg != null) {
            pi.applicationInfo.theme = pkg.applicationTheme;
        }
        return pi;
    }
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageInfo(packageName, (int) flags.getValue());
    }
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return info.packageName != null ? info.packageName : "app";
    }
    public android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo info) {
        return getDefaultActivityIcon();
    }
    public android.graphics.drawable.Drawable getApplicationIcon(String packageName) {
        return getDefaultActivityIcon();
    }
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName activityName) {
        return getDefaultActivityIcon();
    }
    /**
     * Permission state of another uid; answered by the session's package
     * manager (source note: AOSP asks PermissionManagerService).
     */
    public static int checkPermissionForUid(String permName, int uid) {
        try {
            return service().checkUidPermission(permName, uid);
        } catch (android.os.RemoteException e) {
            android.util.Log.w("PackageManager", "permission " + permName + " for uid " + uid
                    + " denied: package manager unreachable", e);
            return PERMISSION_DENIED;
        }
    }
    public int checkPermission(String permName, String packageName) {
        if (permName == null) throw new IllegalArgumentException("permission is null");
        dev.artzig.system.HostedInstalledPackage local =
                packageName != null ? sRegisteredHere.get(packageName) : null;
        if (local != null) {
            return local.grantedPermissions.contains(permName) ? PERMISSION_GRANTED : PERMISSION_DENIED;
        }
        try {
            return service().checkPermission(permName, packageName);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public java.util.List<ResolveInfo> queryBroadcastReceivers(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        java.util.ArrayList<ProviderInfo> result = new java.util.ArrayList<>();
        try {
            for (String packageName : service().getInstalledPackageNames()) {
                dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
                if (pkg == null || pkg.packageInfo.providers == null) continue;
                if (uid >= 0 && service().getPackageUid(packageName) != uid) continue;
                for (ProviderInfo provider : pkg.packageInfo.providers) {
                    if (processName != null && provider.processName != null
                            && !processName.equals(provider.processName)) {
                        continue;
                    }
                    result.add(new ProviderInfo(provider));
                }
            }
        } catch (android.os.RemoteException e) {
            // Session teardown: no providers reachable.
        }
        return result;
    }
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (authority == null) return null;
        for (dev.artzig.system.HostedInstalledPackage pkg : sRegisteredHere.values()) {
            if (pkg.packageInfo.providers == null) continue;
            for (ProviderInfo provider : pkg.packageInfo.providers) {
                if (provider.authority == null) continue;
                for (String candidate : provider.authority.split(";")) {
                    if (authority.equals(candidate)) return new ProviderInfo(provider);
                }
            }
        }
        // Session-wide resolution through the package manager service.
        try {
            return service().resolveContentProvider(authority, flags);
        } catch (android.os.RemoteException e) {
            return null;
        }
    }
    public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) { return false; }
    public boolean hasSigningCertificate(int uid, byte[] certificate, int type) { return false; }
    public String[] getSystemSharedLibraryNames() { return new String[0]; }
    public String getInstallerPackageName(String packageName) { return null; }
    public java.util.List<PackageInfo> getInstalledPackages(int flags) { return new java.util.ArrayList<>(); }
    public java.util.List<ApplicationInfo> getInstalledApplications(int flags) { return new java.util.ArrayList<>(); }
    public void setComponentEnabledSetting(android.content.ComponentName componentName, int newState, int flags) {}
    public int getComponentEnabledSetting(android.content.ComponentName componentName) { return 0; }
    public ApplicationInfo getApplicationInfoAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        return getApplicationInfo(packageName, flags);
    }
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.name = packageName;
        info.processName = packageName;
        info.enabled = true;
        info.metaData = new android.os.Bundle();
        if (pkg != null) {
            info.sourceDir = pkg.apkPath;
            info.publicSourceDir = pkg.apkPath;
            info.dataDir = pkg.dataDir;
            info.theme = pkg.applicationTheme;
        }
        return info;
    }
    public android.content.res.Resources getResourcesForApplication(ApplicationInfo app)
            throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(app));
    }
    public android.content.res.Resources getResourcesForApplication(String packageName)
            throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }
    public static class NameNotFoundException extends android.util.AndroidException {
        public NameNotFoundException() {}
        public NameNotFoundException(String name) { super(name); }
    }
}
