package dev.artzig.system

import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The session's broadcast router for dynamically registered receivers.
 *
 * Source note: follows `com.android.server.am.BroadcastQueueImpl`
 * (`deliverToRegisteredReceiverLocked`, `processNextBroadcastLocked` reduced
 * to registered receivers, `finishReceiverLocked`, the 10s
 * `BROADCAST_TIMEOUT`) and `ActivityManagerService.broadcastIntentLocked`
 * (sticky retention, `registerReceiverWithFeature`'s per-binder
 * `ReceiverList` of filters); the AOSP queue is inseparable from
 * AMS/`ProcessList` (spike result). Ordered broadcasts deliver one receiver
 * at a time by descending filter priority and chain the result state;
 * unordered broadcasts fan out immediately (`performReceive` is oneway, and
 * the runtime serializes deliveries per receiver binder). Manifest-declared
 * receivers arrive with a later phase.
 */
internal class HostedBroadcastQueue(private val ams: HostedActivityManagerService) {
    /** AOSP ReceiverList: one entry per IIntentReceiver binder, many filters. */
    private class Registration(
        val receiver: IIntentReceiver,
        val packageName: String?,
        val uid: Int,
        /** Permission a sender must hold to reach this receiver (AOSP requiredPermission on register). */
        val broadcastPermission: String?,
        /** RECEIVER_NOT_EXPORTED: only same-uid (or system) senders may reach it. */
        val notExported: Boolean,
    ) : IBinder.DeathRecipient {
        val binder: IBinder = receiver.asBinder()
        val filters = ArrayList<IntentFilter>()
        var queue: HostedBroadcastQueue? = null
        override fun binderDied() {
            queue?.unregisterReceiver(receiver)
        }
    }

