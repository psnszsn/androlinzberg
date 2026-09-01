/* artzig stub: the shared-memory nonce backend of PropertyInvalidatedCache is
 * flagged off (com.android.internal.os.Flags.applicationSharedMemoryEnabled
 * is false); nonces ride the session's SystemProperties area instead. */
package com.android.internal.os;
public class ApplicationSharedMemory {
    public static ApplicationSharedMemory getInstance() {
        throw new IllegalStateException("application shared memory is not hosted");
    }
    public long getSystemNonceBlock() { return 0; }
    public boolean isMutable() { return false; }
    /** SystemClock.currentNetworkTimeMillis (flag-gated path): no network time is published. */
    public long getLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis() {
        throw new java.time.DateTimeException("No network time available");
    }
    private ApplicationSharedMemory() {}
}
