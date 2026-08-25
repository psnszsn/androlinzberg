/* artzig stub: no SurfaceFlinger on host. */
package android.view;
import android.os.Parcel;
import android.os.Parcelable;
public final class SurfaceControl implements Parcelable {
    public long mNativeObject;
    public SurfaceControl() {}
    public SurfaceControl(SurfaceControl other, String callsite) {}
    public boolean isValid() { return false; }
    public void release() {}
    public void setUnreleasedWarningCallSite(String callsite) {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public void readFromParcel(Parcel in) {}
    public static final Creator<SurfaceControl> CREATOR = new Creator<SurfaceControl>() {
        public SurfaceControl createFromParcel(Parcel in) { return new SurfaceControl(); }
        public SurfaceControl[] newArray(int size) { return new SurfaceControl[size]; }
    };
    public static final int BUFFER_TRANSFORM_IDENTITY = 0, BUFFER_TRANSFORM_ROTATE_90 = 4,
            BUFFER_TRANSFORM_ROTATE_180 = 3, BUFFER_TRANSFORM_ROTATE_270 = 7;
    public static final int HIDDEN = 4, OPAQUE = 1024;
    public static final int FRAME_RATE_SELECTION_STRATEGY_PROPAGATE = 0,
            FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN = 1,
            FRAME_RATE_SELECTION_STRATEGY_SELF = 2;
    public boolean isSameSurface(SurfaceControl other) { return this == other; }
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    public static final class Transaction implements AutoCloseable, android.os.Parcelable {
        public long mNativeObject;
        public Transaction() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<Transaction> CREATOR = new Creator<Transaction>() {
            public Transaction createFromParcel(android.os.Parcel in) { return new Transaction(); }
            public Transaction[] newArray(int size) { return new Transaction[size]; }
        };
        public Transaction show(SurfaceControl sc) { return this; }
        public Transaction hide(SurfaceControl sc) { return this; }
        public Transaction setAlpha(SurfaceControl sc, float alpha) { return this; }
        public Transaction setPosition(SurfaceControl sc, float x, float y) { return this; }
        public Transaction setLayer(SurfaceControl sc, int z) { return this; }
        public Transaction remove(SurfaceControl sc) { return this; }
        public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) { return this; }
        public void apply() {}
        public void applyAsyncUnsafe() {}
        @Override public void close() {}
        public Transaction merge(Transaction other) { return this; }
        public Transaction setMatrix(SurfaceControl sc, float dsdx, float dtdx, float dtdy, float dsdy) { return this; }
        public Transaction setMatrix(SurfaceControl sc, android.graphics.Matrix matrix, float[] float9) { return this; }
        public Transaction setFrameRateCategory(SurfaceControl sc, int category, boolean smoothSwitchOnly) { return this; }
        public Transaction setFrameRateSelectionStrategy(SurfaceControl sc, int strategy) { return this; }
        public Transaction setEarlyWakeupStart() { return this; }
        public Transaction setEarlyWakeupEnd() { return this; }
        public Transaction setWindowCrop(SurfaceControl sc, android.graphics.Rect crop) { return this; }
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) { return this; }
        public Transaction setCornerRadius(SurfaceControl sc, float cornerRadius) { return this; }
        public Transaction setBackgroundBlurRadius(SurfaceControl sc, int radius) { return this; }
        public Transaction setTraceTag(long traceTag) { return this; }
        public Transaction setOpaque(SurfaceControl sc, boolean opaque) { return this; }
        public Transaction setTrustedOverlay(SurfaceControl sc, boolean isTrustedOverlay) { return this; }
        public Transaction setFrameTimelineVsync(long frameTimelineVsyncId) { return this; }
        public Transaction setFrameRate(SurfaceControl sc, float frameRate, int compatibility) { return this; }
        public Transaction setBufferSize(SurfaceControl sc, int w, int h) { return this; }
        public Transaction setCrop(SurfaceControl sc, android.graphics.Rect crop) { return this; }
        public Transaction setVisibility(SurfaceControl sc, boolean visible) { return this; }
        public Transaction addTransactionCommittedListener(java.util.concurrent.Executor executor, TransactionCommittedListener listener) { return this; }
    }
    public interface TransactionCommittedListener { void onTransactionCommitted(); }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface BufferTransform {}
    public interface OnJankDataListener { void onJankDataAvailable(Object[] jankData); }
    public static final class DisplayPrimaries {
        public CieXyz red, green, blue, white;
        public static final class CieXyz { public float X, Y, Z; }
    }
    public static class OnJankDataListenerRegistration {
        public static final OnJankDataListenerRegistration NONE = new OnJankDataListenerRegistration();
        public void removeAfter(long afterVsync) {}
        public void release() {}
    }
    public static final class RefreshRateRange implements android.os.Parcelable {
        public float min, max;
        public RefreshRateRange() {}
        public RefreshRateRange(float min, float max) { this.min = min; this.max = max; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeFloat(min); dest.writeFloat(max);
        }
        public static final Creator<RefreshRateRange> CREATOR = new Creator<RefreshRateRange>() {
            public RefreshRateRange createFromParcel(android.os.Parcel in) {
                return new RefreshRateRange(in.readFloat(), in.readFloat());
            }
            public RefreshRateRange[] newArray(int size) { return new RefreshRateRange[size]; }
        };
    }
    public static final class Builder {
        public Builder() {}
        public Builder(SurfaceSession session) {}
        public Builder setFlags(int flags) { return this; }
        public Builder setContainerLayer() { return this; }
        public Builder setBufferSize(int w, int h) { return this; }
        public Builder setFormat(int format) { return this; }
        public Builder setCallsite(String callsite) { return this; }
        public Builder setHidden(boolean hidden) { return this; }
        public Builder setBLASTLayer() { return this; }
        public Builder setName(String name) { return this; }
        public Builder setParent(SurfaceControl parent) { return this; }
        public SurfaceControl build() { return new SurfaceControl(); }
    }
}
