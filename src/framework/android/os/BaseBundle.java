/* artzig shim: android.os.BaseBundle — map-backed base of Bundle/PersistableBundle
 * (matches the SDK hierarchy: app dex resolves getInt/getString/... against BaseBundle). */
package android.os;
import java.util.HashMap;
import java.util.Set;
public class BaseBundle {
    protected final HashMap<String, Object> mMap;
    BaseBundle() { mMap = new HashMap<>(); }
    BaseBundle(int capacity) { mMap = new HashMap<>(capacity); }
    BaseBundle(BaseBundle b) { mMap = new HashMap<>(b.mMap); }
    public int size() { return mMap.size(); }
    public boolean isEmpty() { return mMap.isEmpty(); }
    public void clear() { mMap.clear(); }
    public boolean containsKey(String key) { return mMap.containsKey(key); }
    public Object get(String key) { return mMap.get(key); }
    public void remove(String key) { mMap.remove(key); }
    public Set<String> keySet() { return mMap.keySet(); }
    public void putAll(PersistableBundle bundle) { mMap.putAll(bundle.mMap); }
    public void putBoolean(String k, boolean v) { mMap.put(k, v); }
    public void putByte(String k, byte v) { mMap.put(k, v); }
    public void putChar(String k, char v) { mMap.put(k, v); }
    public void putShort(String k, short v) { mMap.put(k, v); }
    public void putInt(String k, int v) { mMap.put(k, v); }
    public void putLong(String k, long v) { mMap.put(k, v); }
    public void putDouble(String k, double v) { mMap.put(k, v); }
    public void putString(String k, String v) { mMap.put(k, v); }
    public void putBooleanArray(String k, boolean[] v) { mMap.put(k, v); }
    public void putShortArray(String k, short[] v) { mMap.put(k, v); }
    public void putCharArray(String k, char[] v) { mMap.put(k, v); }
    public void putIntArray(String k, int[] v) { mMap.put(k, v); }
    public void putLongArray(String k, long[] v) { mMap.put(k, v); }
    public void putFloatArray(String k, float[] v) { mMap.put(k, v); }
    public void putDoubleArray(String k, double[] v) { mMap.put(k, v); }
    public void putStringArray(String k, String[] v) { mMap.put(k, v); }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public boolean getBoolean(String k, boolean d) { Object v = mMap.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public byte getByte(String k) { return getByte(k, (byte) 0); }
    public byte getByte(String k, byte d) { Object v = mMap.get(k); return v instanceof Byte ? (Byte) v : d; }
    public char getChar(String k) { return getChar(k, (char) 0); }
    public char getChar(String k, char d) { Object v = mMap.get(k); return v instanceof Character ? (Character) v : d; }
    public short getShort(String k) { return getShort(k, (short) 0); }
    public short getShort(String k, short d) { Object v = mMap.get(k); return v instanceof Short ? (Short) v : d; }
    public int getInt(String k) { return getInt(k, 0); }
    public int getInt(String k, int d) { Object v = mMap.get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLong(String k) { return getLong(k, 0); }
    public long getLong(String k, long d) { Object v = mMap.get(k); return v instanceof Long ? (Long) v : d; }
    public double getDouble(String k) { return getDouble(k, 0d); }
    public double getDouble(String k, double d) { Object v = mMap.get(k); return v instanceof Double ? (Double) v : d; }
    public String getString(String k) { Object v = mMap.get(k); return v instanceof String ? (String) v : null; }
    public String getString(String k, String d) { String v = getString(k); return v != null ? v : d; }
    public boolean[] getBooleanArray(String k) { Object v = mMap.get(k); return v instanceof boolean[] ? (boolean[]) v : null; }
    public short[] getShortArray(String k) { Object v = mMap.get(k); return v instanceof short[] ? (short[]) v : null; }
    public char[] getCharArray(String k) { Object v = mMap.get(k); return v instanceof char[] ? (char[]) v : null; }
    public int[] getIntArray(String k) { Object v = mMap.get(k); return v instanceof int[] ? (int[]) v : null; }
    public long[] getLongArray(String k) { Object v = mMap.get(k); return v instanceof long[] ? (long[]) v : null; }
    public float[] getFloatArray(String k) { Object v = mMap.get(k); return v instanceof float[] ? (float[]) v : null; }
    public double[] getDoubleArray(String k) { Object v = mMap.get(k); return v instanceof double[] ? (double[]) v : null; }
    public String[] getStringArray(String k) { Object v = mMap.get(k); return v instanceof String[] ? (String[]) v : null; }
}
