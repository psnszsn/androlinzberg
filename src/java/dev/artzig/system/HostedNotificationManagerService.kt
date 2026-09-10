package dev.artzig.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.pm.ParceledListSlice
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The session's notification manager over the real `INotificationManager`
 * surface, published as `Context.NOTIFICATION_SERVICE`.
 *
 * Source note: `com.android.server.notification.NotificationManagerService`
 * (`enqueueNotificationInternal`, `cancelNotificationWithTag`,
 * `cancelAllNotifications`, `getAppActiveNotifications`) and its
 * `PreferencesHelper` (channel and channel-group tables, the pre-O default
 * channel, importance). The AOSP service is inseparable from the ranking,
 * listener and zen machinery and from SystemUI's shade, none of which exist
 * on this host: notifications are recorded in the same per-package tables the
 * AOSP service keeps and surfaced through the testkit and one log line per
 * post/cancel. Zen, listeners, assistants and bubbles answer with the AOSP
 * "nothing configured" values. Anything else stays unsupported at the
 * service boundary.
 */
class HostedNotificationManagerService : android.app.INotificationManager.Stub(), android.app.INotificationManager by unsupported<android.app.INotificationManager>() {
    override fun asBinder(): android.os.IBinder = this

    /** The package manager resolves uids to packages (NMS.checkCallerIsSameApp). */
    @Volatile var packageService: HostedPackageManagerService? = null

    private val lock = Any()

    /** NMS.mNotificationList keyed by StatusBarNotification.key, in post order. */
    private val notifications = LinkedHashMap<String, StatusBarNotification>()

    private class PackagePreferences {
        /** PreferencesHelper.PackagePreferences.channels (deleted channels are removed). */
        val channels = LinkedHashMap<String, NotificationChannel>()
        val groups = LinkedHashMap<String, NotificationChannelGroup>()
        var importance = NotificationManager.IMPORTANCE_UNSPECIFIED
        var delegate: String? = null
    }

    private val preferences = LinkedHashMap<String, PackagePreferences>()

    private fun prefsLocked(pkg: String): PackagePreferences = preferences.getOrPut(pkg) { PackagePreferences() }

    // ------------------------------------------------------------ identity

