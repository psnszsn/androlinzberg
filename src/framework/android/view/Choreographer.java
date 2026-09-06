/* artzig shim: android.view.Choreographer — callbacks are driven by the
 * Wayland compositor frame used to render the view tree. */
package android.view;

import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;

public final class Choreographer {
    public static final int CALLBACK_INPUT = 0, CALLBACK_ANIMATION = 1,
            CALLBACK_INSETS_ANIMATION = 2, CALLBACK_TRAVERSAL = 3, CALLBACK_COMMIT = 4;
    private static final int CALLBACK_LAST = CALLBACK_COMMIT;
    private static final long FRAME_INTERVAL_MS = 16;
    private static final Object FRAME_CALLBACK_TOKEN = new Object();
    private static final ThreadLocal<Choreographer> sThreadInstance = new ThreadLocal<>();
    private static volatile Choreographer sMainInstance;
    private static final boolean TRACE_SLOW_FRAMES =
            System.getenv("ARTZIG_SLOW_FRAME_TRACE") != null;

    public interface FrameCallback { void doFrame(long frameTimeNanos); }

    private static final class CallbackRecord {
        final int type;
        final Object action;
        final Object token;
        final long dueTime;

        CallbackRecord(int type, Object action, Object token, long dueTime) {
            this.type = type;
            this.action = action;
            this.token = token;
            this.dueTime = dueTime;
        }

        void run(long frameTimeNanos) {
            if (token == FRAME_CALLBACK_TOKEN) {
                ((FrameCallback) action).doFrame(frameTimeNanos);
            } else {
                ((Runnable) action).run();
            }
        }
    }

    private final Looper mLooper;
    private final Handler mHandler;
    private final Runnable mWakeForFrame = this::onWakeForFrame;
    private final ArrayList<CallbackRecord> mCallbacks = new ArrayList<>();
    private long mLastFrameTimeNanos;
    private long mWakeTime = Long.MAX_VALUE;

    private Choreographer(Looper looper) {
        mLooper = looper;
        // DisplayEventReceiver scheduling messages are asynchronous in AOSP so
        // they can pass ViewRootImpl's traversal synchronization barrier.
        mHandler = Handler.createAsync(looper);
    }

