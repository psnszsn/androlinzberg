/* artzig shim: android.os.Message. */
package android.os;
public final class Message {
    /** AOSP Message.UID_NONE: no work-source uid attributed to this message. */
    public static final int UID_NONE = -1;
    public int what, arg1, arg2;
    public Object obj;
    public Messenger replyTo;
    public int sendingUid = -1;
    public long when;
    int flags;
    Handler target;
    Runnable callback;
    Message next;
    private Bundle data;
    static Message sPool;
    static int sPoolSize;
    public static final Object sPoolSync = new Object();
    public Message() {}
    public static Message obtain() {
        synchronized (sPoolSync) {
            if (sPool != null) {
                Message m = sPool; sPool = m.next; m.next = null; m.flags = 0; sPoolSize--;
                return m;
            }
        }
        return new Message();
    }
    public static Message obtain(Handler h) { Message m = obtain(); m.target = h; return m; }
    public static Message obtain(Handler h, int what) { Message m = obtain(h); m.what = what; return m; }
    public static Message obtain(Handler h, int what, Object obj) { Message m = obtain(h, what); m.obj = obj; return m; }
    public static Message obtain(Handler h, int what, int arg1, int arg2) { Message m = obtain(h, what); m.arg1 = arg1; m.arg2 = arg2; return m; }
    public static Message obtain(Handler h, int what, int arg1, int arg2, Object obj) { Message m = obtain(h, what, arg1, arg2); m.obj = obj; return m; }
    public static Message obtain(Handler h, Runnable r) { Message m = obtain(h); m.callback = r; return m; }
    public static Message obtain(Message orig) {
        Message m = obtain();
        m.what = orig.what; m.arg1 = orig.arg1; m.arg2 = orig.arg2; m.obj = orig.obj;
        m.target = orig.target; m.callback = orig.callback; m.data = orig.data;
        return m;
    }
    public void recycle() { recycleUnchecked(); }
    void recycleUnchecked() {
        what = arg1 = arg2 = 0; obj = null; when = 0; target = null; callback = null; data = null; flags = 0;
        synchronized (sPoolSync) {
            if (sPoolSize < 50) { next = sPool; sPool = this; sPoolSize++; }
        }
    }
    public Handler getTarget() { return target; }
    public Runnable getCallback() { return callback; }
    public Message setCallback(Runnable r) { callback = r; return this; }
    public void setTarget(Handler t) { target = t; }
    public Bundle getData() { if (data == null) data = new Bundle(); return data; }
    public Bundle peekData() { return data; }
    public void setData(Bundle b) { data = b; }
    public void setAsynchronous(boolean async) { if (async) flags |= 1; else flags &= ~1; }
    public boolean isAsynchronous() { return (flags & 1) != 0; }
    public void sendToTarget() { target.sendMessage(this); }
    public long getWhen() { return when; }
    public void copyFrom(Message o) { what = o.what; arg1 = o.arg1; arg2 = o.arg2; obj = o.obj; data = o.data; }
}
