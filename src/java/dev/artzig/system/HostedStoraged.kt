package dev.artzig.system

import android.os.IStoraged

/**
 * The storaged daemon of a hosted session: the `IStoraged` Binder that stock
 * `StorageManagerService` notifies of user starts and stops. Pinned
 * system/core/storaged keeps per-user I/O statistics and performance history
 * for the device's storage health reports; the host keeps none, so the
 * notifications are accepted and the history is empty.
 */
class HostedStoraged : IStoraged.Stub() {
    override fun onUserStarted(userId: Int) {}
    override fun onUserStopped(userId: Int) {}
    override fun getRecentPerf(): Int = 0
}
