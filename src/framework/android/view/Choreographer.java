/* artzig shim: android.view.Choreographer — callbacks are driven by the
 * Wayland compositor frame used to render the view tree. */
package android.view;

import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;

public final class Choreographer {
    public static final int CALLBACK_INPUT = 0, CALLBACK_ANIMATION = 1,
            CALLBACK_INSETS_ANIMATION = 2, CALLBACK_TRAVERSAL = 3, CALLBACK_COMMIT = 4;
    private static final int CALLBACK_LAST = CALLBACK_COMMIT;
    private static final long FRAME_INTERVAL_MS = 16;
    private static final Object FRAME_CALLBACK_TOKEN = new Object();
    private static final ThreadLocal<Choreographer> sThreadInstance = new ThreadLocal<>();

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
    private final ArrayList<CallbackRecord> mCallbacks = new ArrayList<>();
    private long mLastFrameTimeNanos;

    private Choreographer(Looper looper) { mLooper = looper; }

    public static Choreographer getInstance() {
        Choreographer c = sThreadInstance.get();
        if (c == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalStateException("The current thread must have a looper!");
            }
            c = new Choreographer(looper);
            sThreadInstance.set(c);
        }
        return c;
    }

    public static long getFrameDelay() { return FRAME_INTERVAL_MS; }
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
            ArrayList<CallbackRecord> due = takeDue(type, SystemClock.uptimeMillis());
            for (int i = 0; i < due.size(); ++i) {
                due.get(i).run(frameTimeNanos);
            }
        }
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
