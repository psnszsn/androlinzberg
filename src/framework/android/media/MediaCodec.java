/* artzig shim: synchronous MediaCodec over ffmpeg (azk_codec). Audio only for
 * now; video decoders will grow a Surface path. */
package android.media;

import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME = 1;
    public static final int BUFFER_FLAG_SYNC_FRAME = 1;
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;
    public static final int CONFIGURE_FLAG_ENCODE = 1;
    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;
    public static final int CRYPTO_MODE_UNENCRYPTED = 0;

    private static final int NUM_INPUT = 8;
    private static final int NUM_OUTPUT = 8;
    private static final int NUM_VIDEO_OUTPUT = 3;
    private int mOutCap = OUT_CAP;
    private static final int IN_CAP = 1 << 20;
    private static final int OUT_CAP = 1 << 19;


    private final String mName;
    private final String mFfmpegName;
    private long mHandle;
    private final ByteBuffer[] mIn = new ByteBuffer[NUM_INPUT];
    private final ByteBuffer[] mOut = new ByteBuffer[NUM_OUTPUT];
    private final boolean[] mInBusy = new boolean[NUM_INPUT];
    private final boolean[] mOutBusy = new boolean[NUM_OUTPUT];
    private final int[] mMeta = new int[3];
    private final long[] mPts = new long[1];
    private final long[] mFrameHandle = new long[1];
    private MediaFormat mOutputFormat;
    private boolean mFormatReported;
    private int mPendingSlot = -1; // decoded frame waiting for FORMAT_CHANGED to be consumed
    private int mPendingBytes;
    private long mPendingPts;
    private boolean mSawInputEos, mSawOutputEos;
    private final Object mNativeLock = new Object();
    private boolean mVideo;
    private Surface mSurface;
    private Callback mCallback;
    private android.os.Handler mCallbackHandler;
    private boolean mAsyncRunning;
    private final boolean[] mInAnnounced = new boolean[NUM_INPUT];
    private final Runnable mPump = this::pump;
    private final int[] mOutW = new int[NUM_OUTPUT];
    private final int[] mOutH = new int[NUM_OUTPUT];
    private final long[] mOutHardware = new long[NUM_OUTPUT];
    private final long[] mOutSoftware = new long[NUM_OUTPUT];

    private static native long nativeOpen(String codecName);
    private static native int nativeConfigure(long h, byte[] csd, int sampleRate, int channels);
    private static native void nativeSetVideoTarget(long h, int width, int height);
    private static native int nativeSend(long h, ByteBuffer buf, int offset, int size, long ptsUs, int eof);
    private static native int nativeReceiveAudio(long h, ByteBuffer dst, int cap, int[] meta, long[] pts);
    private static native int nativeReceiveVideo(long h, ByteBuffer dst, int cap,
            int[] meta, long[] pts, long[] frameHandle, boolean surfaceOutput);
    private static native void nativeFlush(long h);
    private static native void nativeClose(long h);

    public static final class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
        public void set(int newOffset, int newSize, long newTimeUs, int newFlags) {
            offset = newOffset;
            size = newSize;
            presentationTimeUs = newTimeUs;
            flags = newFlags;
        }
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(int errorCode, String detailMessage) { super(detailMessage); }
        public int getErrorCode() { return 0; }
    }

    public static class CodecException extends IllegalStateException {
        CodecException(String msg) { super(msg); }
        public boolean isTransient() { return false; }
        public boolean isRecoverable() { return false; }
        public int getErrorCode() { return 0; }
        public String getDiagnosticInfo() { return getMessage(); }
    }

    public static final class CryptoInfo {
        public int numSubSamples;
        public int[] numBytesOfClearData;
        public int[] numBytesOfEncryptedData;
        public byte[] key;
        public byte[] iv;
        public int mode;
        public void set(int newNumSubSamples, int[] newNumBytesOfClearData,
                int[] newNumBytesOfEncryptedData, byte[] newKey, byte[] newIV, int newMode) {}
        public void setPattern(Pattern newPattern) {}
        public static final class Pattern {
            public Pattern(int blocksToEncrypt, int blocksToSkip) { set(blocksToEncrypt, blocksToSkip); }
            public void set(int blocksToEncrypt, int blocksToSkip) {}
            public int getEncryptBlocks() { return 0; }
            public int getSkipBlocks() { return 0; }
        }
    }

    private MediaCodec(String name, String ffmpegName) {
        mName = name;
        mFfmpegName = ffmpegName;
    }

    public static MediaCodec createByCodecName(String name) throws java.io.IOException {
        String ff = MediaCodecList.ffmpegNameFor(name);
        if (ff == null) throw new java.io.IOException("unknown codec: " + name);
        return new MediaCodec(name, ff);
    }

    public static MediaCodec createDecoderByType(String mime) throws java.io.IOException {
        String name = MediaCodecList.decoderNameForMime(mime);
        if (name == null) throw new java.io.IOException("no decoder for " + mime);
        return createByCodecName(name);
    }

    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
        if ((flags & CONFIGURE_FLAG_ENCODE) != 0) throw new CodecException("encoders not supported");
        String mime = format != null ? format.getString(MediaFormat.KEY_MIME, "") : "";
        mVideo = mime.startsWith("video/");
        mSurface = surface;
        mHandle = nativeOpen(mFfmpegName);
        if (mHandle == 0) throw new CodecException("open failed: " + mFfmpegName);
        byte[] csd = null;
        if (format != null) {
            // video: concatenate csd-0/csd-1 (e.g. SPS+PPS); audio: csd-0 only
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < (mVideo ? 4 : 1); i++) {
                ByteBuffer b = format.getByteBuffer("csd-" + i);
                if (b == null) break;
                byte[] tmp = new byte[b.remaining()];
                b.duplicate().get(tmp);
                all.write(tmp, 0, tmp.length);
            }
            if (all.size() > 0) csd = all.toByteArray();
        }
        int rate = format != null ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0) : 0;
        int ch = format != null ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0) : 0;
        if (nativeConfigure(mHandle, csd, rate, ch) < 0) throw new CodecException("configure failed");
        int w0 = format != null ? format.getInteger(MediaFormat.KEY_WIDTH, 0) : 0;
        int h0 = format != null ? format.getInteger(MediaFormat.KEY_HEIGHT, 0) : 0;
        // Surface output is transported by an owned native handle. Allocating
        // three decoded-frame-sized Java buffers here retained ~100 MiB for a
        // single 4K format generation even though none of them were written.
        mOutCap = mVideo && surface != null
                ? 1
                : mVideo ? Math.max(w0 * h0 * 4, 1280 * 736 * 4) : OUT_CAP;
        for (int i = 0; i < NUM_INPUT; i++) {
            mIn[i] = ByteBuffer.allocateDirect(IN_CAP).order(ByteOrder.nativeOrder());
        }
        int nOut = mVideo ? NUM_VIDEO_OUTPUT : NUM_OUTPUT;
        for (int i = 0; i < nOut; i++) {
            mOut[i] = ByteBuffer.allocateDirect(mOutCap).order(ByteOrder.nativeOrder());
        }
        if (mVideo) {
            int w = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            int h = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            mOutputFormat = MediaFormat.createVideoFormat(mime, w, h);
            setVideoFormatKeys(mOutputFormat, w, h);
        } else {
            mOutputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW,
                    rate > 0 ? rate : 44100, ch > 0 ? ch : 2);
            mOutputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        }
    }

    private static void setVideoFormatKeys(MediaFormat f, int w, int h) {
        f.setInteger(MediaFormat.KEY_CROP_LEFT, 0);
        f.setInteger(MediaFormat.KEY_CROP_TOP, 0);
        f.setInteger(MediaFormat.KEY_CROP_RIGHT, Math.max(0, w - 1));
        f.setInteger(MediaFormat.KEY_CROP_BOTTOM, Math.max(0, h - 1));
        f.setInteger(MediaFormat.KEY_STRIDE, w);
        f.setInteger(MediaFormat.KEY_SLICE_HEIGHT, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x2A); // RGBA_8888-ish marker
    }

    public void start() {
        if (mCallback != null) {
            if (mCallbackHandler == null) {
                android.os.Looper l = android.os.Looper.myLooper();
                if (l == null) l = android.os.Looper.getMainLooper();
                mCallbackHandler = new android.os.Handler(l);
            }
            java.util.Arrays.fill(mInAnnounced, false);
            mAsyncRunning = true;
            mCallbackHandler.post(mPump);
        }
    }

    public void stop() {
        mAsyncRunning = false;
        releaseHeldFrames();
    }

    private void pump() {
        if (!mAsyncRunning || mCallback == null || mHandle == 0) return;
        try {
            // deliver at most a few output buffers per tick
            for (int n = 0; n < 3; n++) {
                BufferInfo info = new BufferInfo();
                int idx = dequeueOutputBuffer(info, 0);
                if (idx == INFO_OUTPUT_FORMAT_CHANGED) {
                    mCallback.onOutputFormatChanged(this, mOutputFormat);
                } else if (idx >= 0) {
                    mCallback.onOutputBufferAvailable(this, idx, info);
                    if ((info.flags & BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else {
                    break;
                }
                if (!mAsyncRunning) return;
            }
            // Do not let asynchronous video input run arbitrarily far ahead
            // of the three output slots. Decoded RGB frames are large, and the
            // previous unbounded producer looked like a memory leak.
            int inputBudget = NUM_INPUT;
            if (mVideo) {
                inputBudget = 0;
                for (int i = 0; i < NUM_VIDEO_OUTPUT; i++) {
                    if (!mOutBusy[i]) inputBudget++;
                }
            }
            for (int i = 0; i < NUM_INPUT && inputBudget > 0; i++) {
                if (!mInBusy[i] && !mInAnnounced[i]) {
                    mInBusy[i] = true;
                    mInAnnounced[i] = true;
                    mIn[i].clear();
                    mCallback.onInputBufferAvailable(this, i);
                    inputBudget--;
                    if (!mAsyncRunning) return;
                }
            }
        } catch (CodecException e) {
            mCallback.onError(this, e);
        }
        if (mAsyncRunning) mCallbackHandler.postDelayed(mPump, 4);
    }

    public void flush() {
        releaseHeldFrames();
        synchronized (mNativeLock) { if (mHandle != 0) nativeFlush(mHandle); }
        java.util.Arrays.fill(mInBusy, false);
        java.util.Arrays.fill(mOutBusy, false);
        java.util.Arrays.fill(mInAnnounced, false);
        mPendingSlot = -1;
        mSawInputEos = false;
        mSawOutputEos = false;
        mAsyncRunning = false; // resumed by the next start()
    }

    public void release() {
        mAsyncRunning = false;
        releaseHeldFrames();
        synchronized (mNativeLock) {
            if (mHandle != 0) { nativeClose(mHandle); mHandle = 0; }
        }
    }

    public void reset() { release(); }

    public String getName() { return mName; }
    public String getCanonicalName() { return mName; }
    public MediaCodecInfo getCodecInfo() { return MediaCodecList.infoFor(mName); }

    private void releaseHeldFrames() {
        for (int i = 0; i < mOutHardware.length; i++) {
            if (mOutHardware[i] != 0) {
                android.view.ArtzigVideoLayer.releaseHardware(mOutHardware[i]);
                mOutHardware[i] = 0;
            }
            if (mOutSoftware[i] != 0) {
                android.view.ArtzigVideoLayer.releaseSoftware(mOutSoftware[i]);
                mOutSoftware[i] = 0;
            }
        }
    }

    public int dequeueInputBuffer(long timeoutUs) {
        for (int i = 0; i < NUM_INPUT; i++) {
            if (!mInBusy[i]) { mInBusy[i] = true; mIn[i].clear(); return i; }
        }
        return INFO_TRY_AGAIN_LATER;
    }

    public ByteBuffer getInputBuffer(int index) { return mIn[index]; }
    public ByteBuffer[] getInputBuffers() { return mIn; }

    public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
        boolean eos = (flags & BUFFER_FLAG_END_OF_STREAM) != 0;
        if ((flags & BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (mVideo && size > 0) {
                synchronized (mNativeLock) { nativeSend(mHandle, mIn[index], offset, size, 0, 0); }
            }
            mInBusy[index] = false;
            mInAnnounced[index] = false;
            return;
        }
        int r = 0;
        synchronized (mNativeLock) {
            if (mVideo) {
                nativeSetVideoTarget(mHandle,
                        mSurface != null ? android.view.ArtzigVideoLayer.targetWidth(mSurface) : 0,
                        mSurface != null ? android.view.ArtzigVideoLayer.targetHeight(mSurface) : 0);
            }
            if (size > 0) r = nativeSend(mHandle, mIn[index], offset, size, presentationTimeUs, 0);
            if (eos) { nativeSend(mHandle, mIn[index], 0, 0, presentationTimeUs, 1); mSawInputEos = true; }
        }
        mInBusy[index] = false;
        mInAnnounced[index] = false;
        if (r < 0) throw new CodecException("decode failed: " + r);
    }

    public void queueSecureInputBuffer(int index, int offset, CryptoInfo info,
            long presentationTimeUs, int flags) {
        throw new CryptoException(0, "secure buffers not supported");
    }

    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
        int slot = -1;
        int nOut = mVideo ? NUM_VIDEO_OUTPUT : NUM_OUTPUT;
        for (int i = 0; i < nOut; i++) if (!mOutBusy[i]) { slot = i; break; }
        if (slot < 0) return INFO_TRY_AGAIN_LATER;
        if (mSawOutputEos) {
            info.set(0, 0, 0, BUFFER_FLAG_END_OF_STREAM);
            mOut[slot].clear().limit(0);
            mOutBusy[slot] = true;
            return slot;
        }
        if (mPendingSlot >= 0) {
            int p = mPendingSlot;
            mPendingSlot = -1;
            mOut[p].position(0).limit(mPendingBytes);
            info.set(0, mPendingBytes, mPendingPts, 0);
            return p;
        }
        if (mOut[slot].capacity() < mOutCap) {
            mOut[slot] = ByteBuffer.allocateDirect(mOutCap).order(ByteOrder.nativeOrder());
        }
        ByteBuffer dst = mOut[slot];
        dst.clear();
        int r;
        synchronized (mNativeLock) {
            r = mVideo ? nativeReceiveVideo(mHandle, dst, mOutCap, mMeta, mPts, mFrameHandle, mSurface != null)
                       : nativeReceiveAudio(mHandle, dst, mOutCap, mMeta, mPts);
        }
        while (r == -2 && mVideo) { // frame larger than our buffers: retain, grow, retry
            int required = mMeta[0];
            int doubled = mOutCap <= Integer.MAX_VALUE / 2
                    ? mOutCap * 2 : Integer.MAX_VALUE;
            int newCap = Math.max(required, doubled);
            if (newCap <= mOutCap) throw new CodecException("video frame is too large");
            mOutCap = newCap;
            // Do not replace buffers currently owned by the client. They are
            // resized lazily when their slots become available again.
            for (int i = 0; i < NUM_VIDEO_OUTPUT; i++) {
                if (!mOutBusy[i] && mOut[i].capacity() < mOutCap) {
                    mOut[i] = ByteBuffer.allocateDirect(mOutCap).order(ByteOrder.nativeOrder());
                }
            }
            dst = mOut[slot];
            dst.clear();
            synchronized (mNativeLock) {
                r = nativeReceiveVideo(mHandle, dst, mOutCap, mMeta, mPts, mFrameHandle, mSurface != null);
            }
        }
        if (r == 1) return INFO_TRY_AGAIN_LATER;
        if (r == 2) {
            mSawOutputEos = true;
            info.set(0, 0, 0, BUFFER_FLAG_END_OF_STREAM);
            dst.limit(0);
            mOutBusy[slot] = true;
            return slot;
        }
        if (r < 0) throw new CodecException("receive failed: " + r);
        if (mVideo) {
            mOutHardware[slot] = r == 3 ? mFrameHandle[0] : 0;
            mOutSoftware[slot] = r == 4 ? mFrameHandle[0] : 0;
        }
        boolean formatChanged;
        if (mVideo) {
            formatChanged = !mFormatReported
                    || mMeta[1] != mOutputFormat.getInteger(MediaFormat.KEY_WIDTH, 0)
                    || mMeta[2] != mOutputFormat.getInteger(MediaFormat.KEY_HEIGHT, 0);
            if (formatChanged) {
                mOutputFormat = MediaFormat.createVideoFormat(
                        mOutputFormat.getString(MediaFormat.KEY_MIME), mMeta[1], mMeta[2]);
                setVideoFormatKeys(mOutputFormat, mMeta[1], mMeta[2]);
            }
            mOutW[slot] = mMeta[1];
            mOutH[slot] = mMeta[2];
        } else {
            formatChanged = !mFormatReported
                    || mMeta[1] != mOutputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
                    || mMeta[2] != mOutputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
            if (formatChanged) {
                mOutputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW,
                        mMeta[1], mMeta[2]);
                mOutputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }
        }
        if (formatChanged) {
            mFormatReported = true;
            mPendingSlot = slot;
            mPendingBytes = mMeta[0];
            mPendingPts = mPts[0];
            mOutBusy[slot] = true;
            return INFO_OUTPUT_FORMAT_CHANGED;
        }
        dst.position(0).limit(mMeta[0]);
        info.set(0, mMeta[0], mPts[0], 0);
        mOutBusy[slot] = true;
        return slot;
    }

    public ByteBuffer getOutputBuffer(int index) { return mOut[index]; }
    public ByteBuffer[] getOutputBuffers() { return mOut; }

    public void releaseOutputBuffer(int index, boolean render) {
        long hardware = mOutHardware[index];
        long software = mOutSoftware[index];
        mOutHardware[index] = 0;
        mOutSoftware[index] = 0;
        if (hardware != 0) {
            if (render && mSurface != null) {
                android.view.ArtzigVideoLayer.pushHardware(mSurface, hardware);
            } else {
                android.view.ArtzigVideoLayer.releaseHardware(hardware);
            }
        } else if (software != 0) {
            if (render && mSurface != null) {
                android.view.ArtzigVideoLayer.pushSoftware(mSurface, software);
            } else {
                android.view.ArtzigVideoLayer.releaseSoftware(software);
            }
        } else if (render && mVideo && mSurface != null && mOutW[index] > 0) {
            ByteBuffer b = mOut[index];
            b.position(0);
            android.view.ArtzigVideoLayer.push(mSurface, b, mOutW[index], mOutH[index]);
        }
        mOutBusy[index] = false;
    }
    public void releaseOutputBuffer(int index, long renderTimestampNs) {
        releaseOutputBuffer(index, true);
    }

    public MediaFormat getOutputFormat() { return mOutputFormat; }
    public MediaFormat getInputFormat() { return mOutputFormat; }

    public interface OnFrameRenderedListener {
        void onFrameRendered(MediaCodec codec, long presentationTimeUs, long nanoTime);
    }
    public abstract static class Callback {
        public Callback() {}
        public abstract void onInputBufferAvailable(MediaCodec codec, int index);
        public abstract void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info);
        public abstract void onError(MediaCodec codec, CodecException e);
        public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format);
    }
    public void setCallback(Callback cb) { setCallback(cb, null); }
    public void setCallback(Callback cb, android.os.Handler handler) {
        mCallback = cb;
        mCallbackHandler = handler;
    }
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, android.os.Handler handler) {}
    public void setVideoScalingMode(int mode) {}
    public void setOutputSurface(Surface surface) { mSurface = surface; }
    public void setParameters(android.os.Bundle params) {}
    public android.os.PersistableBundle getMetrics() { return new android.os.PersistableBundle(); }
}
