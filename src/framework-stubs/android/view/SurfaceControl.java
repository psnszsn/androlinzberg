/* artzig stub: no SurfaceFlinger on host. */
package android.view;
import android.os.Parcel;
import android.os.Parcelable;
import android.annotation.DurationNanosLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import com.android.window.flags.Flags;
public final class SurfaceControl implements Parcelable {
    public long mNativeObject;
    private String mName;
    public SurfaceControl() {}
    public SurfaceControl(SurfaceControl other, String callsite) { mName = other.mName; }
    /** AOSP Java diagnostic name; this does not allocate a compositor layer. */
    public String getName() { return mName; }
    /** AOSP mirrorSurface enters SurfaceComposerClient; the hosted backend has no layer mirrors. */
    public static SurfaceControl mirrorSurface(SurfaceControl mirrorOf) {
        throw dev.artzig.ArtzigTestDiagnostics.unsupported("android.view.SurfaceControl",
                "mirrorSurface", "SurfaceControl layer mirroring is not hosted");
    }
    public boolean isValid() { return false; }
    /** AOSP getLayerId(): an unallocated SurfaceControl has no compositor layer. */
    public int getLayerId() { return -1; }
    /** AOSP nativeCreateJankDataListenerWrapper returns zero for an unallocated surface.
     * Keep the Java listener until release, with no compositor subscription. */
    public OnJankDataListenerRegistration addOnJankDataListener(OnJankDataListener listener) {
        return new OnJankDataListenerRegistration(listener);
    }

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
        /** AOSP sendSurfaceFlushJankData rejects an unallocated SurfaceControl. */
        public static void sendSurfaceFlushJankData(SurfaceControl sc) {
            throw new NullPointerException("mNativeObject of " + sc
                    + " is null. Have you called release() already?");
        }

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
        /** AOSP Transaction.apply(boolean) requires a compositor acknowledgement for sync.
         * The stock class needs SurfaceFlinger JNI; the hosted window service owns that gap. */
        public void apply(boolean sync) {
            dev.artzig.system.HostedWindowManagerService.applySurfaceTransaction(sync);
        }
        public void applyAsyncUnsafe() {}
        @Override public void close() {}
        public Transaction merge(Transaction other) { return this; }
        /** AOSP Transaction::sanitize removes privileged flags from each composer
         * state. Hosted transactions currently contain no composer states. */
        public void sanitize(int pid, int uid) {}
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
    // Verbatim AOSP SurfaceControl.JankData and listener contract. The enclosing
    // SurfaceControl still needs the absent SurfaceFlinger/native layer stack.
    @FlaggedApi(Flags.FLAG_JANK_API)
    public static class JankData {

