/* Hosted adaptation of AOSP android.os.SystemProperties.
 *
 * Values live in the session's shared property area (`<session>/props`,
 * written only by the binder broker; see src/binder/prop_area.zig), mapped
 * read-only by every process — the analogue of bionic's
 * __system_property_area__ owned by init. Reads are lock-free with the
 * bionic torn-read serial protocol; set() is a broker message, like
 * property_service. Change callbacks fire on the broker's property_changed
 * push. */
package android.os;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class SystemProperties {
    public static final int PROP_NAME_MAX = Integer.MAX_VALUE;
    public static final int PROP_VALUE_MAX = 91;

    // Layout constants shared with src/binder/prop_area.zig.
    private static final int AREA_MAGIC = 0x504B5A41; // "AZKP"
    private static final int HEADER_LEN = 32;
    private static final int NAME_MAX = 128; // including NUL
    private static final int VALUE_MAX = 92; // including NUL
    private static final int SLOT_LEN = 4 + NAME_MAX + VALUE_MAX;
    private static final int OFF_COUNT = 12;

    private static volatile ByteBuffer sArea;
    /** Slots are append-only and never move, so offsets cache forever. */
    private static final ConcurrentHashMap<String, Integer> sSlots = new ConcurrentHashMap<>();
    private static final ArrayList<Runnable> sChangeCallbacks = new ArrayList<>();

    private SystemProperties() {}

    private static ByteBuffer area() {
        ByteBuffer a = sArea;
        if (a != null) return a;
        synchronized (SystemProperties.class) {
            if (sArea == null) sArea = mapArea();
            return sArea;
        }
    }

    private static ByteBuffer mapArea() {
        String path = System.getenv("AZK_PROP_AREA");
        if (path == null) {
            throw new IllegalStateException("AZK_PROP_AREA is not set; system properties need a hosted session");
        }
        try (FileInputStream in = new FileInputStream(path)) {
            FileChannel channel = in.getChannel();
            MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            map.order(ByteOrder.LITTLE_ENDIAN);
            if (map.getInt(0) != AREA_MAGIC) {
                throw new IllegalStateException("bad property area at " + path);
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("cannot map property area at " + path, e);
        }
    }

    /** Slot byte offset for the key, or -1. Absolute buffer reads only: the
     * mapping is shared between threads without positional state. */
    private static int findSlot(String key) {
        Integer cached = sSlots.get(key);
        if (cached != null) return cached;
        ByteBuffer a = area();
        byte[] name = key.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length >= NAME_MAX) return -1;
        int count = a.getInt(OFF_COUNT);
        for (int i = 0; i < count; i++) {
            int slot = HEADER_LEN + i * SLOT_LEN;
            if (nameMatches(a, slot + 4, name)) {
                sSlots.put(key, slot);
                return slot;
            }
        }
        return -1;
    }

    private static boolean nameMatches(ByteBuffer a, int off, byte[] name) {
        for (int i = 0; i < name.length; i++) {
            if (a.get(off + i) != name[i]) return false;
        }
        return a.get(off + name.length) == 0;
    }

    /** Torn-read protocol per prop_area.zig: the serial is odd while the
     * broker rewrites the value; retry until it is even and unchanged. The
     * retry is bounded: a broker killed mid-write leaves the serial odd
     * forever, and a possibly-torn (still NUL-terminated) value beats a hang. */
    private static String readSlot(int slot) {
        ByteBuffer a = area();
        byte[] value = new byte[VALUE_MAX];
        for (int attempt = 0; ; attempt++) {
            int before = a.getInt(slot);
            if ((before & 1) != 0 && attempt < 1000) {
                Thread.onSpinWait();
                continue;
            }
            int len = VALUE_MAX;
            for (int i = 0; i < VALUE_MAX; i++) {
                byte b = a.get(slot + 4 + NAME_MAX + i);
                if (b == 0) {
                    len = i;
                    break;
                }
                value[i] = b;
            }
            if (a.getInt(slot) == before || attempt >= 1000) {
                return new String(value, 0, len, StandardCharsets.UTF_8);
            }
        }
    }

    public static String get(String key) {
        int slot = findSlot(key);
        return slot < 0 ? "" : readSlot(slot);
    }

    public static String get(String key, String def) {
        String v = get(key);
        return v.isEmpty() ? def : v;
    }

    public static int getInt(String key, int def) {
        return parseInt(get(key), def);
    }

    public static long getLong(String key, long def) {
        return parseLong(get(key), def);
    }

    public static boolean getBoolean(String key, boolean def) {
        return parseBoolean(get(key), def);
    }

    /** Requests the write from the broker; policy lives there (system may set
     * anything but re-set an ro.*, apps only debug.* and log.*). */
    public static void set(String key, String val) {
        if (val == null) val = "";
        if (val.length() > PROP_VALUE_MAX && !key.startsWith("ro.")) {
            throw new IllegalArgumentException("value of system property '" + key
                    + "' is longer than " + PROP_VALUE_MAX + " characters: " + val);
        }
        if (!HostedBinderRuntime.get().setProperty(key, val)) {
            throw new RuntimeException("failed to set system property " + key);
        }
    }

    public static void addChangeCallback(Runnable callback) {
        synchronized (sChangeCallbacks) {
            sChangeCallbacks.add(callback);
        }
    }

    public static void removeChangeCallback(Runnable callback) {
        synchronized (sChangeCallbacks) {
            sChangeCallbacks.remove(callback);
        }
    }

    /** Runs the registered change callbacks; invoked on the broker's
     * property_changed push and callable directly like AOSP's. */
    public static void reportSyspropChanged() {
        Runnable[] callbacks;
        synchronized (sChangeCallbacks) {
            callbacks = sChangeCallbacks.toArray(new Runnable[0]);
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable t) {
                android.util.Log.w("SystemProperties", "property change callback failed", t);
            }
        }
    }

    /** SHA-1 of the sorted key=value lines, hex, per AOSP. */
    public static String digestOf(String... keys) {
        Arrays.sort(keys);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (String key : keys) {
                String item = key + "=" + get(key) + "\n";
                digest.update(item.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Pre-located property for optimal repeated lookup, as in AOSP. Null for
     * a name with no slot yet (a later find() can succeed after a set). */
    public static Handle find(String name) {
        int slot = findSlot(name);
        return slot < 0 ? null : new Handle(slot);
    }

    public static final class Handle {
        private final int mSlot;

        private Handle(int slot) {
            mSlot = slot;
        }

        public String get() {
            return readSlot(mSlot);
        }

        public int getInt(int def) {
            return parseInt(readSlot(mSlot), def);
        }

        public long getLong(long def) {
            return parseLong(readSlot(mSlot), def);
        }

        public boolean getBoolean(boolean def) {
            return parseBoolean(readSlot(mSlot), def);
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return v.isEmpty() ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return v.isEmpty() ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** AOSP truth table: 1/y/yes/on/true and 0/n/no/off/false; else def. */
    private static boolean parseBoolean(String v, boolean def) {
        if (v.isEmpty()) return def;
        switch (v) {
            case "1": case "y": case "yes": case "on": case "true":
                return true;
            case "0": case "n": case "no": case "off": case "false":
                return false;
            default:
                return def;
        }
    }
}
