/* artzig shim: android.os.SystemProperties — in-memory map. */
package android.os;
import java.util.concurrent.ConcurrentHashMap;
public class SystemProperties {
    public static final int PROP_NAME_MAX = Integer.MAX_VALUE;
    public static final int PROP_VALUE_MAX = 91;
    private static final ConcurrentHashMap<String, String> sProps = new ConcurrentHashMap<>();
    static {
        sProps.put("ro.build.version.sdk", "35");
        sProps.put("ro.build.version.release", "15");
        sProps.put("ro.build.version.codename", "REL");
        sProps.put("ro.hardware", "artzig");
        sProps.put("ro.product.cpu.abi", "x86_64");
        sProps.put("debug.force_rtl", "0");
    }
    public static String get(String key) { return sProps.getOrDefault(key, ""); }
    public static String get(String key, String def) { String v = sProps.get(key); return v != null && !v.isEmpty() ? v : def; }
    public static int getInt(String key, int def) {
        try { String v = sProps.get(key); return v != null ? Integer.parseInt(v) : def; }
        catch (NumberFormatException e) { return def; }
    }
    public static long getLong(String key, long def) {
        try { String v = sProps.get(key); return v != null ? Long.parseLong(v) : def; }
        catch (NumberFormatException e) { return def; }
    }
    public static boolean getBoolean(String key, boolean def) {
        String v = sProps.get(key);
        if (v == null || v.isEmpty()) return def;
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("y") || v.equalsIgnoreCase("yes")
                || (!v.equals("0") && !v.equalsIgnoreCase("false") && !v.equalsIgnoreCase("n") && !v.equalsIgnoreCase("no") && def);
    }
    public static void set(String key, String val) { if (val == null) sProps.remove(key); else sProps.put(key, val); }
    public static void addChangeCallback(Runnable cb) {}
}