    /** NMS.checkCallerIsSameApp: the calling uid must own the package (the system may act for any). */
    private fun checkCallerIsSystemOrSameApp(pkg: String?) {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == 0) return
        val packages = packageService?.getPackagesForUid(uid)
        if (packages == null || packages.isEmpty() || packages.contains(pkg)) return
        throw SecurityException("Calling uid $uid gave package $pkg which is owned by another uid")
    }

    private fun uidOf(pkg: String?): Int {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID) return uid
        return packageService?.getPackageUidInternal(pkg ?: return uid)?.takeIf { it > 0 } ?: uid
    }

    private fun targetSdkOf(pkg: String?): Int =
        packageService?.getApplicationInfo(pkg, 0L, UserHandle.USER_SYSTEM)?.targetSdkVersion
            ?: android.os.Build.VERSION.SDK_INT

    // ------------------------------------------------------------ posting

    override fun enqueueNotificationWithTag(pkg: String?, opPkg: String?, tag: String?, id: Int, notification: Notification?, userId: Int) {
        checkCallerIsSystemOrSameApp(pkg)
        requireNotNull(pkg) { "pkg must not be null" }
        requireNotNull(notification) { "Notification must not be null" }
        enqueueNotification(pkg, opPkg, tag, id, notification, UserHandle.USER_SYSTEM,
            uidOf(pkg), Binder.getCallingPid())
    }

    private fun enqueueNotification(pkg: String, opPkg: String?, tag: String?, id: Int,
        notification: Notification, userId: Int, uid: Int, pid: Int) {
        // NMS.fixNotification: a pre-O app posts on the default channel.
        var channelId = notification.channelId
        if (channelId == null && targetSdkOf(pkg) < android.os.Build.VERSION_CODES.O) {
            channelId = NotificationChannel.DEFAULT_CHANNEL_ID
        }
        val sbn = StatusBarNotification(pkg, opPkg, id, tag, uid, pid, notification, UserHandle(userId), null, System.currentTimeMillis())
        synchronized(lock) {
            val prefs = prefsLocked(pkg)
            val channel = channelId?.let { prefs.channels[it] }
                ?: (if (channelId == NotificationChannel.DEFAULT_CHANNEL_ID) defaultChannelLocked(prefs) else null)
            if (channel == null) {
                // NMS.enqueueNotificationInternal: no channel -> log and drop (a
                // developer-warning toast on a device); nothing is thrown.
                Log.e(TAG, "No Channel found for pkg=$pkg, channelId=$channelId, id=$id, tag=$tag, opPkg=$opPkg, callingUid=$uid, userId=$userId, notification=$notification")
                return
            }
            if (channel.importance == NotificationManager.IMPORTANCE_NONE || prefs.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.i(TAG, "notify dropped (blocked): pkg=$pkg id=$id tag=$tag channel=${channel.id}")
                return
            }
            // NMS.PostNotificationRunnable: an existing key is an update that keeps
            // the original post time and position.
            val existing = notifications[sbn.key]
            if (existing != null) {
                val updated = StatusBarNotification(pkg, opPkg, id, tag, uid, pid, notification, sbn.user, null, existing.postTime)
                notifications[sbn.key] = updated
            } else {
                notifications[sbn.key] = sbn
            }
        }
        Log.i(TAG, (if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) "notify (foreground service)" else "notify") +
            ": pkg=$pkg id=$id tag=$tag channel=$channelId title=${notification.extras?.getCharSequence(Notification.EXTRA_TITLE)} text=${notification.extras?.getCharSequence(Notification.EXTRA_TEXT)}")
    }

    /** NMS.cancelNotification with mustNotHaveFlags: an app cannot cancel its own FGS/UIJ notification. */
    private fun cancelLocked(pkg: String, tag: String?, id: Int, mustNotHaveFlags: Int, reason: String): Boolean {
        val entry = notifications.entries.firstOrNull { (_, sbn) ->
            sbn.packageName == pkg && sbn.id == id && sbn.tag == tag
        } ?: return false
        if (entry.value.notification.flags and mustNotHaveFlags != 0) return false
        notifications.remove(entry.key)
        Log.i(TAG, "cancel ($reason): pkg=$pkg id=$id tag=$tag")
        return true
    }

    private fun appCancelMask(): Int =
        if (Binder.getCallingUid() == Process.SYSTEM_UID) 0
        else Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_USER_INITIATED_JOB

    override fun cancelNotificationWithTag(pkg: String?, opPkg: String?, tag: String?, id: Int, userId: Int) {
        checkCallerIsSystemOrSameApp(pkg)
        synchronized(lock) { cancelLocked(pkg ?: return, tag, id, appCancelMask(), "app cancel") }
    }

    override fun cancelAllNotifications(pkg: String?, userId: Int) {
        checkCallerIsSystemOrSameApp(pkg)
        val mask = appCancelMask()
        synchronized(lock) {
            val removed = notifications.values.filter { it.packageName == pkg && it.notification.flags and mask == 0 }
            for (sbn in removed) notifications.remove(sbn.key)
            if (removed.isNotEmpty()) Log.i(TAG, "cancelAll (app cancel all): pkg=$pkg count=${removed.size}")
        }
    }

    /** ServiceRecord.postNotification: the activity manager posts the foreground notification as the app. */
    internal fun postForegroundServiceNotification(pkg: String, uid: Int, pid: Int, id: Int, notification: Notification) {
        val ident = Binder.clearCallingIdentity()
        try {
            // NotificationManagerInternal receives the service owner's UID and
            // live process PID explicitly; Binder's caller here is system_server.
            enqueueNotification(pkg, pkg, null, id, notification.clone(), UserHandle.getUserId(uid), uid, pid)
        } finally {
            Binder.restoreCallingIdentity(ident)
        }
    }

    /** ServiceRecord.cancelNotification: the system removes the foreground notification (mustNotHaveFlags 0). */
    internal fun cancelForegroundServiceNotification(pkg: String, id: Int) {
        synchronized(lock) { cancelLocked(pkg, null, id, 0, "foreground service stopped") }
    }

    /** STOP_FOREGROUND_DETACH: the notification stays, no longer marked as a foreground service. */
    internal fun detachForegroundServiceNotification(pkg: String, id: Int) {
        synchronized(lock) {
            val sbn = notifications.values.firstOrNull { it.packageName == pkg && it.id == id && it.tag == null } ?: return
            sbn.notification.flags = sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE.inv()
        }
    }

    override fun getAppActiveNotifications(callingPkg: String?, userId: Int): ParceledListSlice<*>? {
        checkCallerIsSystemOrSameApp(callingPkg)
        return synchronized(lock) {
            ParceledListSlice(notifications.values.filter { it.packageName == callingPkg }.map { it.clone() })
        }
    }

    override fun getActiveNotifications(callingPkg: String?): Array<StatusBarNotification?>? =
        getActiveNotificationsWithAttribution(callingPkg, null)

    override fun getActiveNotificationsWithAttribution(callingPkg: String?, callingAttributionTag: String?): Array<StatusBarNotification?>? {
        // NMS requires ACCESS_NOTIFICATIONS (system/shell); the hosted system process may read every notification.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("Permission Denial: getActiveNotifications requires android.permission.ACCESS_NOTIFICATIONS")
        }
        return synchronized(lock) { notifications.values.map { it.clone() }.toTypedArray() }
    }

    /** Every posted notification, for the launcher's testkit observation and dumps. */
    fun activeNotifications(): List<StatusBarNotification> = synchronized(lock) { notifications.values.map { it.clone() } }

    override fun getHistoricalNotifications(callingPkg: String?, count: Int, includeSnoozed: Boolean): Array<StatusBarNotification?>? = arrayOfNulls(0)
    override fun getHistoricalNotificationsWithAttribution(callingPkg: String?, callingAttributionTag: String?, count: Int, includeSnoozed: Boolean): Array<StatusBarNotification?>? = arrayOfNulls(0)

    // ------------------------------------------------------------ channels (PreferencesHelper)

    /** PreferencesHelper.createDefaultChannelIfNeededLocked for pre-O packages. */
    private fun defaultChannelLocked(prefs: PackagePreferences): NotificationChannel =
        prefs.channels.getOrPut(NotificationChannel.DEFAULT_CHANNEL_ID) {
            NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "Miscellaneous", NotificationManager.IMPORTANCE_UNSPECIFIED)
        }

    /** PreferencesHelper.createNotificationChannel(fromTargetApp = true). */
    private fun createChannelLocked(prefs: PackagePreferences, channel: NotificationChannel) {
        val id = channel.id
        require(!id.isNullOrEmpty()) { "Invalid channel id: $id" }
        require(id != NotificationChannel.DEFAULT_CHANNEL_ID) { "Reserved id" }
        val group = channel.group
        if (group != null) require(prefs.groups.containsKey(group)) { "NotificationChannelGroup doesn't exist" }
        val existing = prefs.channels[id]
        if (existing != null) {
            // Actually modifying an existing channel - keep most of the existing settings.
            // Apps are allowed to downgrade channel importance if the user has not changed
            // any fields on this channel yet.
            if (existing.userLockedFields == 0 && channel.importance < existing.importance) {
                existing.importance = channel.importance
            }
            existing.name = channel.name
            existing.description = channel.description
            existing.setBlockable(channel.isBlockable)
            if (channel.group != null && existing.group == null) existing.group = channel.group
            return
        }
        require(channel.importance in NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_MAX) { "Invalid importance level" }
        check(prefs.channels.size < NOTIFICATION_CHANNEL_COUNT_LIMIT) { "Limit exceed; cannot create more channels" }
        prefs.channels[id] = NotificationChannel(channel.id, channel.name, channel.importance).also { copy ->
            copy.description = channel.description
            copy.group = channel.group
            copy.setSound(channel.sound, channel.audioAttributes)
            copy.enableLights(channel.shouldShowLights())
            copy.lightColor = channel.lightColor
            copy.enableVibration(channel.shouldVibrate())
            copy.vibrationPattern = channel.vibrationPattern
            copy.lockscreenVisibility = channel.lockscreenVisibility
            copy.setBypassDnd(channel.canBypassDnd())
            copy.setShowBadge(channel.canShowBadge())
            copy.setBlockable(channel.isBlockable)
            copy.setAllowBubbles(channel.canBubble())
        }
    }

    override fun createNotificationChannels(pkg: String?, channelsList: ParceledListSlice<*>?) {
        checkCallerIsSystemOrSameApp(pkg)
        val channels = channelsList?.list ?: return
        synchronized(lock) {
            val prefs = prefsLocked(pkg ?: return)
            for (channel in channels) createChannelLocked(prefs, channel as NotificationChannel)
        }
    }

    override fun createNotificationChannelsForPackage(pkg: String?, uid: Int, channelsList: ParceledListSlice<*>?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("createNotificationChannelsForPackage requires the system")
        val channels = channelsList?.list ?: return
        synchronized(lock) {
            val prefs = prefsLocked(pkg ?: return)
            for (channel in channels) createChannelLocked(prefs, channel as NotificationChannel)
        }
    }

    override fun getNotificationChannel(callingPkg: String?, userId: Int, pkg: String?, channelId: String?): NotificationChannel? {
        checkCallerIsSystemOrSameApp(callingPkg)
        return synchronized(lock) {
            val prefs = preferences[pkg ?: return null] ?: return null
            if (channelId == NotificationChannel.DEFAULT_CHANNEL_ID && targetSdkOf(pkg) < android.os.Build.VERSION_CODES.O) {
                return defaultChannelLocked(prefs).copy()
            }
            prefs.channels[channelId ?: return null]?.copy()
        }
    }

    override fun getConversationNotificationChannel(callingPkg: String?, userId: Int, pkg: String?, channelId: String?, returnParentIfNoConversationChannel: Boolean, conversationId: String?): NotificationChannel? =
        if (returnParentIfNoConversationChannel || conversationId == null) getNotificationChannel(callingPkg, userId, pkg, channelId) else null

    override fun getNotificationChannelForPackage(pkg: String?, uid: Int, channelId: String?, conversationId: String?, includeDeleted: Boolean): NotificationChannel? =
        synchronized(lock) { preferences[pkg ?: return null]?.channels?.get(channelId ?: return null)?.copy() }

    override fun getNotificationChannels(callingPkg: String?, targetPkg: String?, userId: Int): ParceledListSlice<*>? {
        checkCallerIsSystemOrSameApp(callingPkg)
        return synchronized(lock) {
            ParceledListSlice(preferences[targetPkg]?.channels?.values?.map { it.copy() } ?: emptyList())
        }
    }

    override fun getNotificationChannelsForPackage(pkg: String?, uid: Int, includeDeleted: Boolean): ParceledListSlice<*>? =
        synchronized(lock) { ParceledListSlice(preferences[pkg]?.channels?.values?.map { it.copy() } ?: emptyList()) }

    override fun getNumNotificationChannelsForPackage(pkg: String?, uid: Int, includeDeleted: Boolean): Int =
        synchronized(lock) { preferences[pkg]?.channels?.size ?: 0 }

    override fun getDeletedChannelCount(pkg: String?, uid: Int): Int = 0
    override fun getBlockedChannelCount(pkg: String?, uid: Int): Int =
        synchronized(lock) { preferences[pkg]?.channels?.values?.count { it.importance == NotificationManager.IMPORTANCE_NONE } ?: 0 }

    override fun deleteNotificationChannel(pkg: String?, channelId: String?) {
        checkCallerIsSystemOrSameApp(pkg)
        require(channelId != NotificationChannel.DEFAULT_CHANNEL_ID) { "Cannot delete default channel" }
        synchronized(lock) {
            val prefs = preferences[pkg ?: return] ?: return
            if (prefs.channels.remove(channelId) == null) return
            // NMS.deleteNotificationChannel cancels every notification on the channel.
            val removed = notifications.values.filter { it.packageName == pkg && it.notification.channelId == channelId }
            for (sbn in removed) notifications.remove(sbn.key)
            Log.i(TAG, "channel deleted: pkg=$pkg channel=$channelId cancelled=${removed.size}")
        }
    }

    override fun updateNotificationChannelForPackage(pkg: String?, uid: Int, channel: NotificationChannel?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("updateNotificationChannelForPackage requires the system")
        synchronized(lock) {
            val prefs = prefsLocked(pkg ?: return)
            prefs.channels[channel?.id ?: return] = channel.copy()
        }
    }

    override fun createNotificationChannelGroups(pkg: String?, channelGroupList: ParceledListSlice<*>?) {
        checkCallerIsSystemOrSameApp(pkg)
        val groups = channelGroupList?.list ?: return
        synchronized(lock) {
            val prefs = prefsLocked(pkg ?: return)
            for (raw in groups) {
                val group = raw as NotificationChannelGroup
                require(!group.id.isNullOrEmpty()) { "Invalid group id: ${group.id}" }
                // PreferencesHelper.createNotificationChannelGroup: the new group
                // object replaces the old one, keeping its blocked state.
                val existing = prefs.groups[group.id]
                prefs.groups[group.id] = group.clone().also { if (existing != null) it.setBlocked(existing.isBlocked) }
            }
        }
    }

    override fun getNotificationChannelGroups(pkg: String?): ParceledListSlice<*>? {
        checkCallerIsSystemOrSameApp(pkg)
        return synchronized(lock) {
            val prefs = preferences[pkg] ?: return ParceledListSlice(emptyList<NotificationChannelGroup>())
            ParceledListSlice(prefs.groups.values.map { group ->
                group.clone().also { it.setChannels(prefs.channels.values.filter { c -> c.group == group.id }.map { c -> c.copy() }) }
            })
        }
    }

    override fun getNotificationChannelGroupsWithoutChannels(pkg: String?): ParceledListSlice<*>? {
        checkCallerIsSystemOrSameApp(pkg)
        return synchronized(lock) { ParceledListSlice(preferences[pkg]?.groups?.values?.map { it.clone() } ?: emptyList()) }
    }

    override fun getNotificationChannelGroup(pkg: String?, channelGroupId: String?): NotificationChannelGroup? {
        checkCallerIsSystemOrSameApp(pkg)
        return synchronized(lock) {
            val prefs = preferences[pkg] ?: return null
            val group = prefs.groups[channelGroupId] ?: return null
            group.clone().also { it.setChannels(prefs.channels.values.filter { c -> c.group == group.id }.map { c -> c.copy() }) }
        }
    }

    override fun getNotificationChannelGroupsForPackage(pkg: String?, uid: Int, includeDeleted: Boolean): ParceledListSlice<*>? =
        synchronized(lock) { ParceledListSlice(preferences[pkg]?.groups?.values?.map { it.clone() } ?: emptyList()) }

    override fun getNotificationChannelGroupForPackage(groupId: String?, pkg: String?, uid: Int): NotificationChannelGroup? =
        synchronized(lock) { preferences[pkg]?.groups?.get(groupId)?.clone() }

    override fun getRecentBlockedNotificationChannelGroupsForPackage(pkg: String?, uid: Int): ParceledListSlice<*>? =
        ParceledListSlice(emptyList<NotificationChannelGroup>())

    override fun deleteNotificationChannelGroup(pkg: String?, channelGroupId: String?) {
        checkCallerIsSystemOrSameApp(pkg)
        synchronized(lock) {
            val prefs = preferences[pkg ?: return] ?: return
            prefs.groups.remove(channelGroupId) ?: return
            // NMS.deleteNotificationChannelGroup deletes the group's channels (and their notifications).
            val channelIds = prefs.channels.values.filter { it.group == channelGroupId }.map { it.id }
            for (id in channelIds) prefs.channels.remove(id)
            val removed = notifications.values.filter { it.packageName == pkg && it.notification.channelId in channelIds }
            for (sbn in removed) notifications.remove(sbn.key)
            Log.i(TAG, "channel group deleted: pkg=$pkg group=$channelGroupId channels=${channelIds.size} cancelled=${removed.size}")
        }
    }

    // ------------------------------------------------------------ toasts (NMS.enqueueToast / ToastRecord)

    private class ToastRecord(val pkg: String, val token: android.os.IBinder, val hide: () -> Unit) {
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
    }

    private val toasts = LinkedHashMap<android.os.IBinder, ToastRecord>()
    private val toastExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotificationToasts").apply { isDaemon = true }
    }

    /**
     * NMS.enqueueToast reduced to a host without SystemUI: the toast is shown at
     * once (custom views through ITransientNotification.show, text through the
     * ITransientNotificationCallback SystemUI would drive) and hidden after the
     * AOSP LONG_DELAY / SHORT_DELAY.
     */
    private fun enqueueToastInternal(pkg: String?, token: android.os.IBinder?, duration: Int, description: String, show: () -> Unit, hide: () -> Unit): Boolean {
        checkCallerIsSystemOrSameApp(pkg)
        pkg ?: return false
        token ?: return false
        val callingUid = Binder.getCallingUid()
        // NMS: a package blocked from notifications may not toast either (system and shell excepted).
        if (callingUid != Process.SYSTEM_UID && !areNotificationsEnabledForPackage(pkg, callingUid)) {
            Log.e(TAG, "Suppressing toast from package $pkg by user request.")
            return false
        }
        val record = ToastRecord(pkg, token, hide)
        synchronized(lock) {
            if (toasts.values.count { it.pkg == pkg } >= MAX_PACKAGE_TOASTS) {
                Log.e(TAG, "Package has already queued $MAX_PACKAGE_TOASTS toasts. Not showing more. Package=$pkg")
                return false
            }
            toasts[token] = record
        }
        Log.i(TAG, "toast: pkg=$pkg $description")
        try {
            show()
        } catch (e: android.os.RemoteException) {
            synchronized(lock) { toasts.remove(token) }
            return true
        }
        val delay = if (duration == android.widget.Toast.LENGTH_LONG) LONG_DELAY else SHORT_DELAY
        record.timer = toastExecutor.schedule({ cancelToastRecord(token) }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        return true
    }

    private fun cancelToastRecord(token: android.os.IBinder) {
        val record = synchronized(lock) { toasts.remove(token) } ?: return
        record.timer?.cancel(false)
        try {
            record.hide()
        } catch (e: android.os.RemoteException) {
            // the toaster is gone
        }
    }

    override fun enqueueTextToast(pkg: String?, token: android.os.IBinder?, text: CharSequence?, duration: Int, isUiContext: Boolean, displayId: Int, callback: android.app.ITransientNotificationCallback?): Boolean =
        enqueueToastInternal(
            pkg, token, duration, "text=\"$text\"",
            { callback?.onToastShown() },
            { callback?.onToastHidden() },
        )

    override fun enqueueToast(pkg: String?, token: android.os.IBinder?, callback: android.app.ITransientNotification?, duration: Int, isUiContext: Boolean, displayId: Int): Boolean =
        enqueueToastInternal(
            pkg, token, duration, "custom view",
            { callback?.show(token) },
            { callback?.hide() },
        )

    override fun cancelToast(pkg: String?, token: android.os.IBinder?) {
        checkCallerIsSystemOrSameApp(pkg)
        cancelToastRecord(token ?: return)
    }

    /** NMS.finishToken releases the window token; the hosted toast owns no token. */
    override fun finishToken(pkg: String?, token: android.os.IBinder?) {}

    // ------------------------------------------------------------ importance and policy

    override fun areNotificationsEnabled(pkg: String?): Boolean = areNotificationsEnabledForPackage(pkg, Binder.getCallingUid())
    override fun areNotificationsEnabledForPackage(pkg: String?, uid: Int): Boolean =
        synchronized(lock) { (preferences[pkg]?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED) != NotificationManager.IMPORTANCE_NONE }
    override fun getPackageImportance(pkg: String?): Int =
        synchronized(lock) { preferences[pkg]?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED }
    override fun setNotificationsEnabledForPackage(pkg: String?, uid: Int, enabled: Boolean) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setNotificationsEnabledForPackage requires the system")
        synchronized(lock) {
            prefsLocked(pkg ?: return).importance =
                if (enabled) NotificationManager.IMPORTANCE_UNSPECIFIED else NotificationManager.IMPORTANCE_NONE
        }
    }
    override fun canShowBadge(pkg: String?, uid: Int): Boolean = true
    override fun hasSentValidMsg(pkg: String?, uid: Int): Boolean = false
    override fun isImportanceLocked(pkg: String?, uid: Int): Boolean = false
    override fun getAllowedAssistantAdjustments(pkg: String?): MutableList<String?>? = ArrayList()
    override fun shouldHideSilentStatusIcons(callingPkg: String?): Boolean = false
    override fun areBubblesAllowed(pkg: String?): Boolean = false
    override fun areBubblesEnabled(user: UserHandle?): Boolean = false
    override fun getBubblePreferenceForPackage(pkg: String?, uid: Int): Int = NotificationManager.BUBBLE_PREFERENCE_NONE
    override fun isPackagePaused(pkg: String?): Boolean = false
    override fun canUseFullScreenIntent(attributionSource: android.content.AttributionSource?): Boolean = true
    override fun getPrivateNotificationsAllowed(): Boolean = true
    override fun setNotificationDelegate(callingPkg: String?, delegate: String?) {
        checkCallerIsSystemOrSameApp(callingPkg)
        synchronized(lock) { prefsLocked(callingPkg ?: return).delegate = delegate }
    }
    override fun getNotificationDelegate(callingPkg: String?): String? {
        checkCallerIsSystemOrSameApp(callingPkg)
        return synchronized(lock) { preferences[callingPkg]?.delegate }
    }
    override fun canNotifyAsPackage(callingPkg: String?, targetPkg: String?, userId: Int): Boolean {
        checkCallerIsSystemOrSameApp(callingPkg)
        if (callingPkg == targetPkg) return true
        return synchronized(lock) { preferences[targetPkg]?.delegate == callingPkg }
    }

    // Zen, listeners, assistants: the AOSP values of a device with nothing configured.
    override fun getZenMode(): Int = Settings.Global.ZEN_MODE_OFF
    override fun getZenModeConfig(): android.service.notification.ZenModeConfig? = null
    override fun getNotificationPolicy(pkg: String?): NotificationManager.Policy? = defaultPolicy()
    override fun getConsolidatedNotificationPolicy(): NotificationManager.Policy? = defaultPolicy()
    override fun isNotificationPolicyAccessGranted(pkg: String?): Boolean = false
    override fun isNotificationPolicyAccessGrantedForPackage(pkg: String?): Boolean = false
    override fun getEffectsSuppressor(): android.content.ComponentName? = null
    override fun matchesCallFilter(extras: android.os.Bundle?): Boolean = false
    override fun isNotificationListenerAccessGranted(listener: android.content.ComponentName?): Boolean = false
    override fun isNotificationListenerAccessGrantedForUser(listener: android.content.ComponentName?, userId: Int): Boolean = false
    override fun isNotificationAssistantAccessGranted(assistant: android.content.ComponentName?): Boolean = false
    override fun getAutomaticZenRule(id: String?): android.app.AutomaticZenRule? = null
    override fun getAutomaticZenRules(): ParceledListSlice<*>? = ParceledListSlice(emptyList<android.app.AutomaticZenRule>())
    /** NMS.enforcePolicyAccess: no package holds notification policy access on this host. */
    override fun getAutomaticZenRuleState(id: String?): Int = throw SecurityException("Notification policy access denied")
    override fun areChannelsBypassingDnd(): Boolean = false
    override fun getNotificationChannelsBypassingDnd(pkg: String?, uid: Int): ParceledListSlice<*>? = ParceledListSlice(emptyList<NotificationChannel>())
    override fun getInterruptionFilterFromListener(token: android.service.notification.INotificationListener?): Int =
        NotificationManager.INTERRUPTION_FILTER_ALL
    override fun silenceNotificationSound() {}

    /** ZenModeConfig.toNotificationPolicy of the default config (calls/messages from anyone, all categories). */
    private fun defaultPolicy(): NotificationManager.Policy = NotificationManager.Policy(
        NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
            NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS or NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS or
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM,
        NotificationManager.Policy.PRIORITY_SENDERS_ANY,
        NotificationManager.Policy.PRIORITY_SENDERS_ANY,
    )

    private companion object {
        const val TAG = "HostedNotificationManager"
        /** PreferencesHelper.NOTIFICATION_CHANNEL_COUNT_LIMIT. */
        const val NOTIFICATION_CHANNEL_COUNT_LIMIT = 5000
        /** NMS.MAX_PACKAGE_TOASTS / LONG_DELAY / SHORT_DELAY. */
        const val MAX_PACKAGE_TOASTS = 5
        const val LONG_DELAY = 3500L
        const val SHORT_DELAY = 2000L
    }
}
