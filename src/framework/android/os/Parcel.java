/* artzig shim: android.os.Parcel — pure-Java, in-process only. */
package android.os;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public final class Parcel {
    private ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    private int size = 0;
    private Parcel() {}
    public static Parcel obtain() { return new Parcel(); }
    public void recycle() { size = 0; buf.position(0); }
    private void ensure(int n) {
        if (buf.position() + n > buf.capacity()) {
            ByteBuffer nb = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + n))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int pos = buf.position();
            buf.position(0); buf.limit(size); nb.put(buf);
            nb.position(pos); buf = nb;
        }
    }
    private void grew() { if (buf.position() > size) size = buf.position(); }
    public int dataSize() { return size; }
    public int dataPosition() { return buf.position(); }
    public int dataAvail() { return size - buf.position(); }
    public void setDataPosition(int pos) { buf.position(pos); }
    public void setDataSize(int s) { ensure(s - buf.position()); size = s; }
    public void setDataCapacity(int c) { ensure(c - buf.position()); }
    public void writeInt(int v) { ensure(4); buf.putInt(v); grew(); }
    public void writeLong(long v) { ensure(8); buf.putLong(v); grew(); }
    public void writeFloat(float v) { ensure(4); buf.putFloat(v); grew(); }
    public void writeDouble(double v) { ensure(8); buf.putDouble(v); grew(); }
    public void writeBoolean(boolean v) { writeInt(v ? 1 : 0); }
    public void writeByte(byte v) { writeInt(v); }
    public void writeString(String v) {
        if (v == null) { writeInt(-1); return; }
        writeInt(v.length());
        for (int i = 0; i < v.length(); i++) { ensure(2); buf.putChar(v.charAt(i)); }
        ensure(2); buf.putChar('\0'); grew();
        while ((buf.position() & 3) != 0) { ensure(1); buf.put((byte) 0); }
        grew();
    }
    public void writeString8(String v) { writeString(v); }
    public void writeString16(String v) { writeString(v); }
    public void writeCharSequence(CharSequence v) { writeString(v == null ? null : v.toString()); }
    public void writeByteArray(byte[] b) {
        if (b == null) { writeInt(-1); return; }
        writeInt(b.length); ensure(b.length); buf.put(b); grew();
        while ((buf.position() & 3) != 0) { ensure(1); buf.put((byte) 0); }
        grew();
    }
    public void writeBlob(byte[] b) { writeByteArray(b); }
    public void writeBlob(byte[] b, int offset, int len) {
        if (b == null) { writeInt(-1); return; }
        byte[] sub = new byte[len];
        System.arraycopy(b, offset, sub, 0, len);
        writeByteArray(sub);
    }
    public void writeByteArray(byte[] b, int offset, int len) { writeBlob(b, offset, len); }
    public void writeIntArray(int[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (int v : a) writeInt(v);
    }
    public void writeLongArray(long[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (long v : a) writeLong(v);
    }
    public void writeFloatArray(float[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (float v : a) writeFloat(v);
    }
    public void writeBooleanArray(boolean[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (boolean v : a) writeBoolean(v);
    }
    public void writeStringArray(String[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (String v : a) writeString(v);
    }
    public void writeStrongBinder(IBinder b) { writeInt(b == null ? 0 : System.identityHashCode(b)); }
    public void writeStrongInterface(IInterface i) { writeStrongBinder(i == null ? null : i.asBinder()); }
    public void writeFileDescriptor(FileDescriptor fd) { writeInt(0); }
    public void writeParcelable(Parcelable p, int flags) {
        if (p == null) { writeString(null); return; }
        writeString(p.getClass().getName());
        p.writeToParcel(this, flags);
    }
    public <T> void writeTypedObject(T p, int flags) {
        if (p == null) { writeInt(0); return; }
        writeInt(1); ((Parcelable) p).writeToParcel(this, flags);
    }
    public <T> void writeTypedList(List<T> l) { writeTypedList(l, 0); }
    public <T> void writeTypedList(List<T> l, int flags) {
        if (l == null) { writeInt(-1); return; }
        writeInt(l.size());
        for (T t : l) writeTypedObject(t, flags);
    }
    public <T extends Parcelable> void writeTypedArray(T[] a, int flags) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length);
        for (T t : a) writeTypedObject(t, flags);
    }
    public void writeStringList(List<String> l) {
        if (l == null) { writeInt(-1); return; }
        writeInt(l.size()); for (String s : l) writeString(s);
    }
    public void writeValue(Object v) { throw new UnsupportedOperationException("Parcel.writeValue"); }
    public void writeSerializable(java.io.Serializable s) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            new java.io.ObjectOutputStream(bos).writeObject(s);
            writeByteArray(bos.toByteArray());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
    public java.io.Serializable readSerializable() { return readSerializable(null, java.io.Serializable.class); }
    public <T> T readSerializable(ClassLoader loader, Class<T> clazz) {
        byte[] data = readByteArray();
        if (data == null) return null;
        try {
            return (T) new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data)).readObject();
        } catch (Exception e) {
            throw new BadParcelableException(e.toString());
        }
    }
    public void writeMap(Map m) {
        if (m == null) { writeInt(-1); return; }
        writeInt(m.size());
        for (Object e : m.entrySet()) {
            Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
            writeString(String.valueOf(en.getKey()));
            writeString(String.valueOf(en.getValue()));
        }
    }
    public void readMap(Map m, ClassLoader loader) {
        int n = readInt();
        for (int i = 0; i < n; i++) m.put(readString(), readString());
    }
    public void writeBundle(Bundle b) { writeInt(b == null ? -1 : 0); }
    public void writeNoException() { writeInt(0); }
    public void writeException(Exception e) { throw new RuntimeException(e); }
    public int readInt() { return buf.getInt(); }
    public long readLong() { return buf.getLong(); }
    public float readFloat() { return buf.getFloat(); }
    public double readDouble() { return buf.getDouble(); }
    public boolean readBoolean() { return readInt() != 0; }
    public byte readByte() { return (byte) readInt(); }
    public String readString() {
        int n = readInt();
        if (n < 0) return null;
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(buf.getChar());
        buf.getChar(); // NUL
        while ((buf.position() & 3) != 0) buf.get();
        return sb.toString();
    }
    public String readString8() { return readString(); }
    public String readString16() { return readString(); }
    public CharSequence readCharSequence() { return readString(); }
    public void writeCharSequenceArray(CharSequence[] val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (CharSequence c : val) writeCharSequence(c);
    }
    public CharSequence[] readCharSequenceArray() {
        int n = readInt();
        if (n < 0) return null;
        CharSequence[] out = new CharSequence[n];
        for (int i = 0; i < n; i++) out[i] = readCharSequence();
        return out;
    }
    public byte[] readByteArray() {
        int n = readInt();
        if (n < 0) return null;
        byte[] b = new byte[n]; buf.get(b);
        while ((buf.position() & 3) != 0) buf.get();
        return b;
    }
    public byte[] readBlob() { return readByteArray(); }
    public byte[] createByteArray() { return readByteArray(); }
    public int[] createIntArray() {
        int n = readInt(); if (n < 0) return null;
        int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = readInt(); return a;
    }
    public long[] createLongArray() {
        int n = readInt(); if (n < 0) return null;
        long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = readLong(); return a;
    }
    public void readFloatArray(float[] out) {
        int n = readInt();
        for (int i = 0; i < n && i < out.length; i++) out[i] = readFloat();
    }
    public void readIntArray(int[] out) {
        int n = readInt();
        for (int i = 0; i < n && i < out.length; i++) out[i] = readInt();
    }
    public float[] createFloatArray() {
        int n = readInt(); if (n < 0) return null;
        float[] a = new float[n]; for (int i = 0; i < n; i++) a[i] = readFloat(); return a;
    }
    public boolean[] createBooleanArray() {
        int n = readInt(); if (n < 0) return null;
        boolean[] a = new boolean[n]; for (int i = 0; i < n; i++) a[i] = readBoolean(); return a;
    }
    public String[] createStringArray() {
        int n = readInt(); if (n < 0) return null;
        String[] a = new String[n]; for (int i = 0; i < n; i++) a[i] = readString(); return a;
    }
    public String[] readStringArray() { return createStringArray(); }
    public void writeSparseIntArray(android.util.SparseIntArray val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeInt(val.valueAt(i)); }
    }
    public void writeSparseBooleanArray(android.util.SparseBooleanArray val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeBoolean(val.valueAt(i)); }
    }
    public android.util.SparseBooleanArray readSparseBooleanArray() {
        int n = readInt();
        if (n < 0) return null;
        android.util.SparseBooleanArray out = new android.util.SparseBooleanArray(n);
        for (int i = 0; i < n; i++) out.put(readInt(), readBoolean());
        return out;
    }
    public <T extends Parcelable> void writeParcelableArray(T[] val, int flags) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (T t : val) writeParcelable(t, flags);
    }
    public <T> T[] readParcelableArray(ClassLoader loader, Class<T> clazz) {
        int n = readInt();
        if (n < 0) return null;
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, n);
        for (int i = 0; i < n; i++) out[i] = (T) readParcelable(loader);
        return out;
    }
    public <T extends Parcelable> T[] readParcelableArray(ClassLoader loader) {
        int n = readInt();
        if (n < 0) return null;
        Parcelable[] out = new Parcelable[n];
        for (int i = 0; i < n; i++) out[i] = readParcelable(loader);
        return (T[]) out;
    }
    public void writeParcelableCreator(Parcelable p) { writeString(p.getClass().getName()); }
    public Parcelable.Creator<?> readParcelableCreator(ClassLoader loader) {
        String name = readString();
        if (name == null) return null;
        try {
            Class<?> cls = Class.forName(name, true, loader != null ? loader : getClass().getClassLoader());
            return (Parcelable.Creator<?>) cls.getField("CREATOR").get(null);
        } catch (ReflectiveOperationException e) {
            throw new BadParcelableException(e.toString());
        }
    }
    public void readException(int code, String msg) {}
    public void writeArrayMap(android.util.ArrayMap<String, Object> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (int i = 0; i < val.size(); i++) { writeString(val.keyAt(i)); writeString(String.valueOf(val.valueAt(i))); }
    }
    public void readArrayMap(android.util.ArrayMap outVal, ClassLoader loader) {
        int n = readInt();
        for (int i = 0; i < n; i++) outVal.put(readString(), readString());
    }
    public <T extends Parcelable> void writeParcelableList(List<T> val, int flags) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (T t : val) writeParcelable(t, flags);
    }
    public <T extends Parcelable> List<T> readParcelableList(List<T> list, ClassLoader cl) {
        int n = readInt();
        list.clear();
        for (int i = 0; i < n; i++) list.add(this.<T>readParcelable(cl));
        return list;
    }
    public <T> void writeSparseArray(android.util.SparseArray<T> val) {
        if (val == null) { writeInt(-1); return; }
        int n = val.size();
        writeInt(n);
        for (int i = 0; i < n; i++) { writeInt(val.keyAt(i)); writeValue(val.valueAt(i)); }
    }
    public <T> android.util.SparseArray<T> readSparseArray(ClassLoader loader) {
        int n = readInt();
        if (n < 0) return null;
        android.util.SparseArray<T> out = new android.util.SparseArray<>(n);
        for (int i = 0; i < n; i++) out.put(readInt(), (T) readValue(loader));
        return out;
    }
    public <T> android.util.SparseArray<T> readSparseArray(ClassLoader loader, Class<? extends T> clazz) {
        return readSparseArray(loader);
    }
    public <T> void readTypedArray(T[] dest, Parcelable.Creator<T> c) {
        int n = readInt();
        for (int i = 0; i < n && i < dest.length; i++) dest[i] = readTypedObject(c);
    }
    public void writeArraySet(android.util.ArraySet<? extends Object> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (int i = 0; i < val.size(); i++) writeString(String.valueOf(val.valueAt(i)));
    }
    public android.util.ArraySet readArraySet(ClassLoader loader) {
        int n = readInt();
        if (n < 0) return null;
        android.util.ArraySet out = new android.util.ArraySet(n);
        for (int i = 0; i < n; i++) out.add(readString());
        return out;
    }
    public void writeList(java.util.List val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (Object o : val) writeValue(o);
    }
    public <T> void readList(java.util.List<? super T> outVal, ClassLoader loader, Class<T> clazz) {
        readList(outVal, loader);
    }
    public <T> java.util.List<T> readParcelableList(java.util.List<T> list, ClassLoader cl, Class<? extends T> clazz) {
        return (java.util.List<T>) readParcelableList((java.util.List) list, cl);
    }
    public void readList(java.util.List outVal, ClassLoader loader) {
        int n = readInt();
        for (int i = 0; i < n; i++) outVal.add(readValue(loader));
    }
    public void writeBinderList(java.util.List<IBinder> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (IBinder b : val) writeStrongBinder(b);
    }
    public void readBinderList(java.util.List<IBinder> list) {
        int n = readInt();
        list.clear();
        for (int i = 0; i < n; i++) list.add(readStrongBinder());
    }
    public android.util.SparseIntArray readSparseIntArray() {
        int n = readInt();
        if (n < 0) return null;
        android.util.SparseIntArray out = new android.util.SparseIntArray(n);
        for (int i = 0; i < n; i++) out.put(readInt(), readInt());
        return out;
    }
    public IBinder readStrongBinder() { readInt(); return null; }
    public Object readValue(ClassLoader loader) { throw new UnsupportedOperationException("Parcel.readValue"); }
    public void readBooleanArray(boolean[] out) {
        int n = readInt();
        for (int i = 0; i < n && i < out.length; i++) out[i] = readBoolean();
    }
    public java.util.HashMap readHashMap(ClassLoader loader) {
        int n = readInt();
        java.util.HashMap m = new java.util.HashMap();
        for (int i = 0; i < n; i++) m.put(readString(), readString());
        return m;
    }
    public <K, V> void readMap(java.util.Map<? super K, ? super V> map, ClassLoader loader,
            Class<K> clazzKey, Class<V> clazzValue) {
        readMap(map, loader);
    }
    public void readStringList(java.util.List<String> list) {
        int n = readInt();
        list.clear();
        for (int i = 0; i < n; i++) list.add(readString());
    }
    public <T> T readTypedObject(Parcelable.Creator<T> c) { return readInt() != 0 ? c.createFromParcel(this) : null; }
    public <T> void readTypedList(List<T> list, Parcelable.Creator<T> c) {
        int n = readInt();
        list.clear();
        for (int i = 0; i < n; i++) list.add(readTypedObject(c));
    }
    public <T> ArrayList<T> createTypedArrayList(Parcelable.Creator<T> c) {
        int n = readInt(); if (n < 0) return null;
        ArrayList<T> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(readTypedObject(c));
        return l;
    }
    public <T> T[] createTypedArray(Parcelable.Creator<T> c) {
        int n = readInt(); if (n < 0) return null;
        T[] a = c.newArray(n);
        for (int i = 0; i < n; i++) a[i] = readTypedObject(c);
        return a;
    }
    public ArrayList<String> createStringArrayList() {
        int n = readInt(); if (n < 0) return null;
        ArrayList<String> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(readString());
        return l;
    }
    public <T extends Parcelable> T readParcelable(ClassLoader loader) {
        String name = readString();
        if (name == null) return null;
        try {
            Class<?> cls = Class.forName(name, true, loader != null ? loader : getClass().getClassLoader());
            @SuppressWarnings("unchecked")
            Parcelable.Creator<T> c = (Parcelable.Creator<T>) cls.getField("CREATOR").get(null);
            return c.createFromParcel(this);
        } catch (ReflectiveOperationException e) {
            throw new BadParcelableException(e.toString());
        }
    }
    public PersistableBundle readPersistableBundle() { return readPersistableBundle(null); }
    public PersistableBundle readPersistableBundle(ClassLoader loader) {
        int n = readInt();
        return n < 0 ? null : new PersistableBundle();
    }
    public void writePersistableBundle(PersistableBundle b) { writeInt(b == null ? -1 : 0); }
    public Bundle readBundle() { return readBundle(null); }
    public Bundle readBundle(ClassLoader loader) {
        int n = readInt();
        return n < 0 ? null : new Bundle();
    }
    public <T> T readParcelable(ClassLoader loader, Class<T> clazz) { return (T) readParcelable(loader); }
    public void readException() { readInt(); }
    public int readExceptionCode() { return 0; }
    public void enforceInterface(String descriptor) {}
    public void writeInterfaceToken(String descriptor) {}
    public boolean hasFileDescriptors() { return false; }
    public void appendFrom(Parcel parcel, int offset, int length) {
        byte[] all = parcel.marshall();
        int len = Math.min(length, all.length - offset);
        if (len > 0) {
            byte[] sub = new byte[len];
            System.arraycopy(all, offset, sub, 0, len);
            ensure(len);
            buf.put(sub);
            grew();
        }
    }
    public byte[] marshall() {
        byte[] out = new byte[size];
        int pos = buf.position();
        buf.position(0); buf.get(out, 0, size); buf.position(pos);
        return out;
    }
    public void unmarshall(byte[] data, int offset, int length) {
        buf.position(0); ensure(length);
        buf.put(data, offset, length);
        size = length; buf.position(0);
    }
}
