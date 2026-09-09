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
 * records in running sessions are stock PendingIntentRecords owned by ATMS.
 * This controller also supports isolated service tests without a task owner.
 */
internal class HostedPendingIntentController(private val ams: HostedActivityManagerService) {
    class Key(
        val type: Int,
        val packageName: String?,
        val uid: Int,
        val activityToken: IBinder?,
        val resultWho: String?,
        val requestCode: Int,
        var intents: Array<Intent?>?,
        var resolvedTypes: Array<String?>?,
        val flags: Int,
    ) {
        val requestIntent: Intent? get() = intents?.lastOrNull()
        override fun equals(other: Any?): Boolean {
            if (other !is Key) return false
            if (type != other.type || uid != other.uid || activityToken != other.activityToken ||
                resultWho != other.resultWho || requestCode != other.requestCode || flags != other.flags
            ) return false
            if (packageName != other.packageName) return false
            val request = requestIntent
            val otherRequest = other.requestIntent
            if (request == null || otherRequest == null) {
                if (request !== otherRequest) return false
            } else if (!request.filterEquals(otherRequest)) {
                return false
            }
            return resolvedTypes?.lastOrNull() == other.resolvedTypes?.lastOrNull()
        }
        override fun hashCode(): Int {
            var h = type * 31 + uid
            h = h * 31 + (activityToken?.hashCode() ?: 0)
            h = h * 31 + (resultWho?.hashCode() ?: 0)
            h = h * 31 + requestCode
            h = h * 31 + flags
            h = h * 31 + (packageName?.hashCode() ?: 0)
            h = h * 31 + (requestIntent?.filterHashCode() ?: 0)
            h = h * 31 + (resolvedTypes?.lastOrNull()?.hashCode() ?: 0)
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
        type: Int, packageName: String?, uid: Int, activityToken: IBinder?, resultWho: String?,
        requestCode: Int,
        intents: Array<Intent?>?, resolvedTypes: Array<String?>?, flags: Int,
    ): IIntentSender? {
        if (intents != null && intents.isEmpty()) {
            throw IllegalArgumentException("Intents array length must be >= 1")
        }
        if (resolvedTypes != null && resolvedTypes.size != intents?.size) {
            throw IllegalArgumentException("Intent array length does not match resolvedTypes length")
        }
        val noCreate = (flags and PendingIntent.FLAG_NO_CREATE) != 0
        val cancelCurrent = (flags and PendingIntent.FLAG_CANCEL_CURRENT) != 0
        val updateCurrent = (flags and PendingIntent.FLAG_UPDATE_CURRENT) != 0
        val keyFlags = flags and (PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_UPDATE_CURRENT).inv()
        val storedIntents = intents?.let { source ->
            Array<Intent?>(source.size) { index -> source[index]?.let(::Intent) }
        }
        val storedResolvedTypes = resolvedTypes?.copyOf()
        val key = Key(
            type,
            packageName,
            uid,
            activityToken,
            resultWho,
            requestCode,
            storedIntents,
            storedResolvedTypes,
            keyFlags,
        )
        synchronized(lock) {
            val existing = records[key]
            if (existing != null) {
                if (!cancelCurrent) {
                    if (updateCurrent) {
                        val request = existing.key.requestIntent
                        val fresh = key.requestIntent
                        if (request != null && fresh != null) request.replaceExtras(fresh)
                        existing.key.intents = key.intents?.also {
                            it[it.lastIndex] = request
                        }
                        existing.key.resolvedTypes = key.resolvedTypes
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

    fun cancel(sender: IIntentSender?, callingUid: Int) {
        val record = sender as? Record ?: return
        if (callingUid != record.key.uid && callingUid != android.os.Process.SYSTEM_UID &&
            callingUid != android.os.Process.ROOT_UID
        ) {
            throw SecurityException(
                "uid $callingUid may not cancel ${record.key.packageName}'s IntentSender",
            )
        }
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
        ams.addCreatorToken(fillIn, record.key.packageName)
        val prepared = synchronized(lock) {
            if (record.canceled) return ActivityManager.START_CANCELED
            prepareIntent(record, fillIn, resolvedType, 0, 0).also {
                if ((record.key.flags and PendingIntent.FLAG_ONE_SHOT) != 0) {
                    cancelLocked(record)
                }
            }
        }
        val key = record.key
        val finalIntent = prepared.intent
        val result = when (key.type) {
            ActivityManager.INTENT_SENDER_BROADCAST -> {
                ams.broadcastFromPendingIntent(key.packageName, key.uid, finalIntent, requiredPermission, finishedReceiver, code)
                ActivityManager.START_SUCCESS
            }
            ActivityManager.INTENT_SENDER_SERVICE, ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE -> {
                ams.startServiceFromPendingIntent(key.packageName, key.uid, finalIntent)
                finishedReceiver?.let { deliverFinished(it, finalIntent, 0) }
                ActivityManager.START_SUCCESS
            }
            ActivityManager.INTENT_SENDER_ACTIVITY -> {
                val started = startActivity(record, prepared, null, null, -1, options)
                if (!ActivityManager.isStartResultFatalError(started)) {
                    finishedReceiver?.let { deliverFinished(it, finalIntent, 0) }
                }
                started
            }
            ActivityManager.INTENT_SENDER_ACTIVITY_RESULT -> {
                // Activity senders in a running session are stock PendingIntentRecords.
                // An isolated AMS service fixture has no activity/task owner.
                ActivityManager.START_CANCELED
            }
            else -> {
                Log.w(TAG, "unsupported PendingIntent kind ${key.type}: $finalIntent")
                ActivityManager.START_CANCELED
            }
        }
        return result
    }

    /** ActivityTaskManagerService.startActivityIntentSender on a hosted PendingIntent record. */
    fun sendActivity(
        sender: IIntentSender?,
        fillIn: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        flagsMask: Int,
        flagsValues: Int,
        options: Bundle?,
    ): Int {
        val record = sender as? Record ?: return ActivityManager.START_CANCELED
        ams.addCreatorToken(fillIn, record.key.packageName)
        val prepared = synchronized(lock) {
            if (record.canceled) return ActivityManager.START_CANCELED
            if (record.key.type != ActivityManager.INTENT_SENDER_ACTIVITY) {
                return ActivityManager.START_NOT_ACTIVITY
            }
            prepareIntent(record, fillIn, resolvedType, flagsMask, flagsValues).also {
                if ((record.key.flags and PendingIntent.FLAG_ONE_SHOT) != 0) {
                    cancelLocked(record)
                }
            }
        }
        val result = startActivity(record, prepared, resultTo, resultWho, requestCode, options)
        return result
    }

    private data class PreparedIntent(
        val intent: Intent,
        val resolvedType: String?,
        val allIntents: Array<Intent?>?,
        val allResolvedTypes: Array<String?>?,
    )

    /**
     * PendingIntentRecord.sendInner: immutable senders retain their creator's data/type/flags;
     * mutable senders may fill data and may replace only flags outside Intent.IMMUTABLE_FLAGS.
     */
    private fun prepareIntent(
        record: Record,
        fillIn: Intent?,
        suppliedResolvedType: String?,
        suppliedFlagsMask: Int,
        suppliedFlagsValues: Int,
    ): PreparedIntent {
        val key = record.key
        val intent = key.requestIntent?.let(::Intent) ?: Intent()
        var resolvedType = suppliedResolvedType
        if (record.immutable) {
            resolvedType = key.resolvedTypes?.lastOrNull()
        } else {
            if (fillIn == null) {
                resolvedType = key.resolvedTypes?.lastOrNull()
            } else {
                val changes = intent.fillIn(fillIn, key.flags)
                if (changes and Intent.FILL_IN_DATA == 0) {
                    resolvedType = key.resolvedTypes?.lastOrNull()
                }
            }
            val flagsMask = suppliedFlagsMask and Intent.IMMUTABLE_FLAGS.inv()
            val flagsValues = suppliedFlagsValues and flagsMask
            intent.flags = (intent.flags and flagsMask.inv()) or flagsValues
        }
        val allIntents = key.intents?.takeIf { it.size > 1 }?.let { source ->
            Array<Intent?>(source.size) { index -> source[index]?.let(::Intent) }.also {
                it[it.lastIndex] = intent
            }
        }
        val allResolvedTypes = allIntents?.let { copied ->
            Array<String?>(copied.size) { index -> key.resolvedTypes?.getOrNull(index) }.also {
                it[it.lastIndex] = resolvedType
            }
        }
        return PreparedIntent(intent, resolvedType, allIntents, allResolvedTypes)
    }

    /** PendingIntentRecord.sendInner preserves every creator-supplied intent in getActivities. */
    private fun startActivity(
        record: Record,
        prepared: PreparedIntent,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        options: Bundle?,
    ): Int {
        return ActivityManager.START_CANCELED
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