        /**
         * Needs to be kept in sync with android_view_SurfaceControl.cpp's
         * JankDataListenerWrapper::onJankDataAvailable.
         * @hide
         */
        @IntDef(flag = true, value = {
            JANK_NONE,
            JANK_COMPOSER,
            JANK_APPLICATION,
            JANK_OTHER,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface JankType {}

        /**
         * No jank detected, the frame was on time.
         */
        public static final int JANK_NONE = 0;

        /**
         * Bitmask for jank due to deadlines missed by the composer.
         */
        public static final int JANK_COMPOSER = 1 << 0;

        /**
         * Bitmask for jank due to deadlines missed by the application.
         */
        public static final int JANK_APPLICATION = 1 << 1;

        /**
         * Bitmask for jank due to deadlines missed by other system components.
         */
        public static final int JANK_OTHER = 1 << 2;

        private final long mFrameVsyncId;
        private final @JankType int mJankType;
        private final @DurationNanosLong long mFrameIntervalNs;
        private final @DurationNanosLong long mScheduledAppFrameTimeNs;
        private final @DurationNanosLong long mActualAppFrameTimeNs;

        /**
         * @hide
         */
        public JankData(long frameVsyncId, @JankType int jankType, long frameIntervalNs,
                long scheduledAppFrameTimeNs, long actualAppFrameTimeNs) {
            mFrameVsyncId = frameVsyncId;
            mJankType = jankType;
            mFrameIntervalNs = frameIntervalNs;
            mScheduledAppFrameTimeNs = scheduledAppFrameTimeNs;
            mActualAppFrameTimeNs = actualAppFrameTimeNs;
        }

        /**
         * Returns the id of the frame for this jank classification.
         *
         * @see FrameMetrics#FRAME_TIMELINE_VSYNC_ID
         * @see Choreographer.FrameTimeline#getVsyncId
         * @see Transaction#setFrameTimeline
         * @return the frame id
         */
        public long getVsyncId() {
            return mFrameVsyncId;
        }

        /**
         * Returns the bitmask indicating the types of jank observed.
         *
         * @return the jank type bitmask
         */
        public @JankType int getJankType() {
            return mJankType;
        }

        /**
         * Returns the time between frame VSyncs in nanoseconds.
         *
         * @return the frame interval in ns
         * @hide
         */
        public @DurationNanosLong long getFrameIntervalNanos() {
            return mFrameIntervalNs;
        }

        /**
         * Returns the duration in nanoseconds the application was scheduled to use to render this
         * frame.
         * <p>
         * Note that this may be higher than the frame interval to allow for CPU/GPU
         * parallelization of work.
         *
         * @return scheduled app time in ns
         */
        public @DurationNanosLong long getScheduledAppFrameTimeNanos() {
            return mScheduledAppFrameTimeNs;
        }

        /**
         * Returns the actual time in nanoseconds taken by the application to render this frame.
         *
         * @return the actual app time in ns
         */
        public @DurationNanosLong long getActualAppFrameTimeNanos() {
            return mActualAppFrameTimeNs;
        }

        @Override
        public String toString() {
            return "JankData{vsync=" + mFrameVsyncId
                    + ", jankType=0x" + Integer.toHexString(mJankType)
                    + ", frameInterval=" + mFrameIntervalNs + "ns"
                    + ", scheduledAppTime=" + mScheduledAppFrameTimeNs + "ns"
                    + ", actualAppTime=" + mActualAppFrameTimeNs + "ns}";
        }
    }

    /**
     * Listener interface to be informed about SurfaceFlinger's jank classification for a specific
     * surface.
     *
     * @see JankData
     * @see #addOnJankDataListener
     */
    @FlaggedApi(Flags.FLAG_JANK_API)
    public interface OnJankDataListener {
        /**
         * Called when new jank classifications are available. The listener is invoked out of band
         * of the rendered frames with jank classification data for a batch of frames.
         */
        void onJankDataAvailable(@NonNull List<JankData> jankData);

    }


    public static final class DisplayPrimaries {
        public CieXyz red, green, blue, white;
        public static final class CieXyz { public float X, Y, Z; }
    }
    public static class OnJankDataListenerRegistration {
        public static final OnJankDataListenerRegistration NONE = new OnJankDataListenerRegistration(null);
        private OnJankDataListener mListener;
        private OnJankDataListenerRegistration(OnJankDataListener listener) { mListener = listener; }
        // AOSP flush/remove return immediately for a zero native wrapper.
        public void flush() {}
        public void removeAfter(long afterVsync) {}
        public void release() { mListener = null; }
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
        private String mName;
        public Builder() {}
        public Builder(SurfaceSession session) {}
        public Builder setFlags(int flags) { return this; }
        public Builder setContainerLayer() { return this; }
        public Builder setBufferSize(int w, int h) { return this; }
        public Builder setFormat(int format) { return this; }
        public Builder setCallsite(String callsite) { return this; }
        public Builder setHidden(boolean hidden) { return this; }
        public Builder setBLASTLayer() { return this; }
        public Builder setName(String name) { mName = name; return this; }
        public Builder setParent(SurfaceControl parent) { return this; }
        public SurfaceControl build() {
            SurfaceControl control = new SurfaceControl();
            control.mName = mName;
            return control;
        }
    }
}
