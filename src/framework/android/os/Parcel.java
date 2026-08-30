/* Hosted adaptation of AOSP android.os.Parcel. The platform implementation cannot be reused
 * directly because it owns data through libbinder's native Parcel; this version keeps the same
 * cursor, growth, and Parcelable contracts over a Java ByteBuffer. Binder objects and file
 * descriptors live in a side table keyed by data offset (the analogue of libbinder's object
 * array), which the hosted Binder runtime translates when a Parcel crosses processes. */
package android.os;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public final class Parcel {
    /** One side-table entry: a Binder or a descriptor at a data offset. */
    static final class ObjectEntry {
        static final int KIND_BINDER = 1;
        static final int KIND_FILE_DESCRIPTOR = 2;
        final int offset;
        final int kind;
        final IBinder binder;
        final FileDescriptor fd;
        /** The Parcel closes this descriptor on recycle. */
        final boolean ownsFd;
        ObjectEntry(int offset, int kind, IBinder binder, FileDescriptor fd, boolean ownsFd) {
            this.offset = offset; this.kind = kind; this.binder = binder; this.fd = fd; this.ownsFd = ownsFd;
        }
    }

    private static final int EX_SECURITY = -1;
    private static final int EX_BAD_PARCELABLE = -2;
    private static final int EX_ILLEGAL_ARGUMENT = -3;
    private static final int EX_NULL_POINTER = -4;
    private static final int EX_ILLEGAL_STATE = -5;
    private static final int EX_NETWORK_MAIN_THREAD = -6;
    private static final int EX_UNSUPPORTED_OPERATION = -7;
    private static final int EX_SERVICE_SPECIFIC = -8;
    private static final int EX_PARCELABLE = -9;
    private static final int EX_TRANSACTION_FAILED = -129;

    private static final int VAL_NULL = -1, VAL_STRING = 0, VAL_INTEGER = 1, VAL_MAP = 2, VAL_BUNDLE = 3,
            VAL_PARCELABLE = 4, VAL_SHORT = 5, VAL_LONG = 6, VAL_FLOAT = 7, VAL_DOUBLE = 8, VAL_BOOLEAN = 9,
            VAL_CHARSEQUENCE = 10, VAL_LIST = 11, VAL_SPARSEARRAY = 12, VAL_BYTEARRAY = 13, VAL_STRINGARRAY = 14,
            VAL_IBINDER = 15, VAL_PARCELABLEARRAY = 16, VAL_OBJECTARRAY = 17, VAL_INTARRAY = 18, VAL_LONGARRAY = 19,
            VAL_BYTE = 20, VAL_SERIALIZABLE = 21, VAL_SPARSEBOOLEANARRAY = 22, VAL_BOOLEANARRAY = 23,
            VAL_CHARSEQUENCEARRAY = 24, VAL_PERSISTABLEBUNDLE = 25, VAL_SIZE = 26, VAL_SIZEF = 27,
            VAL_DOUBLEARRAY = 28, VAL_CHAR = 29, VAL_SHORTARRAY = 30, VAL_CHARARRAY = 31, VAL_FLOATARRAY = 32;

    /** Fallback loader for readParcelable when no explicit loader is given.
     * ApkHost sets the APK class loader so app-defined Parcelables decode. */
    private static volatile ClassLoader sDefaultClassLoader;
    public static void setDefaultClassLoader(ClassLoader loader) { sDefaultClassLoader = loader; }
    static ClassLoader defaultClassLoader$artzig(Class<?> caller) {
        ClassLoader loader = sDefaultClassLoader;
        return loader != null ? loader : caller.getClassLoader();
    }
    private ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    private int size = 0;
    /** Side table keyed by data offset; kept sorted so wire order is the iteration order. */
    private java.util.TreeMap<Integer, ObjectEntry> objects;
    private Parcel() {}
    public static Parcel obtain() { return new Parcel(); }
    public static Parcel obtain(IBinder binder) { return new Parcel(); }
    public void recycle() { clearObjects(); size = 0; buf.position(0); }

    // ---- side table (hosted Binder runtime interface) ----
    private static void closeEntry(ObjectEntry e) {
        if (e.ownsFd && e.fd != null) {
            try { android.system.Os.close(e.fd); } catch (Throwable ignored) {}
        }
    }
    private void clearObjects() {
        if (objects == null) return;
        for (ObjectEntry e : objects.values()) closeEntry(e);
        objects = null;
    }
    private void addObject(ObjectEntry entry) {
        if (objects == null) objects = new java.util.TreeMap<>();
        ObjectEntry old = objects.put(entry.offset, entry);
        if (old != null) closeEntry(old);
    }
    private ObjectEntry objectAt(int offset) {
        return objects == null ? null : objects.get(offset);
    }
    private void truncateObjects(int newSize) {
        if (objects == null) return;
        java.util.SortedMap<Integer, ObjectEntry> tail = objects.tailMap(newSize);
        for (ObjectEntry e : tail.values()) closeEntry(e);
        tail.clear();
        if (objects.isEmpty()) objects = null;
    }
    /** Side-table entries in data order; the collection is owned by the Parcel. */
    java.util.Collection<ObjectEntry> objectEntries() {
        return objects == null ? java.util.Collections.<ObjectEntry>emptyList() : objects.values();
    }
    /** Copy of the committed payload bytes regardless of side-table contents. */
    byte[] payloadBytes() {
        byte[] out = new byte[size];
        int pos = buf.position();
        buf.position(0); buf.get(out, 0, size); buf.position(pos);
        return out;
    }
    /** Replaces the contents with data received from another process; descriptors become Parcel-owned. */
    void adoptWire(byte[] data, List<ObjectEntry> entries) {
        unmarshall(data, 0, data.length);
        for (ObjectEntry e : entries) addObject(e);
        setDataPosition(0);
    }
    static Parcel fromWire(byte[] data, List<ObjectEntry> entries) {
        Parcel p = new Parcel();
        p.adoptWire(data, entries);
        return p;
    }
    private void ensure(int n) {
        if (buf.position() + n > buf.capacity()) {
            ByteBuffer nb = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + n))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int pos = buf.position();
            // A write may reserve and fill several bytes before grew() commits the new dataSize
            // (writeString() does this one UTF-16 code unit at a time). Preserve both committed
            // data and every byte already written up to the cursor when growth happens mid-write.
            int used = Math.max(size, pos);
            buf.position(0); buf.limit(used); nb.put(buf);
            nb.position(pos); buf = nb;
        }
    }
    private void grew() { if (buf.position() > size) size = buf.position(); }
    public int dataSize() { return size; }
    public int dataPosition() { return buf.position(); }
    public int dataAvail() { return size - buf.position(); }
    public void setDataPosition(int pos) { buf.position(pos); }
    public void setDataSize(int s) { ensure(s - buf.position()); if (s < size) truncateObjects(s); size = s; }
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
    public void writeStrongBinder(IBinder b) {
        int offset = dataPosition();
        writeInt(b == null ? 0 : 1);
        addObject(new ObjectEntry(offset, ObjectEntry.KIND_BINDER, b, null, false));
    }
    public void writeStrongInterface(IInterface i) { writeStrongBinder(i == null ? null : i.asBinder()); }
    /** Stores a duplicate that the Parcel owns, as libbinder does. */
    public void writeFileDescriptor(FileDescriptor fd) {
        if (fd == null) throw new NullPointerException("fd");
        FileDescriptor dup;
        try { dup = android.system.Os.dup(fd); }
        catch (android.system.ErrnoException e) { throw new RuntimeException("dup failed", e); }
        int offset = dataPosition();
        writeInt(1);
        addObject(new ObjectEntry(offset, ObjectEntry.KIND_FILE_DESCRIPTOR, null, dup, true));
    }
    public void writeRawFileDescriptor(int fd) {
        FileDescriptor f = new FileDescriptor();
        f.setInt$(fd);
        writeFileDescriptor(f);
    }
    public void writeRawFileDescriptorArray(int[] value) {
        if (value == null) { writeInt(-1); return; }
        writeInt(value.length);
        for (int fd : value) writeRawFileDescriptor(fd);
    }
    /** Caller-owned duplicate of the descriptor at the cursor. */
    public ParcelFileDescriptor readFileDescriptor() {
        FileDescriptor raw = readRawFileDescriptor();
        if (raw == null) return null;
        try { return new ParcelFileDescriptor(android.system.Os.dup(raw)); }
        catch (android.system.ErrnoException e) { throw new RuntimeException("dup failed", e); }
    }
    /** Parcel-owned descriptor at the cursor; valid until recycle. */
    public FileDescriptor readRawFileDescriptor() {
        int offset = dataPosition();
        int marker = readInt();
        ObjectEntry e = objectAt(offset);
        if (e == null || e.kind != ObjectEntry.KIND_FILE_DESCRIPTOR) {
            if (marker == 0) return null;
            throw new BadParcelableException("no file descriptor at offset " + offset);
        }
        return e.fd;
    }
    public FileDescriptor[] createRawFileDescriptorArray() {
        int n = readInt();
        if (n < 0) return null;
        FileDescriptor[] out = new FileDescriptor[n];
        for (int i = 0; i < n; i++) out[i] = readRawFileDescriptor();
        return out;
    }
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
    public void writeValue(Object v) {
        if (v == null) { writeInt(VAL_NULL); }
        else if (v instanceof String) { writeInt(VAL_STRING); writeString((String) v); }
        else if (v instanceof Integer) { writeInt(VAL_INTEGER); writeInt((Integer) v); }
        else if (v instanceof Map) { writeInt(VAL_MAP); writeMap((Map) v); }
        else if (v instanceof Bundle) { writeInt(VAL_BUNDLE); writeBundle((Bundle) v); }
        else if (v instanceof PersistableBundle) { writeInt(VAL_PERSISTABLEBUNDLE); writePersistableBundle((PersistableBundle) v); }
        else if (v instanceof Parcelable) { writeInt(VAL_PARCELABLE); writeParcelable((Parcelable) v, 0); }
        else if (v instanceof Short) { writeInt(VAL_SHORT); writeInt(((Short) v).intValue()); }
        else if (v instanceof Long) { writeInt(VAL_LONG); writeLong((Long) v); }
        else if (v instanceof Float) { writeInt(VAL_FLOAT); writeFloat((Float) v); }
        else if (v instanceof Double) { writeInt(VAL_DOUBLE); writeDouble((Double) v); }
        else if (v instanceof Boolean) { writeInt(VAL_BOOLEAN); writeInt(((Boolean) v) ? 1 : 0); }
        else if (v instanceof CharSequence) { writeInt(VAL_CHARSEQUENCE); writeCharSequence((CharSequence) v); }
        else if (v instanceof List) { writeInt(VAL_LIST); writeList((List) v); }
        else if (v instanceof android.util.SparseArray) { writeInt(VAL_SPARSEARRAY); writeSparseArray((android.util.SparseArray) v); }
        else if (v instanceof boolean[]) { writeInt(VAL_BOOLEANARRAY); writeBooleanArray((boolean[]) v); }
        else if (v instanceof byte[]) { writeInt(VAL_BYTEARRAY); writeByteArray((byte[]) v); }
        else if (v instanceof String[]) { writeInt(VAL_STRINGARRAY); writeStringArray((String[]) v); }
        else if (v instanceof CharSequence[]) { writeInt(VAL_CHARSEQUENCEARRAY); writeCharSequenceArray((CharSequence[]) v); }
        else if (v instanceof IBinder) { writeInt(VAL_IBINDER); writeStrongBinder((IBinder) v); }
        else if (v instanceof Parcelable[]) { writeInt(VAL_PARCELABLEARRAY); writeParcelableArray((Parcelable[]) v, 0); }
        else if (v instanceof int[]) { writeInt(VAL_INTARRAY); writeIntArray((int[]) v); }
        else if (v instanceof short[]) { writeInt(VAL_SHORTARRAY); writeShortArray((short[]) v); }
        else if (v instanceof char[]) { writeInt(VAL_CHARARRAY); writeCharArray((char[]) v); }
        else if (v instanceof long[]) { writeInt(VAL_LONGARRAY); writeLongArray((long[]) v); }
        else if (v instanceof double[]) { writeInt(VAL_DOUBLEARRAY); writeDoubleArray((double[]) v); }
        else if (v instanceof float[]) { writeInt(VAL_FLOATARRAY); writeFloatArray((float[]) v); }
        else if (v instanceof Byte) { writeInt(VAL_BYTE); writeInt((Byte) v); }
        else if (v instanceof Character) { writeInt(VAL_CHAR); writeInt((Character) v); }
        else if (v instanceof android.util.SparseBooleanArray) { writeInt(VAL_SPARSEBOOLEANARRAY); writeSparseBooleanArray((android.util.SparseBooleanArray) v); }
        else if (v instanceof android.util.Size) { writeInt(VAL_SIZE); writeInt(((android.util.Size) v).getWidth()); writeInt(((android.util.Size) v).getHeight()); }
        else if (v instanceof android.util.SizeF) { writeInt(VAL_SIZEF); writeFloat(((android.util.SizeF) v).getWidth()); writeFloat(((android.util.SizeF) v).getHeight()); }
        else if (v instanceof Object[]) { writeInt(VAL_OBJECTARRAY); writeArray((Object[]) v); }
        else if (v instanceof java.io.Serializable) { writeInt(VAL_SERIALIZABLE); writeSerializable((java.io.Serializable) v); }
        else throw new RuntimeException("Parcel: unable to marshal value " + v);
    }
    public void writeArray(Object[] val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.length);
        for (Object o : val) writeValue(o);
    }
    public Object[] readArray(ClassLoader loader) {
        int n = readInt();
        if (n < 0) return null;
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = readValue(loader);
        return out;
    }
    public <T> T[] readArray(ClassLoader loader, Class<T> clazz) {
        int n = readInt();
        if (n < 0) return null;
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, n);
        for (int i = 0; i < n; i++) out[i] = (T) readValue(loader);
        return out;
    }
    public void writeShortArray(short[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (short v : a) writeInt(v);
    }
    public short[] createShortArray() {
        int n = readInt(); if (n < 0) return null;
        short[] a = new short[n]; for (int i = 0; i < n; i++) a[i] = (short) readInt(); return a;
    }
    public void writeCharArray(char[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (char v : a) writeInt(v);
    }
    public char[] createCharArray() {
        int n = readInt(); if (n < 0) return null;
        char[] a = new char[n]; for (int i = 0; i < n; i++) a[i] = (char) readInt(); return a;
    }
    public void writeDoubleArray(double[] a) {
        if (a == null) { writeInt(-1); return; }
        writeInt(a.length); for (double v : a) writeDouble(v);
    }
    public double[] createDoubleArray() {
        int n = readInt(); if (n < 0) return null;
        double[] a = new double[n]; for (int i = 0; i < n; i++) a[i] = readDouble(); return a;
    }
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
    /** Resolves classes through the given loader (else the APK default), as AOSP does. */
    public <T> T readSerializable(ClassLoader loader, Class<T> clazz) {
        byte[] data = readByteArray();
        if (data == null) return null;
        final ClassLoader effective = loader != null ? loader : defaultClassLoader$artzig(getClass());
        try {
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data)) {
                @Override protected Class<?> resolveClass(java.io.ObjectStreamClass osClass)
                        throws java.io.IOException, ClassNotFoundException {
                    Class<?> c = Class.forName(osClass.getName(), false, effective);
                    if (c != null) return c;
                    return super.resolveClass(osClass);
                }
            };
            return (T) ois.readObject();
        } catch (Exception e) {
            throw new BadParcelableException(e.toString());
        }
    }
    /** AOSP writeMapInternal: size, then key/value pairs through writeValue. */
    public void writeMap(Map m) {
        if (m == null) { writeInt(-1); return; }
        writeInt(m.size());
        for (Object e : m.entrySet()) {
            Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
            writeValue(en.getKey());
            writeValue(en.getValue());
        }
    }
    public void readMap(Map m, ClassLoader loader) {
        int n = readInt();
        for (int i = 0; i < n; i++) m.put(readValue(loader), readValue(loader));
    }
    private void writeBundleContents(BaseBundle b) {
        writeInt(b.map().size());
        for (Map.Entry<String, Object> e : b.map().entrySet()) {
            writeString(e.getKey());
            writeValue(e.getValue());
        }
    }
    private void readBundleContents(BaseBundle b, ClassLoader loader, int n) {
        for (int i = 0; i < n; i++) {
            String key = readString();
            b.mMap.put(key, readValue(loader));
        }
    }
    /** Decodes lazily captured bundle contents (count, then key/value pairs). */
    void readBundleContentsInto$artzig(BaseBundle b, ClassLoader loader) {
        int n = readInt();
        readBundleContents(b, loader, n);
    }
    /**
     * Bundles travel as a byte-length-prefixed envelope so the reader can
     * capture them without decoding (AOSP writes a length + magic the same
     * way). A still-parcelled Bundle is copied through raw, so a process that
     * only forwards it never needs the classes inside.
     */
    public void writeBundle(Bundle b) {
        if (b == null) { writeInt(-1); return; }
        int lengthPos = dataPosition();
        writeInt(0);
        int start = dataPosition();
        if (b.isParcelled()) {
            appendFrom(b.mParcelledData, 0, b.mParcelledData.dataSize());
        } else {
            writeBundleContents(b);
        }
        int end = dataPosition();
        setDataPosition(lengthPos);
        writeInt(end - start);
        setDataPosition(end);
    }
    public void writeNoException() { writeInt(0); }
    /** AOSP encoding: exception code, message, then a service-specific error code when applicable. */
    public void writeException(Exception e) {
        int code = getExceptionCode(e);
        writeInt(code);
        if (code == 0) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        }
        writeString(e.getMessage());
        if (code == EX_SERVICE_SPECIFIC) writeInt(((ServiceSpecificException) e).errorCode);
    }
    public static int getExceptionCode(Throwable e) {
        if (e instanceof Parcelable && e.getClass().getClassLoader() == Parcelable.class.getClassLoader()) return EX_PARCELABLE;
        if (e instanceof SecurityException) return EX_SECURITY;
        if (e instanceof BadParcelableException) return EX_BAD_PARCELABLE;
        if (e instanceof IllegalArgumentException) return EX_ILLEGAL_ARGUMENT;
        if (e instanceof NullPointerException) return EX_NULL_POINTER;
        if (e instanceof IllegalStateException) return EX_ILLEGAL_STATE;
        if (e instanceof android.os.NetworkOnMainThreadException) return EX_NETWORK_MAIN_THREAD;
        if (e instanceof UnsupportedOperationException) return EX_UNSUPPORTED_OPERATION;
        if (e instanceof ServiceSpecificException) return EX_SERVICE_SPECIFIC;
        return 0;
    }
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
            Class<?> cls = Class.forName(name, true, loader != null ? loader : defaultClassLoader$artzig(getClass()));
            return (Parcelable.Creator<?>) cls.getField("CREATOR").get(null);
        } catch (ReflectiveOperationException e) {
            throw new BadParcelableException(e.toString());
        }
    }
    public void readException(int code, String msg) {
        Exception e = createExceptionOrNull(code, msg);
        if (e != null) SneakyThrow.sneakyThrow(e);
    }
    public Exception createException(int code, String msg) {
        Exception e = createExceptionOrNull(code, msg);
        return e != null ? e : new RuntimeException("Unknown exception code: " + code + " msg " + msg);
    }
    private Exception createExceptionOrNull(int code, String msg) {
        switch (code) {
            case EX_SECURITY: return new SecurityException(msg);
            case EX_BAD_PARCELABLE: return new BadParcelableException(msg);
            case EX_ILLEGAL_ARGUMENT: return new IllegalArgumentException(msg);
            case EX_NULL_POINTER: return new NullPointerException(msg);
            case EX_ILLEGAL_STATE: return new IllegalStateException(msg);
            case EX_NETWORK_MAIN_THREAD: return new android.os.NetworkOnMainThreadException();
            case EX_UNSUPPORTED_OPERATION: return new UnsupportedOperationException(msg);
            case EX_SERVICE_SPECIFIC: return new ServiceSpecificException(readInt(), msg);
            case EX_PARCELABLE: return new BadParcelableException("Parcelable exceptions are not forwarded: " + msg);
            case EX_TRANSACTION_FAILED: return new RemoteException(msg != null ? msg : "Transaction failed");
            default: return null;
        }
    }
    /** Rethrows a checked exception without declaring it, as the AOSP reader does for RemoteException. */
    private static final class SneakyThrow {
        static void sneakyThrow(Throwable t) { SneakyThrow.<RuntimeException>sneakyThrow0(t); }
        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow0(Throwable t) throws T { throw (T) t; }
    }
    public void writeArrayMap(android.util.ArrayMap<String, Object> val) {
        if (val == null) { writeInt(-1); return; }
        writeInt(val.size());
        for (int i = 0; i < val.size(); i++) { writeString(val.keyAt(i)); writeValue(val.valueAt(i)); }
    }
    public void readArrayMap(android.util.ArrayMap outVal, ClassLoader loader) {
        int n = readInt();
        for (int i = 0; i < n; i++) outVal.put(readString(), readValue(loader));
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
    public IBinder readStrongBinder() {
        int offset = dataPosition();
        int marker = readInt();
        ObjectEntry e = objectAt(offset);
        if (e == null || e.kind != ObjectEntry.KIND_BINDER) {
            if (marker == 0) return null;
            throw new BadParcelableException("no binder at offset " + offset);
        }
        return e.binder;
    }
    public Object readValue(ClassLoader loader) {
        int type = readInt();
        switch (type) {
            case VAL_NULL: return null;
            case VAL_STRING: return readString();
            case VAL_INTEGER: return readInt();
            case VAL_MAP: return readHashMap(loader);
            case VAL_BUNDLE: return readBundle(loader);
            case VAL_PERSISTABLEBUNDLE: return readPersistableBundle(loader);
            case VAL_PARCELABLE: return readParcelable(loader);
            case VAL_SHORT: return (short) readInt();
            case VAL_LONG: return readLong();
            case VAL_FLOAT: return readFloat();
            case VAL_DOUBLE: return readDouble();
            case VAL_BOOLEAN: return readInt() == 1;
            case VAL_CHARSEQUENCE: return readCharSequence();
            case VAL_LIST: { ArrayList l = new ArrayList(); readList(l, loader); return l; }
            case VAL_SPARSEARRAY: return readSparseArray(loader);
            case VAL_BOOLEANARRAY: return createBooleanArray();
            case VAL_BYTEARRAY: return createByteArray();
            case VAL_STRINGARRAY: return createStringArray();
            case VAL_CHARSEQUENCEARRAY: return readCharSequenceArray();
            case VAL_IBINDER: return readStrongBinder();
            case VAL_PARCELABLEARRAY: return readParcelableArray(loader);
            case VAL_OBJECTARRAY: return readArray(loader);
            case VAL_INTARRAY: return createIntArray();
            case VAL_SHORTARRAY: return createShortArray();
            case VAL_CHARARRAY: return createCharArray();
            case VAL_LONGARRAY: return createLongArray();
            case VAL_DOUBLEARRAY: return createDoubleArray();
            case VAL_FLOATARRAY: return createFloatArray();
            case VAL_BYTE: return (byte) readInt();
            case VAL_CHAR: return (char) readInt();
            case VAL_SPARSEBOOLEANARRAY: return readSparseBooleanArray();
            case VAL_SIZE: return new android.util.Size(readInt(), readInt());
            case VAL_SIZEF: return new android.util.SizeF(readFloat(), readFloat());
            case VAL_SERIALIZABLE: return readSerializable(loader, java.io.Serializable.class);
            default: throw new BadParcelableException("Parcel " + this + ": Unmarshalling unknown type code " + type + " at offset " + dataPosition());
        }
    }
    public void readBooleanArray(boolean[] out) {
        int n = readInt();
        for (int i = 0; i < n && i < out.length; i++) out[i] = readBoolean();
    }
    public java.util.HashMap readHashMap(ClassLoader loader) {
        int n = readInt();
        if (n < 0) return null;
        java.util.HashMap m = new java.util.HashMap();
        for (int i = 0; i < n; i++) m.put(readValue(loader), readValue(loader));
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
            Class<?> cls = Class.forName(name, true, loader != null ? loader : defaultClassLoader$artzig(getClass()));
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
        if (n < 0) return null;
        PersistableBundle b = new PersistableBundle();
        readBundleContents(b, loader, n);
        return b;
    }
    public void writePersistableBundle(PersistableBundle b) {
        if (b == null) { writeInt(-1); return; }
        writeBundleContents(b);
    }
    public Bundle readBundle() { return readBundle(null); }
    public Bundle readBundle(ClassLoader loader) {
        int length = readInt();
        if (length < 0) return null;
        int start = dataPosition();
        Parcel slice = Parcel.obtain();
        slice.appendFrom(this, start, length);
        slice.setDataPosition(0);
        setDataPosition(start + length);
        Bundle b = new Bundle();
        b.mParcelledData = slice;
        if (loader != null) b.setClassLoader(loader);
        return b;
    }
    public <T> T readParcelable(ClassLoader loader, Class<T> clazz) { return (T) readParcelable(loader); }
    public void readException() {
        int code = readExceptionCode();
        if (code != 0) readException(code, readString());
    }
    public int readExceptionCode() { return readInt(); }
    /** Token layout: strict-mode policy int, then the descriptor (AOSP writes both). */
    public void writeInterfaceToken(String descriptor) {
        writeInt(0);
        writeString(descriptor);
    }
    public void enforceInterface(String descriptor) {
        readInt();
        String got = readString();
        if (descriptor != null && !descriptor.equals(got)) {
            throw new SecurityException("Binder invocation to an incorrect interface: expected "
                    + descriptor + ", got " + got);
        }
    }
    public void enforceNoDataAvail() {
        int avail = dataAvail();
        if (avail > 0) throw new BadParcelableException("Parcel data not fully consumed, unread size: " + avail);
    }
    public boolean hasFileDescriptors() {
        if (objects == null) return false;
        for (ObjectEntry e : objects.values()) if (e.kind == ObjectEntry.KIND_FILE_DESCRIPTOR) return true;
        return false;
    }
    public boolean hasBinders() {
        if (objects == null) return false;
        for (ObjectEntry e : objects.values()) if (e.kind == ObjectEntry.KIND_BINDER) return true;
        return false;
    }
    /** Copies bytes and rebases the side-table entries of the copied range. */
    public void appendFrom(Parcel parcel, int offset, int length) {
        byte[] all = parcel.payloadBytes();
        int len = Math.min(length, all.length - offset);
        if (len <= 0) return;
        int base = dataPosition();
        ensure(len);
        buf.put(all, offset, len);
        grew();
        if (parcel.objects == null) return;
        for (ObjectEntry e : parcel.objects.subMap(offset, offset + len).values()) {
            int rebased = base + (e.offset - offset);
            if (e.kind == ObjectEntry.KIND_FILE_DESCRIPTOR) {
                try {
                    addObject(new ObjectEntry(rebased, e.kind, null, android.system.Os.dup(e.fd), true));
                } catch (android.system.ErrnoException ex) {
                    throw new RuntimeException("dup failed", ex);
                }
            } else {
                addObject(new ObjectEntry(rebased, e.kind, e.binder, null, false));
            }
        }
    }
    /** Like AOSP, only flat data can be marshalled; Binders and descriptors are not portable. */
    public byte[] marshall() {
        if (objects != null && !objects.isEmpty()) {
            throw new RuntimeException("Tried to marshall a Parcel that contained Binder objects.");
        }
        return payloadBytes();
    }
    public void unmarshall(byte[] data, int offset, int length) {
        clearObjects();
        buf.position(0); ensure(length);
        buf.put(data, offset, length);
        size = length; buf.position(0);
    }
}
