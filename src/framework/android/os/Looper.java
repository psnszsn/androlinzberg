/* artzig shim: android.os.Looper — pure-Java message loop (ATL pattern). */
package android.os;
public final class Looper {
    private static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<>();
    private static Looper sMainLooper;
    final MessageQueue mQueue = new MessageQueue();
    final Thread mThread = Thread.currentThread();
    private Looper() {}
    public static void prepare() {
        if (sThreadLocal.get() != null) throw new RuntimeException("Only one Looper per thread");
        sThreadLocal.set(new Looper());
    }
    public static void prepareMainLooper() {
        prepare();
        synchronized (Looper.class) { sMainLooper = myLooper(); }
    }
    public static Looper getMainLooper() { synchronized (Looper.class) { return sMainLooper; } }
    public static Looper myLooper() { return sThreadLocal.get(); }
    public static MessageQueue myQueue() { return myLooper().mQueue; }
    public MessageQueue getQueue() { return mQueue; }
    public Thread getThread() { return mThread; }
    public boolean isCurrentThread() { return Thread.currentThread() == mThread; }
    public static void loop() {
        Looper me = myLooper();
        if (me == null) throw new RuntimeException("No Looper; Looper.prepare() wasn't called");
        for (;;) {
            Message msg = me.mQueue.next();
            if (msg == null) return; // quit
            try {
                msg.target.dispatchMessage(msg);
            } finally {
                msg.recycleUnchecked();
            }
        }
    }
    public void quit() { mQueue.quit(false); }
    public void setSlowLogThresholdMs(long slowDispatchThresholdMs, long slowDeliveryThresholdMs) {}
    public void setTraceTag(long traceTag) {}
    public void quitSafely() { mQueue.quit(true); }
    public void dump(android.util.Printer pw, String prefix) {}
    @Override public String toString() { return "Looper (" + mThread.getName() + ")"; }
}
