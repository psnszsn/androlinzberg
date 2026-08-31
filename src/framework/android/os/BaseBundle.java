/* artzig shim: android.os.BaseBundle — map-backed base of Bundle/PersistableBundle
 * (matches the SDK hierarchy: app dex resolves getInt/getString/... against BaseBundle). */
package android.os;
import java.util.HashMap;
import java.util.Set;
public class BaseBundle {
    /** Lazily decoded wire data (AOSP BaseBundle.mParcelledData): contents stay
     * as bytes until first access, so a process that only forwards the Bundle
     * never needs the classes inside it. */
    Parcel mParcelledData;
    ClassLoader mClassLoader;
    public void setClassLoader(ClassLoader loader) { mClassLoader = loader; }
    public ClassLoader getClassLoader() { return mClassLoader; }
    /** Whether the contents are still undecoded wire data. */
    public boolean isParcelled() { return mParcelledData != null; }
    final synchronized void unparcel$artzig() {
        Parcel parcelled = mParcelledData;
        if (parcelled == null) return;
        mParcelledData = null;
        parcelled.setDataPosition(0);
        parcelled.readBundleContentsInto$artzig(this, mClassLoader);
        parcelled.recycle();
    }
    final HashMap<String, Object> map() {
        unparcel$artzig();
        return mMap;
    }
    protected final HashMap<String, Object> mMap;
    BaseBundle() { mMap = new HashMap<>(); }
    BaseBundle(int capacity) { mMap = new HashMap<>(capacity); }
    BaseBundle(BaseBundle b) {
        // Copy without forcing a decode: a still-parcelled Bundle (e.g. a sticky
        // broadcast retained by `new Intent(intent)` in the system process) is
        // duplicated as raw wire data, so a process that only forwards it never
        // needs the app classes inside it. AOSP BaseBundle copy ctor.
        if (b.mParcelledData != null) {
            mMap = new HashMap<>();
            Parcel copy = Parcel.obtain();
            copy.appendFrom(b.mParcelledData, 0, b.mParcelledData.dataSize());
            copy.setDataPosition(0);
            mParcelledData = copy;
            mClassLoader = b.mClassLoader;
        } else {
            mMap = new HashMap<>(b.mMap);
            mClassLoader = b.mClassLoader;
        }
    }
    public int size() { return map().size(); }
    public boolean isEmpty() { return map().isEmpty(); }
    public void clear() { map().clear(); }
    public boolean containsKey(String key) { return map().containsKey(key); }
    public Object get(String key) { return map().get(key); }
    public void remove(String key) { map().remove(key); }
    public Set<String> keySet() { return map().keySet(); }
    public void putAll(PersistableBundle bundle) { map().putAll(bundle.map()); }
    public void putBoolean(String k, boolean v) { map().put(k, v); }
    public void putByte(String k, byte v) { map().put(k, v); }
    public void putChar(String k, char v) { map().put(k, v); }
    public void putShort(String k, short v) { map().put(k, v); }
    public void putInt(String k, int v) { map().put(k, v); }
    public void putLong(String k, long v) { map().put(k, v); }
    public void putDouble(String k, double v) { map().put(k, v); }
    /** AOSP hidden helper (BaseBundle.putObject): type-dispatched put. */
    public void putObject(String key, Object value) {
        if (value == null) {
            putString(key, null);
        } else if (value instanceof Boolean) {
            putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            putLong(key, (Long) value);
        } else if (value instanceof Double) {
            putDouble(key, (Double) value);
        } else if (value instanceof String) {
            putString(key, (String) value);
        } else if (value instanceof boolean[]) {
            putBooleanArray(key, (boolean[]) value);
        } else if (value instanceof int[]) {
            putIntArray(key, (int[]) value);
        } else if (value instanceof long[]) {
            putLongArray(key, (long[]) value);
        } else if (value instanceof double[]) {
            putDoubleArray(key, (double[]) value);
        } else if (value instanceof String[]) {
            putStringArray(key, (String[]) value);
        } else {
            throw new IllegalArgumentException("Unsupported type " + value.getClass());
        }
    }

    public void putString(String k, String v) { map().put(k, v); }
    public void putBooleanArray(String k, boolean[] v) { map().put(k, v); }
    public void putShortArray(String k, short[] v) { map().put(k, v); }
    public void putCharArray(String k, char[] v) { map().put(k, v); }
    public void putIntArray(String k, int[] v) { map().put(k, v); }
    public void putLongArray(String k, long[] v) { map().put(k, v); }
    public void putFloatArray(String k, float[] v) { map().put(k, v); }
    public void putDoubleArray(String k, double[] v) { map().put(k, v); }
    public void putStringArray(String k, String[] v) { map().put(k, v); }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public boolean getBoolean(String k, boolean d) { Object v = map().get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public byte getByte(String k) { return getByte(k, (byte) 0); }
    public byte getByte(String k, byte d) { Object v = map().get(k); return v instanceof Byte ? (Byte) v : d; }
    public char getChar(String k) { return getChar(k, (char) 0); }
    public char getChar(String k, char d) { Object v = map().get(k); return v instanceof Character ? (Character) v : d; }
    public short getShort(String k) { return getShort(k, (short) 0); }
    public short getShort(String k, short d) { Object v = map().get(k); return v instanceof Short ? (Short) v : d; }
    public int getInt(String k) { return getInt(k, 0); }
    public int getInt(String k, int d) { Object v = map().get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLong(String k) { return getLong(k, 0); }
    public long getLong(String k, long d) { Object v = map().get(k); return v instanceof Long ? (Long) v : d; }
    public double getDouble(String k) { return getDouble(k, 0d); }
    public double getDouble(String k, double d) { Object v = map().get(k); return v instanceof Double ? (Double) v : d; }
    public String getString(String k) { Object v = map().get(k); return v instanceof String ? (String) v : null; }
    public String getString(String k, String d) { String v = getString(k); return v != null ? v : d; }
    public boolean[] getBooleanArray(String k) { Object v = map().get(k); return v instanceof boolean[] ? (boolean[]) v : null; }
    public short[] getShortArray(String k) { Object v = map().get(k); return v instanceof short[] ? (short[]) v : null; }
    public char[] getCharArray(String k) { Object v = map().get(k); return v instanceof char[] ? (char[]) v : null; }
    public int[] getIntArray(String k) { Object v = map().get(k); return v instanceof int[] ? (int[]) v : null; }
    public long[] getLongArray(String k) { Object v = map().get(k); return v instanceof long[] ? (long[]) v : null; }
    public float[] getFloatArray(String k) { Object v = map().get(k); return v instanceof float[] ? (float[]) v : null; }
    public double[] getDoubleArray(String k) { Object v = map().get(k); return v instanceof double[] ? (double[]) v : null; }
    public String[] getStringArray(String k) { Object v = map().get(k); return v instanceof String[] ? (String[]) v : null; }
}
