package dev.artzig.system

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * The session's PendingIntent records.
 *
 * Source note: `com.android.server.am.PendingIntentController` +
 * `PendingIntentRecord` — one record per (type, creator package/uid,
 * requestCode, filter-equal intents, resolved types, flags) key, the
 * FLAG_NO_CREATE / FLAG_CANCEL_CURRENT / FLAG_UPDATE_CURRENT / FLAG_ONE_SHOT
 * semantics, cancel listeners, and `send` dispatching by type. Broadcast and
 * service sends go through the activity manager as the creator; activity
 * sends need the activity task manager (the activity-core milestone) and
 * report START_CANCELED, which the sender sees as CanceledException.
 */
internal class HostedPendingIntentController(private val ams: HostedActivityManagerService) {
    class Key(
        val type: Int,
        val packageName: String?,
        val uid: Int,
        val requestCode: Int,
        val intents: Array<Intent?>?,
        val resolvedTypes: Array<String?>?,
        val flags: Int,
    ) {
        val requestIntent: Intent? get() = intents?.lastOrNull()
        override fun equals(other: Any?): Boolean {
            if (other !is Key) return false
            if (type != other.type || uid != other.uid || requestCode != other.requestCode || flags != other.flags) return false
            if (packageName != other.packageName) return false
            val a = intents ?: emptyArray(); val b = other.intents ?: emptyArray()
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]; val y = b[i]
                if (x == null || y == null) { if (x !== y) return false } else if (!x.filterEquals(y)) return false
            }
            val ra = resolvedTypes ?: emptyArray(); val rb = other.resolvedTypes ?: emptyArray()
            return ra.contentEquals(rb)
        }
        override fun hashCode(): Int {
            var h = type * 31 + uid; h = h * 31 + requestCode; h = h * 31 + flags
            h = h * 31 + (packageName?.hashCode() ?: 0)
            intents?.forEach { h = h * 31 + (it?.filterHashCode() ?: 0) }
            resolvedTypes?.forEach { h = h * 31 + (it?.hashCode() ?: 0) }
            return h
        }
    }

    inner class Record(val key: Key) : IIntentSender.Stub() {
        @Volatile var canceled = false
        val cancelListeners = ArrayList<com.android.internal.os.IResultReceiver>()
        val immutable: Boolean get() = (key.flags and PendingIntent.FLAG_IMMUTABLE) != 0

        override fun send(
            code: Int, intent: Intent?, resolvedType: String?, whitelistToken: IBinder?,
            finishedReceiver: IIntentReceiver?, requiredPermission: String?, options: Bundle?,
        ) {
            sendInner(this, code, intent, resolvedType, finishedReceiver, requiredPermission, options)
        }
    }

    private val lock = Any()
    private val records = HashMap<Key, Record>()

    fun getIntentSender(
        type: Int, packageName: String?, uid: Int, requestCode: Int,
        intents: Array<Intent?>?, resolvedTypes: Array<String?>?, flags: Int,
    ): IIntentSender? {
        val noCreate = (flags and PendingIntent.FLAG_NO_CREATE) != 0
        val cancelCurrent = (flags and PendingIntent.FLAG_CANCEL_CURRENT) != 0
        val updateCurrent = (flags and PendingIntent.FLAG_UPDATE_CURRENT) != 0
        val keyFlags = flags and (PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_UPDATE_CURRENT).inv()
        val key = Key(type, packageName, uid, requestCode, intents, resolvedTypes, keyFlags)
        synchronized(lock) {
            val existing = records[key]
            if (existing != null) {
                if (!cancelCurrent) {
                    if (updateCurrent) {
                        val request = existing.key.requestIntent
                        val fresh = key.requestIntent
                        if (request != null && fresh != null) request.replaceExtras(fresh)
                    }
                    return existing
                }
                cancelLocked(existing)
            }
            if (noCreate) return null
            val record = Record(key)
            records[key] = record
            return record
        }
    }

    fun cancel(sender: IIntentSender?) {
        val record = sender as? Record ?: return
        synchronized(lock) { cancelLocked(record) }
    }

    private fun cancelLocked(record: Record) {
        record.canceled = true
        records.remove(record.key)
        val listeners = ArrayList(record.cancelListeners)
        record.cancelListeners.clear()
        for (listener in listeners) {
            try { listener.send(android.app.Activity.RESULT_CANCELED, null) } catch (e: RemoteException) {}
        }
    }

    fun record(sender: IIntentSender?): Record? = sender as? Record

    fun registerCancelListener(sender: IIntentSender?, receiver: com.android.internal.os.IResultReceiver?): Boolean {
        val record = sender as? Record ?: return false
        receiver ?: return false
        synchronized(lock) {
            if (record.canceled) return false
            record.cancelListeners.add(receiver)
            return true
        }
    }

    fun unregisterCancelListener(sender: IIntentSender?, receiver: com.android.internal.os.IResultReceiver?) {
        val record = sender as? Record ?: return
        synchronized(lock) { record.cancelListeners.remove(receiver) }
    }

    /** PendingIntentRecord.sendInner: fill in, dispatch by type, then the finished receiver. */
    fun sendInner(
        record: Record, code: Int, fillIn: Intent?, resolvedType: String?,
        finishedReceiver: IIntentReceiver?, requiredPermission: String?, options: Bundle?,
    ): Int {
        if (record.canceled) return ActivityManager.START_CANCELED
        val key = record.key
        val finalIntent = key.requestIntent?.let { Intent(it) } ?: Intent()
        if (!record.immutable && fillIn != null) finalIntent.fillIn(fillIn, key.flags)
        val result = when (key.type) {
            ActivityManager.INTENT_SENDER_BROADCAST -> {
                ams.broadcastFromPendingIntent(key.packageName, key.uid, finalIntent, requiredPermission, finishedReceiver, code)
                ActivityManager.START_SUCCESS
            }
            ActivityManager.INTENT_SENDER_SERVICE, ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE -> {
                ams.startServiceFromPendingIntent(key.packageName, key.uid, finalIntent)
                finishedReceiver?.let { deliverFinished(it, finalIntent, code) }
                ActivityManager.START_SUCCESS
            }
            else -> {
                Log.w(TAG, "activity PendingIntent send is not hosted (needs the activity task manager): " + finalIntent)
                ActivityManager.START_CANCELED
            }
        }
        if ((key.flags and PendingIntent.FLAG_ONE_SHOT) != 0) synchronized(lock) { cancelLocked(record) }
        return result
    }

    private fun deliverFinished(receiver: IIntentReceiver, intent: Intent, code: Int) {
        try {
            receiver.performReceive(intent, code, null, null, false, false, 0)
        } catch (e: RemoteException) {
        }
    }

    private companion object {
        const val TAG = "HostedPendingIntentController"
    }
}
