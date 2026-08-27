/* artzig shim: android.os.MessageQueue — wait/notify queue, no epoll. */
package android.os;
public final class MessageQueue {
    Message mMessages;
    private boolean mQuitting;
    private int mNextBarrierToken;
    private final java.util.ArrayList<IdleHandler> mIdleHandlers = new java.util.ArrayList<>();
    public interface IdleHandler { boolean queueIdle(); }
    public void addIdleHandler(IdleHandler handler) {
        synchronized (this) { mIdleHandlers.add(handler); }
    }
    public void removeIdleHandler(IdleHandler handler) {
        synchronized (this) { mIdleHandlers.remove(handler); }
    }
    public boolean isIdle() {
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            return mMessages == null || now < mMessages.when;
        }
    }
    /** artzig: non-blocking poll of a due message (host frame loop drains the queue). */
    public Message pollDueMessage$artzig() {
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            Message msg = mMessages;
            Message prev = null;
            if (msg != null && msg.target == null) {
                // AOSP MessageQueue.next(): a synchronization barrier stalls every
                // synchronous message behind it while allowing asynchronous work through.
                do {
                    prev = msg;
                    msg = msg.next;
                } while (msg != null && !msg.isAsynchronous());
            }
            if (msg != null && now >= msg.when) {
                if (prev == null) mMessages = msg.next; else prev.next = msg.next;
                msg.next = null;
                return msg;
            }
            return null;
        }
    }

    /**
     * AOSP MessageQueue.postSyncBarrier(), adapted to the Java-only hosted queue because
     * nativePollOnce/nativeWake are unavailable. The returned token must be removed exactly once.
     */
    public int postSyncBarrier() {
        return postSyncBarrier(SystemClock.uptimeMillis());
    }

    private int postSyncBarrier(long when) {
        synchronized (this) {
            int token = mNextBarrierToken++;
            Message barrier = Message.obtain();
            barrier.when = when;
            barrier.arg1 = token;

            Message previous = null;
            Message current = mMessages;
            if (when != 0) {
                while (current != null && current.when <= when) {
                    previous = current;
                    current = current.next;
                }
            }
            if (previous == null) {
                barrier.next = mMessages;
                mMessages = barrier;
            } else {
                barrier.next = current;
                previous.next = barrier;
            }
            return token;
        }
    }

    /** AOSP MessageQueue.removeSyncBarrier(), without the native wake operation. */
    public void removeSyncBarrier(int token) {
        synchronized (this) {
            Message previous = null;
            Message current = mMessages;
            while (current != null && (current.target != null || current.arg1 != token)) {
                previous = current;
                current = current.next;
            }
            if (current == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + "barrier token has not been posted or has already been removed.");
            }
            if (previous == null) mMessages = current.next; else previous.next = current.next;
            current.next = null;
            current.recycleUnchecked();
            notifyAll();
        }
    }

    Message next() {
        boolean ranIdle = false;
        for (;;) {
            synchronized (this) {
                long now = SystemClock.uptimeMillis();
                Message msg = mMessages;
                if (msg != null && msg.target == null) { // sync barrier: skip to async
                    Message prev;
                    do { prev = msg; msg = msg.next; } while (msg != null && !msg.isAsynchronous());
                    if (msg != null) {
                        if (now >= msg.when) { prev.next = msg.next; msg.next = null; return msg; }
                    }
                } else if (msg != null && now >= msg.when) {
                    mMessages = msg.next; msg.next = null; return msg;
                }
                if (mQuitting) return null;
                long timeout = msg != null ? msg.when - now : 0;
                if (!ranIdle && (mMessages == null || now < mMessages.when)) {
                    // run idle handlers once per idle period
                    java.util.ArrayList<IdleHandler> idle = new java.util.ArrayList<>(mIdleHandlers);
                    if (!idle.isEmpty()) {
                        ranIdle = true;
                        for (IdleHandler h : idle) {
                            boolean keep;
                            try { keep = h.queueIdle(); } catch (Throwable t) { keep = false; }
                            if (!keep) mIdleHandlers.remove(h);
                        }
                        continue;
                    }
                }
                try {
                    if (timeout <= 0) wait(); else wait(timeout);
                } catch (InterruptedException e) {}
                ranIdle = false;
            }
        }
    }
    boolean enqueueMessage(Message msg, long when) {
        synchronized (this) {
            if (mQuitting) return false;
            msg.when = when;
            Message p = mMessages;
            if (p == null || when == 0 || when < p.when) {
                msg.next = p; mMessages = msg;
            } else {
                Message prev;
                for (;;) {
                    prev = p; p = p.next;
                    if (p == null || when < p.when) break;
                }
                msg.next = p; prev.next = msg;
            }
            notifyAll();
        }
        return true;
    }
    void removeMessages(Handler h, int what, Object object) {
        removeIf(m -> m.target == h && m.what == what && (object == null || m.obj == object));
    }
    void removeCallbacks(Handler h, Runnable r, Object object) {
        removeIf(m -> m.target == h && m.callback == r && (object == null || m.obj == object));
    }
    void removeCallbacksAndMessages(Handler h, Object object) {
        removeIf(m -> m.target == h && (object == null || m.obj == object));
    }
    boolean hasMessages(Handler h, int what, Object object) {
        synchronized (this) {
            for (Message m = mMessages; m != null; m = m.next)
                if (m.target == h && m.what == what && (object == null || m.obj == object)) return true;
            return false;
        }
    }
    boolean hasCallbacks(Handler h, Runnable r, Object object) {
        synchronized (this) {
            for (Message m = mMessages; m != null; m = m.next)
                if (m.target == h && m.callback == r && (object == null || m.obj == object)) return true;
            return false;
        }
    }
    private interface Pred { boolean test(Message m); }
    private void removeIf(Pred p) {
        synchronized (this) {
            Message m = mMessages, prev = null;
            while (m != null) {
                Message next = m.next;
                if (p.test(m)) {
                    if (prev == null) mMessages = next; else prev.next = next;
                    m.recycleUnchecked();
                } else prev = m;
                m = next;
            }
        }
    }
    void quit(boolean safe) {
        synchronized (this) {
            mQuitting = true;
            if (!safe) { while (mMessages != null) { Message n = mMessages.next; mMessages.recycleUnchecked(); mMessages = n; } }
            notifyAll();
        }
    }
}
