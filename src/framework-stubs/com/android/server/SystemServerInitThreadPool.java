/* artzig stub: AOSP runs early boot work on a pool; the hosted system process starts its few
 * services synchronously, so only shutdown() is needed. */
package com.android.server;
public final class SystemServerInitThreadPool {
    private SystemServerInitThreadPool() {}
    public static void shutdown() {}
}
