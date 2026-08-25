/* artzig host implementation backed by a native GBM buffer object. */
package android.hardware;
import android.os.Parcel;
import android.os.Parcelable;
public final class HardwareBuffer implements Parcelable, AutoCloseable {
    public static final int RGBA_8888 = 1, RGBX_8888 = 2, RGB_888 = 3, RGB_565 = 4,
            RGBA_FP16 = 0x16, RGBA_1010102 = 0x2b, BLOB = 0x21, YCBCR_420_888 = 0x23,
            D_16 = 0x30, D_24 = 0x31, DS_24UI8 = 0x32, D_FP32 = 0x33, DS_FP32UI8 = 0x34,
            S_UI8 = 0x35, YCBCR_P010 = 0x36, R_8 = 0x38, R_16 = 0x39, RG_1616 = 0x3a,
            RGBA_10101010 = 0x3b, YCBCR_P210 = 0x3c;
    public static final long USAGE_CPU_READ_RARELY = 2, USAGE_CPU_READ_OFTEN = 3,
            USAGE_CPU_WRITE_RARELY = 2 << 4, USAGE_CPU_WRITE_OFTEN = 3 << 4,
            USAGE_GPU_SAMPLED_IMAGE = 1 << 8, USAGE_GPU_COLOR_OUTPUT = 1 << 9,
            USAGE_COMPOSER_OVERLAY = 1 << 11, USAGE_PROTECTED_CONTENT = 1 << 14,
            USAGE_VIDEO_ENCODE = 1 << 16, USAGE_GPU_DATA_BUFFER = 1 << 24,
            USAGE_SENSOR_DIRECT_DATA = 1 << 23, USAGE_GPU_CUBE_MAP = 1L << 25,
            USAGE_GPU_MIPMAP_COMPLETE = 1L << 26, USAGE_FRONT_BUFFER = 1L << 32;
    private long mNativeObject;

    /* Called by native code when an existing AHardwareBuffer crosses into Java. */
    private HardwareBuffer(long nativeObject) {
        if (nativeObject == 0) throw new IllegalArgumentException("null native buffer");
        mNativeObject = nativeObject;
    }

    public static HardwareBuffer create(int width, int height, int format, int layers, long usage) {
        validate(width, height, format, layers, usage);
        long nativeObject = nCreateHardwareBuffer(width, height, format, layers, usage);
        if (nativeObject == 0) {
            throw new IllegalArgumentException("unsupported HardwareBuffer description");
        }
        return new HardwareBuffer(nativeObject);
    }
    public static boolean isSupported(int width, int height, int format, int layers, long usage) {
        validate(width, height, format, layers, usage);
        return nIsSupported(width, height, format, layers, usage);
    }
    private static void validate(int width, int height, int format, int layers, long usage) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid dimensions");
        if (layers != 1) throw new IllegalArgumentException("only one layer is supported");
        if (usage == 0) throw new IllegalArgumentException("usage must be non-zero");
        if (format == BLOB && height != 1) throw new IllegalArgumentException("BLOB height must be 1");
    }
    private long requireNativeObject() {
        if (mNativeObject == 0) throw new IllegalStateException("HardwareBuffer is closed");
        return mNativeObject;
    }
    public int getWidth() { return nGetWidth(requireNativeObject()); }
    public int getHeight() { return nGetHeight(requireNativeObject()); }
    public int getFormat() { return nGetFormat(requireNativeObject()); }
    public int getLayers() { return nGetLayers(requireNativeObject()); }
    public long getUsage() { return nGetUsage(requireNativeObject()); }
    public long getId() { return nGetId(requireNativeObject()); }
    public boolean isClosed() { return mNativeObject == 0; }
    @Override public void close() {
        long nativeObject = mNativeObject;
        if (nativeObject != 0) {
            mNativeObject = 0;
            nRelease(nativeObject);
        }
    }
    @Override protected void finalize() throws Throwable {
        try { close(); } finally { super.finalize(); }
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {}
    public static final Creator<HardwareBuffer> CREATOR = new Creator<HardwareBuffer>() {
        public HardwareBuffer createFromParcel(Parcel in) { return null; }
        public HardwareBuffer[] newArray(int size) { return new HardwareBuffer[size]; }
    };

    private static native long nCreateHardwareBuffer(int width, int height, int format,
            int layers, long usage);
    private static native boolean nIsSupported(int width, int height, int format,
            int layers, long usage);
    private static native void nRelease(long nativeObject);
    private static native int nGetWidth(long nativeObject);
    private static native int nGetHeight(long nativeObject);
    private static native int nGetFormat(long nativeObject);
    private static native int nGetLayers(long nativeObject);
    private static native long nGetUsage(long nativeObject);
    private static native long nGetId(long nativeObject);
}