    /** One in-flight ordered broadcast (AOSP BroadcastRecord, registered receivers only). */
    private class BroadcastRecord(
        val intent: Intent,
        val receivers: List<Registration>,
        val resultTo: IIntentReceiver?,
        var resultCode: Int,
        var resultData: String?,
        var resultExtras: Bundle?,
    ) {
        var index = -1
        var timeout: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val registrations = LinkedHashMap<IBinder, Registration>()
    /** action -> the most recent sticky Intent with that action. */
    private val stickies = LinkedHashMap<String, Intent>()
    private val ordered = ArrayDeque<BroadcastRecord>()
    private var current: BroadcastRecord? = null
    private val timer = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "broadcast-timeout").apply { isDaemon = true }
    }

    /** A null receiver is the sticky-query form: return the first match, register nothing. */
    fun registerReceiver(
        receiver: IIntentReceiver?,
        filter: IntentFilter,
        callingPackage: String?,
        callerUid: Int,
        broadcastPermission: String?,
        notExported: Boolean,
    ): Intent? {
        if (receiver == null) {
            synchronized(lock) {
                return stickies.values.firstOrNull { stickyMatches(filter, it) }
            }
        }
        val binder = receiver.asBinder()
        val matching = ArrayList<Intent>()
        synchronized(lock) {
            val registration = registrations[binder] ?: run {
                val fresh = Registration(receiver, callingPackage, callerUid, broadcastPermission, notExported)
                fresh.queue = this
                try {
                    binder.linkToDeath(fresh, 0)
                } catch (e: RemoteException) {
                    return null
                }
                registrations[binder] = fresh
                fresh
            }
            registration.filters.add(filter)
            for (sticky in stickies.values) {
                if (stickyMatches(filter, sticky)) matching.add(sticky)
            }
        }
        // AOSP delivers every matching sticky to the new receiver and returns the first.
        for (sticky in matching) {
            try {
                receiver.performReceive(sticky, 0, null, null, false, true, 0)
            } catch (e: RemoteException) {
            }
        }
        return matching.firstOrNull()
    }

    fun unregisterReceiver(receiver: IIntentReceiver) {
        val binder = receiver.asBinder()
        var advance = false
        synchronized(lock) {
            val registration = registrations.remove(binder) ?: return
            binder.unlinkToDeath(registration, 0)
            // If it was the current ordered target, move on (AOSP skips dead receivers).
            val record = current
            if (record != null && record.index in record.receivers.indices &&
                record.receivers[record.index].binder == binder
            ) {
                advance = true
            }
        }
        if (advance) finishCurrent(binder, null, null, null, false)
    }

    fun broadcastIntent(
        intent: Intent,
        resultTo: IIntentReceiver?,
        resultCode: Int,
        requiredPermission: String?,
        isOrdered: Boolean,
        sticky: Boolean,
        callingPackage: String?,
        senderUid: Int,
    ): Int {
        val action = intent.action ?: return -1
        val targets: List<Registration>
        synchronized(lock) {
            if (sticky) stickies[action] = Intent(intent)
            targets = registrations.values.mapNotNull { registration ->
                val priority = matchPriorityLocked(registration, intent) ?: return@mapNotNull null
                // Receiver-side restriction: only receivers holding requiredPermission.
                if (requiredPermission != null &&
                    ams.checkUidPermission(requiredPermission, registration.uid) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@mapNotNull null
                }
                // Sender-side restrictions the receiver declared at registration.
                if (!senderMayReach(registration, senderUid)) return@mapNotNull null
                registration to priority
            }.sortedByDescending { it.second }.map { it.first }
        }
        if (!isOrdered) {
            for (registration in targets) {
                try {
                    registration.receiver.performReceive(intent, 0, null, null, false, false, 0)
                } catch (e: RemoteException) {
                }
            }
            resultTo?.let { deliverResult(it, intent, resultCode, null, null) }
            return 0
        }
        synchronized(lock) {
            ordered.addLast(BroadcastRecord(intent, targets, resultTo, resultCode, null, null))
            if (current == null) scheduleNextLocked()
        }
        return 0
    }

    fun removeSticky(intent: Intent) {
        val action = intent.action ?: return
        synchronized(lock) { stickies.remove(action) }
    }

    fun finishReceiver(who: IIntentReceiver, resultCode: Int, resultData: String?, resultExtras: Bundle?, abort: Boolean) {
        finishCurrent(who.asBinder(), resultCode, resultData, resultExtras, abort)
    }

    // ------------------------------------------------------------------ ordered

    private fun finishCurrent(binder: IBinder, resultCode: Int?, resultData: String?, resultExtras: Bundle?, abort: Boolean) {
        synchronized(lock) {
            val record = current ?: return
            if (record.index !in record.receivers.indices ||
                record.receivers[record.index].binder != binder
            ) {
                return
            }
            record.timeout?.cancel(false)
            record.timeout = null
            if (resultCode != null) {
                record.resultCode = resultCode
                record.resultData = resultData
                record.resultExtras = resultExtras
            }
            if (abort) record.index = record.receivers.size
            scheduleNextLocked()
        }
    }

    private fun scheduleNextLocked() {
        while (true) {
            var record = current
            if (record == null) {
                record = ordered.removeFirstOrNull() ?: return
                current = record
            }
            record.index++
            if (record.index >= record.receivers.size) {
                current = null
                val resultTo = record.resultTo
                if (resultTo != null) {
                    deliverResult(resultTo, record.intent, record.resultCode, record.resultData, record.resultExtras)
                }
                continue
            }
            val target = record.receivers[record.index]
            if (registrations[target.binder] == null) continue // unregistered meanwhile
            val delivered = try {
                target.receiver.performReceive(
                    record.intent, record.resultCode, record.resultData, record.resultExtras, true, false, 0,
                )
                true
            } catch (e: RemoteException) {
                false
            }
            if (!delivered) continue
            val index = record.index
            record.timeout = timer.schedule({
                timeoutReceiver(record, index, target.packageName)
            }, BROADCAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return
        }
    }

    /** A timeout only advances the exact (record, index) it was armed for —
     * a legitimate finish that raced the firing timer must not be repeated. */
    private fun timeoutReceiver(record: BroadcastRecord, index: Int, packageName: String?) {
        synchronized(lock) {
            if (current !== record || record.index != index) return
            Log.w(TAG, "ordered broadcast timeout: " + record.intent.action + " at " + packageName)
            record.timeout = null
            scheduleNextLocked()
        }
    }

    private fun deliverResult(resultTo: IIntentReceiver, intent: Intent, code: Int, data: String?, extras: Bundle?) {
        try {
            resultTo.performReceive(intent, code, data, extras, false, false, 0)
        } catch (e: RemoteException) {
        }
    }

    /** Highest priority among the registration's matching filters, or null when none match. */
    private fun matchPriorityLocked(registration: Registration, intent: Intent): Int? {
        val targetPackage = intent.`package`
        if (targetPackage != null && targetPackage != registration.packageName) return null
        var best: Int? = null
        for (filter in registration.filters) {
            if (!filterMatches(filter, intent)) continue
            if (best == null || filter.priority > best) best = filter.priority
        }
        return best
    }

    private fun stickyMatches(filter: IntentFilter, sticky: Intent): Boolean =
        filterMatches(filter, sticky)

    /** A receiver's registration-time restrictions on who may broadcast to it. */
    private fun senderMayReach(registration: Registration, senderUid: Int): Boolean {
        if (senderUid == android.os.Process.SYSTEM_UID) return true
        if (registration.notExported && senderUid != registration.uid) return false
        val permission = registration.broadcastPermission ?: return true
        return ams.checkUidPermission(permission, senderUid) == PackageManager.PERMISSION_GRANTED
    }

    private fun filterMatches(filter: IntentFilter, intent: Intent): Boolean {
        if (!filter.matchAction(intent.action)) return false
        if (filter.matchCategories(intent.categories) != null) return false
        // AOSP IntentFilter.matchData reduced to schemes: a filter that names
        // schemes requires one of them; a filter with no data spec matches
        // only intents without data.
        val scheme = intent.data?.scheme
        return if (filter.countDataSchemes() > 0) filter.hasDataScheme(scheme) else scheme == null
    }

    private companion object {
        const val TAG = "HostedBroadcastQueue"
        const val BROADCAST_TIMEOUT_MS = 10_000L
    }
}
