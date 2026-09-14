package com.android.server.am

import android.app.ActivityManager
import android.app.IUidObserver
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock

/**
 * Feeds the original UidObserverController from the hosted process registry.
 * UidRecord/OomAdjuster own these transitions on Android; their process graph,
 * LMKD and cgroups are absent here. Preserve UID background settling, cached
 * transitions, capability/priority changes and last-process death, while the
 * original dispatcher owns observer filtering, coalescing and callback order.
 * Android's background-service restrictions remain a separate service policy.
 */
class HostedUidObservers(private val handler: Handler,
    private val settleMillis: () -> Long = { 60_000L },
    private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val controller = UidObserverController(handler)
    private class State {
        val change = UidObserverController.ChangeRecord()
        var processState = ActivityManager.PROCESS_STATE_NONEXISTENT
        var capability = 0
        var adjustment = -10000
        var idle = true
        var publishedIdle = false
        var ephemeral = false
        var backgroundSince: Long? = null
        var expire: Runnable? = null
    }
    private val states = HashMap<Int, State>()
    private var sequence = 0L

    fun register(observer: IUidObserver, which: Int, cutpoint: Int,
        callingPackage: String?, callingUid: Int, uids: IntArray?): IBinder =
        controller.register(observer, which, cutpoint, callingPackage, callingUid, uids)

    fun unregister(observer: IUidObserver) = controller.unregister(observer)
    fun add(token: IBinder?, uid: Int) = controller.addUidToObserver(token, uid)
    fun remove(token: IBinder?, uid: Int) = controller.removeUidFromObserver(token, uid)
    fun dump(writer: java.io.PrintWriter) = controller.dump(writer, null)

    @Synchronized fun isActive(uid: Int): Boolean = states[uid]?.idle == false

    /** The caller supplies a snapshot aggregated across all live processes of a UID. */
    @Synchronized fun update(uid: Int, processState: Int, capability: Int,
        adjustment: Int, ephemeral: Boolean = false) {
        require(uid >= 0)
        if (processState == ActivityManager.PROCESS_STATE_NONEXISTENT) {
            val old = states.remove(uid) ?: return
            old.expire?.let(handler::removeCallbacks)
            val change = UidObserverController.CHANGE_GONE or
                if (old.idle) 0 else UidObserverController.CHANGE_IDLE
            controller.enqueueUidChange(old.change, uid, change, processState,
                adjustment, ++sequence, capability, old.ephemeral)
            return
        }
        val state = states.getOrPut(uid) { State() }
        var change = 0
        if (ActivityManager.isProcStateBackground(processState)) {
            if (!ActivityManager.isProcStateBackground(state.processState) || state.backgroundSince == null) {
                state.backgroundSince = now()
                scheduleIdle(uid, state, settleMillis().coerceAtLeast(0L))
            }
            if (state.idle && !state.publishedIdle) change = change or UidObserverController.CHANGE_IDLE
        } else {
            if (state.idle) change = change or UidObserverController.CHANGE_ACTIVE
            state.idle = false
            state.backgroundSince = null
            state.expire?.let(handler::removeCallbacks)
            state.expire = null
        }
        val wasCached = state.processState > ActivityManager.PROCESS_STATE_RECEIVER
        val cached = processState > ActivityManager.PROCESS_STATE_RECEIVER
        if (wasCached != cached || state.processState == ActivityManager.PROCESS_STATE_NONEXISTENT)
            change = change or if (cached) UidObserverController.CHANGE_CACHED else UidObserverController.CHANGE_UNCACHED
        if (state.capability != capability) change = change or UidObserverController.CHANGE_CAPABILITY
        if (state.processState != processState) change = change or UidObserverController.CHANGE_PROCSTATE
        if (state.adjustment != adjustment) change = change or UidObserverController.CHANGE_PROCADJ
        state.processState = processState
        state.capability = capability
        state.adjustment = adjustment
        state.ephemeral = ephemeral
        state.publishedIdle = state.idle
        if (change != 0) enqueue(uid, state, change)
    }

    private fun enqueue(uid: Int, state: State, change: Int) {
        controller.enqueueUidChange(state.change, uid, change, state.processState,
            state.adjustment, ++sequence, state.capability, state.ephemeral)
    }

    private fun scheduleIdle(uid: Int, state: State, settle: Long) {
        state.expire?.let(handler::removeCallbacks)
        val since = checkNotNull(state.backgroundSince)
        val expiry = object : Runnable {
            override fun run() = synchronized(this@HostedUidObservers) {
                if (states[uid] !== state || state.backgroundSince != since) return@synchronized
                val remaining = settle - (now() - since)
                if (remaining > 0) {
                    check(handler.postDelayed(this, remaining))
                } else {
                    state.idle = true
                    state.publishedIdle = true
                    state.expire = null
                    enqueue(uid, state, UidObserverController.CHANGE_IDLE or UidObserverController.CHANGE_PROCSTATE)
                }
            }
        }
        state.expire = expiry
        check(handler.postDelayed(expiry, settle))
    }
}
