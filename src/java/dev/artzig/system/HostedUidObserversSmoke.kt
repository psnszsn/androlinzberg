package dev.artzig.system

import android.app.ActivityManager as AM
import android.app.IUidObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import com.android.server.am.HostedUidObservers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HostedUidObserversSmoke {
    fun run() {
        val thread = HandlerThread("uid-observers-fixture").apply { start() }
        val handler = Handler(thread.looper)
        fun drain() {
            val done = CountDownLatch(1)
            check(handler.post { check(handler.post { done.countDown() }) })
            check(done.await(5, TimeUnit.SECONDS))
        }
        fun batch(action: () -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            check(handler.post { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            check(entered.await(5, TimeUnit.SECONDS))
            try { action() } finally { release.countDown() }
            drain()
        }
        val watchers = HostedUidObservers(handler)
        fun register(observer: Observer, which: Int = 127, cutpoint: Int = -1,
            uids: IntArray? = null, caller: Int = android.os.Process.SYSTEM_UID) =
            watchers.register(observer, which, cutpoint, "android", caller, uids)
        try {
            val all = Observer()
            register(all)
            watchers.update(10001, AM.PROCESS_STATE_TOP, 1, 0)
            drain()
            check(all.events == listOf("active:10001", "cached:10001:false", "state:10001:2:1", "adj:10001:0"))
            all.events.clear()
            batch {
                watchers.update(10001, AM.PROCESS_STATE_CACHED_ACTIVITY, 0, 700)
                watchers.update(10001, AM.PROCESS_STATE_CACHED_EMPTY, 0, 800)
            }
            check(all.events == listOf("cached:10001:true", "state:10001:19:0", "adj:10001:800"))
            all.events.clear()
            batch {
                watchers.update(10001, AM.PROCESS_STATE_TOP, 1, 0)
                watchers.update(10001, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            }
            check(all.events == listOf("idle:10001:false", "cached:10001:false", "gone:10001:false"))
            check(!watchers.isActive(10001))
            watchers.unregister(all)

            val cut = Observer()
            register(cut, AM.UID_OBSERVER_PROCSTATE or AM.UID_OBSERVER_CAPABILITY,
                AM.PROCESS_STATE_IMPORTANT_FOREGROUND)
            watchers.update(10002, AM.PROCESS_STATE_TOP, 1, 0)
            drain()
            watchers.update(10002, AM.PROCESS_STATE_BOUND_TOP, 1, 0)
            drain()
            check(cut.events == listOf("state:10002:2:1"))
            watchers.update(10002, AM.PROCESS_STATE_BOUND_TOP, 2, 0)
            drain()
            watchers.update(10002, AM.PROCESS_STATE_CACHED_EMPTY, 0, 800)
            drain()
            watchers.update(10002, AM.PROCESS_STATE_CACHED_ACTIVITY, 0, 700)
            drain()
            check(cut.events == listOf("state:10002:2:1", "state:10002:3:2", "state:10002:19:0"))
            watchers.update(10002, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            watchers.update(10002, AM.PROCESS_STATE_CACHED_ACTIVITY, 0, 700)
            drain()
            check(cut.events.last() == "state:10002:16:0") // GONE clears the cutpoint history.
            watchers.unregister(cut)

            val filtered = Observer()
            val selection = intArrayOf(10003)
            val token = register(filtered, AM.UID_OBSERVER_GONE, uids = selection)
            selection[0] = 10004 // Registration clones the caller's filter.
            watchers.update(10003, AM.PROCESS_STATE_TOP, 1, 0)
            watchers.update(10004, AM.PROCESS_STATE_TOP, 1, 0)
            drain()
            watchers.update(10003, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(filtered.events == listOf("gone:10003:false"))
            // Filter edits and event delivery use the same Handler order.
            watchers.add(token, 10004)
            watchers.add(token, 10004)
            watchers.update(10004, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(filtered.events.last() == "gone:10004:false" && filtered.events.size == 2)
            watchers.remove(token, 10003)
            watchers.remove(token, 10003)
            watchers.update(10003, AM.PROCESS_STATE_TOP, 1, 0)
            watchers.update(10003, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(filtered.events.size == 2)
            watchers.unregister(filtered)

            val restricted = Observer()
            register(restricted, AM.UID_OBSERVER_GONE,
                caller = android.os.UserHandle.getUid(17, 10123))
            val otherUser = android.os.UserHandle.getUid(18, 10123)
            watchers.update(otherUser, AM.PROCESS_STATE_TOP, 1, 0)
            watchers.update(otherUser, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(restricted.events.isEmpty())
            watchers.unregister(restricted)

            val dead = Observer()
            register(dead)
            checkNotNull(dead.death).binderDied()
            val failedLink = Observer(failLink = true)
            register(failedLink)
            val failingCallback = Observer(failCallback = true)
            register(failingCallback)
            val alive = Observer()
            register(alive, AM.UID_OBSERVER_GONE)
            watchers.update(10005, AM.PROCESS_STATE_TOP, 1, 0)
            watchers.update(10005, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(dead.events.isEmpty() && failedLink.events.isEmpty())
            check(alive.events == listOf("gone:10005:false"))
            watchers.unregister(alive)
            watchers.unregister(failingCallback)

            val settling = HostedUidObservers(handler, { 0L })
            val idle = Observer()
            settling.register(idle, AM.UID_OBSERVER_ACTIVE or AM.UID_OBSERVER_IDLE or AM.UID_OBSERVER_GONE,
                -1, "android", android.os.Process.SYSTEM_UID, null)
            settling.update(10006, AM.PROCESS_STATE_TOP, 1, 0)
            drain()
            check(settling.isActive(10006))
            settling.update(10006, AM.PROCESS_STATE_CACHED_EMPTY, 0, 800)
            drain()
            check(!settling.isActive(10006))
            settling.update(10006, AM.PROCESS_STATE_TOP, 1, 0)
            drain()
            check(settling.isActive(10006))
            batch {
                settling.update(10006, AM.PROCESS_STATE_CACHED_EMPTY, 0, 800)
                settling.update(10006, AM.PROCESS_STATE_TOP, 1, 0)
            }
            check(idle.events == listOf("active:10006", "idle:10006:false", "active:10006"))
            settling.update(10006, AM.PROCESS_STATE_NONEXISTENT, 0, -10000)
            drain()
            check(idle.events.takeLast(2) == listOf("idle:10006:false", "gone:10006:false"))
            settling.unregister(idle)

            val denied = HostedActivityManagerService()
            check(runCatching { denied.registerUidObserver(null, 0, 0, null) }.exceptionOrNull() is SecurityException)
            check(runCatching { denied.addUidToObserver(null, null, 10001) }.exceptionOrNull() is SecurityException)
            check(runCatching { denied.removeUidFromObserver(null, null, 10001) }.exceptionOrNull() is SecurityException)
            check(runCatching { denied.isUidActive(10001, null) }.exceptionOrNull() is SecurityException)
            denied.unregisterUidObserver(Observer())
        } finally {
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive)
        }
    }

    private class Observer(private val failLink: Boolean = false,
        private val failCallback: Boolean = false) : IUidObserver.Stub() {
        var death: IBinder.DeathRecipient? = null
        val events = mutableListOf<String>()
        private val sequences = HashMap<Int, Long>()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink) throw RemoteException("observer already dead")
            death = recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (death !== recipient) return false
            death = null
            return true
        }
        private fun event(value: String) {
            if (failCallback) throw RemoteException("observer died during callback")
            events.add(value)
        }
        override fun onUidGone(uid: Int, disabled: Boolean) = event("gone:$uid:$disabled")
        override fun onUidActive(uid: Int) = event("active:$uid")
        override fun onUidIdle(uid: Int, disabled: Boolean) = event("idle:$uid:$disabled")
        override fun onUidCachedChanged(uid: Int, cached: Boolean) = event("cached:$uid:$cached")
        override fun onUidProcAdjChanged(uid: Int, adj: Int) = event("adj:$uid:$adj")
        override fun onUidStateChanged(uid: Int, state: Int, seq: Long, capability: Int) {
            check(seq > (sequences[uid] ?: -1L))
            sequences[uid] = seq
            event("state:$uid:$state:$capability")
        }
    }
}
