/* artzig shim: android.os.Bundle — HashMap-backed. */
package android.os;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
public class Bundle extends BaseBundle implements Parcelable, Cloneable {
    public static final Bundle EMPTY = new Bundle();
    public Bundle() { super(); }
    public Bundle(int capacity) { super(capacity); }
    public Bundle(Bundle b) { super(b); }
    public Bundle(ClassLoader loader) { super(); }
    public void putAll(Bundle b) { mMap.putAll(b.mMap); }

    public void putFloat(String k, float v) { mMap.put(k, v); }
    public void putCharSequence(String k, CharSequence v) { mMap.put(k, v); }
    public void putParcelable(String k, Parcelable v) { mMap.put(k, v); }
    public void putSerializable(String k, Serializable v) { mMap.put(k, v); }
    public void putBundle(String k, Bundle v) { mMap.put(k, v); }
    public void putByteArray(String k, byte[] v) { mMap.put(k, v); }
    public void putIntegerArrayList(String k, ArrayList<Integer> v) { mMap.put(k, v); }
    public void putStringArrayList(String k, ArrayList<String> v) { mMap.put(k, v); }
    public void putCharSequenceArrayList(String k, ArrayList<CharSequence> v) { mMap.put(k, v); }
    public void putParcelableArrayList(String k, ArrayList<? extends Parcelable> v) { mMap.put(k, v); }
    public float getFloat(String k) { return getFloat(k, 0f); }
    public float getFloat(String k, float d) { Object v = mMap.get(k); return v instanceof Float ? (Float) v : d; }
    public CharSequence getCharSequence(String k) { Object v = mMap.get(k); return v instanceof CharSequence ? (CharSequence) v : null; }
    public CharSequence getCharSequence(String k, CharSequence defaultValue) {
        CharSequence value = getCharSequence(k);
        return value != null ? value : defaultValue;
    }
    public <T extends Parcelable> T getParcelable(String k) { Object v = mMap.get(k); return (T) v; }
    public <T> T getParcelable(String k, Class<T> clazz) { Object v = mMap.get(k); return clazz.isInstance(v) ? clazz.cast(v) : null; }
    public Serializable getSerializable(String k) { Object v = mMap.get(k); return v instanceof Serializable ? (Serializable) v : null; }
    public Bundle getBundle(String k) { Object v = mMap.get(k); return v instanceof Bundle ? (Bundle) v : null; }
    public byte[] getByteArray(String k) { Object v = mMap.get(k); return v instanceof byte[] ? (byte[]) v : null; }
    @SuppressWarnings("unchecked")
    public ArrayList<Integer> getIntegerArrayList(String k) { return (ArrayList<Integer>) mMap.get(k); }
    public ArrayList<String> getStringArrayList(String k) { Object v = mMap.get(k); return (ArrayList<String>) v; }
    @SuppressWarnings("unchecked")
    public ArrayList<CharSequence> getCharSequenceArrayList(String k) { return (ArrayList<CharSequence>) mMap.get(k); }
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String k) { return (ArrayList<T>) mMap.get(k); }
    public <T> ArrayList<T> getParcelableArrayList(String k, Class<? extends T> clazz) { return (ArrayList<T>) mMap.get(k); }
    public <T extends java.io.Serializable> T getSerializable(String k, Class<T> clazz) {
        Object v = mMap.get(k);
        return clazz.isInstance(v) ? clazz.cast(v) : null;
    }
    public void putSparseParcelableArray(String k, android.util.SparseArray<? extends Parcelable> v) { mMap.put(k, v); }
    public <T> android.util.SparseArray<T> getSparseParcelableArray(String k, Class<? extends T> clazz) {
        return (android.util.SparseArray<T>) mMap.get(k);
    }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> android.util.SparseArray<T> getSparseParcelableArray(String k) {
        return (android.util.SparseArray<T>) mMap.get(k);
    }
    public void putBinder(String k, IBinder v) { mMap.put(k, v); }
    public IBinder getBinder(String k) { Object v = mMap.get(k); return v instanceof IBinder ? (IBinder) v : null; }
    public void putCharSequenceArray(String k, CharSequence[] v) { mMap.put(k, v); }
    public void putParcelableArray(String k, Parcelable[] v) { mMap.put(k, v); }
    @SuppressWarnings("unchecked")
    public <T> T[] getParcelableArray(String k, Class<T> clazz) {
        Object v = mMap.get(k);
        if (!(v instanceof Parcelable[])) return null;
        Parcelable[] src = (Parcelable[]) v;
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, src.length);
        for (int i = 0; i < src.length; i++) out[i] = clazz.cast(src[i]);
        return out;
    }
    public Parcelable[] getParcelableArray(String k) { Object v = mMap.get(k); return v instanceof Parcelable[] ? (Parcelable[]) v : null; }
    public CharSequence[] getCharSequenceArray(String k) { Object v = mMap.get(k); return v instanceof CharSequence[] ? (CharSequence[]) v : null; }
    public void setClassLoader(ClassLoader loader) {}
    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    @Override public Object clone() { return new Bundle(this); }
    public Bundle deepCopy() { return new Bundle(this); }
    public boolean kindofEquals(Bundle other) { return other != null && size() == other.size(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeBundle(this); }
    public void readFromParcel(Parcel in) { Bundle b = in.readBundle(); mMap.clear(); if (b != null) mMap.putAll(b.mMap); }
    public static final Creator<Bundle> CREATOR = new Creator<Bundle>() {
        public Bundle createFromParcel(Parcel in) { return in.readBundle(); }
        public Bundle[] newArray(int size) { return new Bundle[size]; }
    };
    @Override public String toString() { return "Bundle" + mMap; }
}
