/* artzig shim: android.os.Handler. */
package android.os;
public class Handler {
    public interface Callback { boolean handleMessage(Message msg); }
    final Looper mLooper;
    final MessageQueue mQueue;
    final Callback mCallback;
    final boolean mAsynchronous;
    public Handler() { this(Looper.myLooper(), null, false); }
    public Handler(Callback callback) { this(Looper.myLooper(), callback, false); }
    public Handler(boolean async) { this(Looper.myLooper(), null, async); }
    public Handler(Looper looper) { this(looper, null, false); }
    public Handler(Looper looper, Callback callback) { this(looper, callback, false); }
    public Handler(Looper looper, Callback callback, boolean async, boolean shared) {
        this(looper, callback, async);
    }
    public Handler(Looper looper, Callback callback, boolean async) {
        if (looper == null) throw new RuntimeException("Can't create handler: no Looper");
        mLooper = looper; mQueue = looper.mQueue; mCallback = callback; mAsynchronous = async;
    }
    public static Handler createAsync(Looper looper) { return new Handler(looper, null, true); }
    public static Handler createAsync(Looper looper, Callback cb) { return new Handler(looper, cb, true); }
    public static Handler getMain() { return new Handler(Looper.getMainLooper()); }
    public Looper getLooper() { return mLooper; }
    public void handleMessage(Message msg) {}
    public void dispatchMessage(Message msg) {
        if (msg.callback != null) { msg.callback.run(); return; }
        if (mCallback != null && mCallback.handleMessage(msg)) return;
        handleMessage(msg);
    }
    public boolean post(Runnable r) { return sendMessageDelayed(Message.obtain(this, r), 0); }
    public boolean postDelayed(Runnable r, long delayMillis) { return sendMessageDelayed(Message.obtain(this, r), delayMillis); }
    public boolean postDelayed(Runnable r, Object token, long delayMillis) {
        Message m = Message.obtain(this, r); m.obj = token; return sendMessageDelayed(m, delayMillis);
    }
    public boolean postAtTime(Runnable r, long uptimeMillis) { return sendMessageAtTime(Message.obtain(this, r), uptimeMillis); }
    public boolean postAtTime(Runnable r, Object token, long uptimeMillis) {
        Message m = Message.obtain(this, r); m.obj = token; return sendMessageAtTime(m, uptimeMillis);
    }
    public boolean postAtFrontOfQueue(Runnable r) { return sendMessageAtFrontOfQueue(Message.obtain(this, r)); }
    public void removeCallbacks(Runnable r) { mQueue.removeCallbacks(this, r, null); }
    public void removeCallbacks(Runnable r, Object token) { mQueue.removeCallbacks(this, r, token); }
    public boolean sendMessage(Message msg) { return sendMessageDelayed(msg, 0); }
    public boolean sendEmptyMessage(int what) { return sendEmptyMessageDelayed(what, 0); }
    public boolean sendEmptyMessageDelayed(int what, long delayMillis) { return sendMessageDelayed(Message.obtain(this, what), delayMillis); }
    public boolean sendEmptyMessageAtTime(int what, long uptimeMillis) { return sendMessageAtTime(Message.obtain(this, what), uptimeMillis); }
    public boolean sendMessageDelayed(Message msg, long delayMillis) {
        if (delayMillis < 0) delayMillis = 0;
        return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
    }
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) msg.setAsynchronous(true);
        return mQueue.enqueueMessage(msg, uptimeMillis);
    }
    public boolean sendMessageAtFrontOfQueue(Message msg) {
        msg.target = this;
        if (mAsynchronous) msg.setAsynchronous(true);
        return mQueue.enqueueMessage(msg, 0);
    }
    public void removeMessages(int what) { mQueue.removeMessages(this, what, null); }
    public void removeMessages(int what, Object object) { mQueue.removeMessages(this, what, object); }
    /** AOSP matches obj by equals(); the hosted queue's identity match is close enough here. */
    public void removeEqualMessages(int what, Object object) { mQueue.removeMessages(this, what, object); }
    public void removeCallbacksAndMessages(Object token) { mQueue.removeCallbacksAndMessages(this, token); }
    public boolean hasMessages(int what) { return mQueue.hasMessages(this, what, null); }
    public boolean hasMessages(int what, Object object) { return mQueue.hasMessages(this, what, object); }
    public boolean hasCallbacks(Runnable r) { return mQueue.hasCallbacks(this, r, null); }
    public final boolean runWithScissors(Runnable r, long timeout) {
        if (Looper.myLooper() == mLooper) { r.run(); return true; }
        final Object lock = new Object();
        final boolean[] done = { false };
        synchronized (lock) {
            post(() -> { r.run(); synchronized (lock) { done[0] = true; lock.notifyAll(); } });
            long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
            while (!done[0]) {
                try {
                    if (timeout > 0) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) return false;
                        lock.wait(left);
                    } else lock.wait();
                } catch (InterruptedException e) {}
            }
        }
        return true;
    }
    public String getMessageName(Message m) { return String.valueOf(m.what); }
    public void executeOrSendMessage(Message msg) {
        if (mLooper == Looper.myLooper()) dispatchMessage(msg);
        else sendMessage(msg);
    }
    public Message obtainMessage() { return Message.obtain(this); }
    public Message obtainMessage(int what) { return Message.obtain(this, what); }
    public Message obtainMessage(int what, Object obj) { return Message.obtain(this, what, obj); }
    public Message obtainMessage(int what, int arg1, int arg2) { return Message.obtain(this, what, arg1, arg2); }
    public Message obtainMessage(int what, int arg1, int arg2, Object obj) { return Message.obtain(this, what, arg1, arg2, obj); }
    @Override public String toString() { return "Handler (" + getClass().getName() + ")"; }
}
