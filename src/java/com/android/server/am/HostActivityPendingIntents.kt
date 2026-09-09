package com.android.server.am

import android.app.ActivityManager
import android.app.IApplicationThread
import android.app.PendingIntent
import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.android.internal.os.IResultReceiver
import com.android.server.LocalServices
import com.android.server.wm.ActivityTaskManagerInternal

/** ActivityManagerService's activity PendingIntent dispatch and record inspection.
 * AMS itself needs the unavailable Android process/daemon stack. ATMS and its
 * PendingIntentController own activity records, launch policy and result delivery;
 * the hosted AMS continues to own service and broadcast dispatch. Package access
 * keeps the stock records and cancellation lock private to their AOSP package. */
object HostActivityPendingIntents {
    fun owns(sender: IIntentSender?) = sender is PendingIntentRecord

    fun create(type: Int, packageName: String?, featureId: String?, uid: Int, userId: Int,
        token: IBinder?, who: String?, requestCode: Int, intents: Array<Intent?>?,
        resolvedTypes: Array<String?>?, flags: Int, options: Bundle?): IIntentSender? {
        require(intents == null || intents.isNotEmpty()) { "Intents array length must be >= 1" }
        require(resolvedTypes == null || resolvedTypes.size == intents?.size) {
            "Intent array length does not match resolvedTypes length"
        }
        require(options?.hasFileDescriptors() != true) { "File descriptors passed in options" }
        val copies = intents?.map { source -> source?.let {
            require(!it.hasFileDescriptors()) { "File descriptors passed in Intent" }
            if (PendingIntent.isNewMutableDisallowedImplicitPendingIntent(flags, it,
                    type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) &&
                android.app.compat.CompatChanges.isChangeEnabled(
                    PendingIntent.BLOCK_MUTABLE_IMPLICIT_PENDING_INTENT, checkNotNull(packageName),
                    android.os.UserHandle.of(userId))) {
                throw IllegalArgumentException("Mutable implicit PendingIntent requires an explicit target")
            }
            Intent(it).apply { removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH) }
        } }?.toTypedArray()
        // This host has one Android user, matching its user-controller boundary.
        require(userId == 0 || userId == android.os.UserHandle.USER_CURRENT) { "Unknown hosted user $userId" }
        return checkNotNull(LocalServices.getService(ActivityTaskManagerInternal::class.java))
            .getIntentSender(type, packageName, featureId, uid, userId, token, who,
                requestCode, copies, resolvedTypes, flags, options)
    }

    fun send(caller: IApplicationThread?, sender: IIntentSender, allowlist: IBinder?, code: Int,
        intent: Intent?, type: String?, finished: IIntentReceiver?, permission: String?, options: Bundle?): Int =
        (sender as PendingIntentRecord).sendWithResult(caller, code, intent, type, allowlist,
            finished, permission, options)

    fun creator(sender: IIntentSender) = (sender as PendingIntentRecord).packageName
    fun cancel(sender: IIntentSender) = (sender as PendingIntentRecord).controller.cancelIntentSender(sender)
    fun register(sender: IIntentSender, receiver: IResultReceiver?) =
        (sender as PendingIntentRecord).controller.registerIntentSenderCancelListener(sender, receiver)
    fun unregister(sender: IIntentSender, receiver: IResultReceiver?) =
        (sender as PendingIntentRecord).controller.unregisterIntentSenderCancelListener(sender, receiver)
    fun info(sender: IIntentSender): ActivityManager.PendingIntentInfo {
        val record = sender as PendingIntentRecord
        val packages = LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
        val filtered = packages.filterAppAccess(record.key.packageName, Binder.getCallingUid(),
            android.os.UserHandle.getUserId(record.uid))
        return ActivityManager.PendingIntentInfo(if (filtered) null else record.key.packageName,
            if (filtered) android.os.Process.INVALID_UID else record.uid,
            record.key.flags and PendingIntent.FLAG_IMMUTABLE != 0, record.key.type)
    }
    fun intent(sender: IIntentSender) = (sender as PendingIntentRecord).key.requestIntent?.let(::Intent)
    fun targeted(sender: IIntentSender): Boolean = (sender as PendingIntentRecord).key.allIntents
        ?.all { it.`package` == null || it.component == null } ?: false
    fun activity(sender: IIntentSender) = (sender as PendingIntentRecord).key.type == ActivityManager.INTENT_SENDER_ACTIVITY
    fun tag(sender: IIntentSender, prefix: String?): String? {
        val intent = (sender as PendingIntentRecord).key.requestIntent ?: return null
        return (prefix ?: "") + (intent.action ?: intent.component?.let { component -> StringBuilder().also(component::appendShortString).toString() } ?: "?")
    }
    fun query(sender: IIntentSender, flags: Int): android.content.pm.ParceledListSlice<android.content.pm.ResolveInfo>? {
        val record = sender as PendingIntentRecord
        val intent = record.key.requestIntent ?: return null
        check(record.key.type == ActivityManager.INTENT_SENDER_ACTIVITY) {
            "Unsupported intent sender type: ${record.key.type}"
        }
        val packages = LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
        return android.content.pm.ParceledListSlice(packages.queryIntentActivities(intent,
            record.key.requestResolvedType, flags.toLong(), record.uid, record.key.userId))
    }
}
