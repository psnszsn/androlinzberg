/* artzig stub: com.android.server.UiThread without ServiceThread (not
 * vendored) — one shared UI handler thread for the hosted system process. */
package com.android.server;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
public final class UiThread extends HandlerThread {
    private static UiThread sInstance;
    private static Handler sHandler;
    private UiThread() { super("android.ui"); }
    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new UiThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
        }
    }
    public static UiThread get() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }
    public static Handler getHandler() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