    public static Choreographer getInstance() {
        Choreographer c = sThreadInstance.get();
        if (c == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalStateException("The current thread must have a looper!");
            }
            c = new Choreographer(looper);
            sThreadInstance.set(c);
            if (looper == Looper.getMainLooper()) sMainInstance = c;
        }
        return c;
    }

    public static long getFrameDelay() { return FRAME_INTERVAL_MS; }
    // Pinned Choreographer ownership/accessors; frame delivery remains hosted.
    public Looper getLooper() { return mLooper; }
    public static Choreographer getMainThreadInstance() { return sMainInstance; }
    public static void setFrameDelay(long delay) {}
    public static long subtractFrameDelay(long delayMillis) {
        return delayMillis <= FRAME_INTERVAL_MS ? 0 : delayMillis - FRAME_INTERVAL_MS;
    }
    public long getFrameIntervalNanos() { return FRAME_INTERVAL_MS * 1_000_000; }
    public long getFrameTimeNanos() {
        return mLastFrameTimeNanos != 0 ? mLastFrameTimeNanos : System.nanoTime();
    }
    public long getFrameTime() { return getFrameTimeNanos() / 1_000_000; }
    public long getVsyncId() { return 0; }
    public long getLastFrameTimeNanos() { return getFrameTimeNanos(); }
    public boolean isTheLooperSame(Looper looper) { return mLooper == looper; }
    public static Choreographer getSfInstance() { return getInstance(); }

    /** Pinned Choreographer.dump, using the callback queue and wake time owned by
     * the hosted frame driver instead of DisplayEventReceiver's vsync schedule. */
    void dump(String prefix, java.io.PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        writer.println(prefix + "Choreographer:");
        synchronized (mCallbacks) {
            writer.println(innerPrefix + "mPendingCallbackCount=" + mCallbacks.size());
            writer.println(innerPrefix + "mWakeTime=" + mWakeTime);
        }
        writer.println(innerPrefix + "mLastFrameTime="
                + android.util.TimeUtils.formatUptime(mLastFrameTimeNanos / 1000000));
    }

    public void postFrameCallback(FrameCallback callback) {
        postFrameCallbackDelayed(callback, 0);
    }

    public void postFrameCallbackDelayed(FrameCallback callback, long delayMillis) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        addCallback(CALLBACK_ANIMATION, callback, FRAME_CALLBACK_TOKEN, delayMillis);
    }

    public void removeFrameCallback(FrameCallback callback) {
        removeMatching(CALLBACK_ANIMATION, callback, FRAME_CALLBACK_TOKEN);
    }

    public void postCallback(int callbackType, Runnable action, Object token) {
        postCallbackDelayed(callbackType, action, token, 0);
    }

    public void postCallbackDelayed(int callbackType, Runnable action, Object token,
            long delayMillis) {
        checkCallbackType(callbackType);
        if (action == null) throw new IllegalArgumentException("action must not be null");
        addCallback(callbackType, action, token, delayMillis);
    }

    public void removeCallbacks(int callbackType, Runnable action, Object token) {
        checkCallbackType(callbackType);
        removeMatching(callbackType, action, token);
    }

    private void addCallback(int type, Object action, Object token, long delayMillis) {
        long dueTime = SystemClock.uptimeMillis() + Math.max(0, delayMillis);
        synchronized (mCallbacks) {
            mCallbacks.add(new CallbackRecord(type, action, token, dueTime));
            scheduleWakeLocked();
        }
    }

    private void removeMatching(int type, Object action, Object token) {
        if (action == null) return;
        synchronized (mCallbacks) {
            for (int i = mCallbacks.size() - 1; i >= 0; --i) {
                CallbackRecord record = mCallbacks.get(i);
                if (record.type == type && record.action == action
                        && (token == null || record.token == token)) {
                    mCallbacks.remove(i);
                }
            }
            // AOSP keeps one scheduled display event for the earliest callback.
            // Recompute it when cancellation may have removed that callback.
            mHandler.removeCallbacks(mWakeForFrame);
            mWakeTime = Long.MAX_VALUE;
            scheduleWakeLocked();
        }
    }

    /**
     * Host DisplayEventReceiver wake. This message does not execute frame work;
     * it only makes the native poll loop re-check hasDueCallbacks$artzig().
     */
    private void onWakeForFrame() {
        synchronized (mCallbacks) {
            mWakeTime = Long.MAX_VALUE;
            // Do not repost a callback which is already due: the host is awake
            // and will request compositor vsync. Keep only a future deadline.
            scheduleFutureWakeLocked(SystemClock.uptimeMillis());
        }
    }

    private void scheduleWakeLocked() {
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < mCallbacks.size(); ++i) {
            earliest = Math.min(earliest, mCallbacks.get(i).dueTime);
        }
        if (earliest < mWakeTime) {
            mHandler.removeCallbacks(mWakeForFrame);
            mWakeTime = earliest;
            mHandler.postAtTime(mWakeForFrame, earliest);
        }
    }

    private void scheduleFutureWakeLocked(long now) {
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < mCallbacks.size(); ++i) {
            long dueTime = mCallbacks.get(i).dueTime;
            if (dueTime > now) earliest = Math.min(earliest, dueTime);
        }
        if (earliest != Long.MAX_VALUE) {
            mWakeTime = earliest;
            mHandler.postAtTime(mWakeForFrame, earliest);
        }
    }

    private static void checkCallbackType(int callbackType) {
        if (callbackType < CALLBACK_INPUT || callbackType > CALLBACK_LAST) {
            throw new IllegalArgumentException("callbackType is invalid");
        }
    }

    /** Called once for the Wayland frame immediately before view traversal. */
    public void doFrame$artzig(long frameTimeNanos) {
        mLastFrameTimeNanos = frameTimeNanos;
        for (int type = CALLBACK_INPUT; type <= CALLBACK_LAST; ++type) {
            long phaseStart = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
            ArrayList<CallbackRecord> due = takeDue(type, SystemClock.uptimeMillis());
            for (int i = 0; i < due.size(); ++i) {
                long callbackStart = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
                due.get(i).run(frameTimeNanos);
                if (TRACE_SLOW_FRAMES) {
                    long elapsed = SystemClock.uptimeMillis() - callbackStart;
                    if (elapsed > 8) {
                        Log.d("ChoreographerFrame", "slow callback type=" + type
                                + " duration=" + elapsed + "ms action="
                                + due.get(i).action.getClass().getName());
                    }
                }
            }
            if (TRACE_SLOW_FRAMES) {
                long elapsed = SystemClock.uptimeMillis() - phaseStart;
                if (elapsed > 8) {
                    Log.d("ChoreographerFrame", "slow phase type=" + type
                            + " callbacks=" + due.size() + " duration=" + elapsed + "ms");
                }
            }
        }
        synchronized (mCallbacks) {
            mHandler.removeCallbacks(mWakeForFrame);
            mWakeTime = Long.MAX_VALUE;
            scheduleFutureWakeLocked(SystemClock.uptimeMillis());
        }
    }

    /** Host DisplayEventReceiver seam: true only when a callback is due now. */
    public boolean hasDueCallbacks$artzig() {
        long now = SystemClock.uptimeMillis();
        synchronized (mCallbacks) {
            for (int i = 0; i < mCallbacks.size(); ++i) {
                if (mCallbacks.get(i).dueTime <= now) return true;
            }
        }
        return false;
    }

    private ArrayList<CallbackRecord> takeDue(int type, long now) {
        ArrayList<CallbackRecord> due = new ArrayList<>();
        synchronized (mCallbacks) {
            for (int i = mCallbacks.size() - 1; i >= 0; --i) {
                CallbackRecord record = mCallbacks.get(i);
                if (record.type == type && record.dueTime <= now) {
                    mCallbacks.remove(i);
                    due.add(0, record);
                }
            }
        }
        return due;
    }
}
