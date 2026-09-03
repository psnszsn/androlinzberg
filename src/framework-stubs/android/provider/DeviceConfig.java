/* artzig stub: device config — all defaults. */
package android.provider;
import java.util.concurrent.Executor;
public final class DeviceConfig {
    public static final String NAMESPACE_AUTOFILL = "autofill";
    public static final String NAMESPACE_TELEPHONY = "telephony";
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";
    public static final String NAMESPACE_INPUT_NATIVE_BOOT = "input_native_boot";
    public static final String NAMESPACE_WIDGET = "widget";
    public static final String NAMESPACE_ANDROID = "android";
    public static final String NAMESPACE_TEXTCLASSIFIER = "textclassifier";
    public static final String NAMESPACE_CONSTRAIN_DISPLAY_APIS = "constrain_display_apis";
    public static final String NAMESPACE_WINDOW_MANAGER = "window_manager";
    public static final String NAMESPACE_CLIPBOARD = "clipboard";
    public static final String NAMESPACE_PRIVACY = "privacy";
    private DeviceConfig() {}
    public static String getString(String namespace, String name, String defaultValue) { return defaultValue; }
    public static boolean getBoolean(String namespace, String name, boolean defaultValue) { return defaultValue; }
    public static int getInt(String namespace, String name, int defaultValue) { return defaultValue; }
    public static long getLong(String namespace, String name, long defaultValue) { return defaultValue; }
    public static float getFloat(String namespace, String name, float defaultValue) { return defaultValue; }
    public static String getProperty(String namespace, String name) { return null; }
    public static Properties getProperties(String namespace, String... names) { return new Properties(); }
    public static void addOnPropertiesChangedListener(String namespace, Executor executor,
            OnPropertiesChangedListener listener) {}
    public static void removeOnPropertiesChangedListener(OnPropertiesChangedListener listener) {}
    public interface OnPropertiesChangedListener { void onPropertiesChanged(Properties properties); }
    public static final int SYNC_DISABLED_MODE_NONE = 0;
    public static final int SYNC_DISABLED_MODE_PERSISTENT = 1;
    public static final int SYNC_DISABLED_MODE_UNTIL_REBOOT = 2;
    public static class BadConfigException extends Exception {}
    public interface MonitorCallback {
        void onNamespaceUpdate(String updatedNamespace);
        void onDeviceConfigAccess(String callingPackage, String namespace);
    }
    public static class Properties {
        public String getString(String name, String defaultValue) { return defaultValue; }
        public boolean getBoolean(String name, boolean defaultValue) { return defaultValue; }
        public int getInt(String name, int defaultValue) { return defaultValue; }
        public long getLong(String name, long defaultValue) { return defaultValue; }
        public float getFloat(String name, float defaultValue) { return defaultValue; }
        public java.util.Set<String> getKeyset() { return java.util.Collections.emptySet(); }
        public String getNamespace() { return ""; }
    }
}
