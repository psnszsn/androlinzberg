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
    private ApplicationSharedMemory() {}
}
