package dev.artzig.system

import android.app.ContentProviderHolder
import android.app.IApplicationThread
import android.app.IServiceConnection
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log

/**
 * The hosted activity manager: service records and scheduling for every app
 * process of the session.
 *
 * Source note: mirrors `com.android.server.am.ActiveServices`
 * (`bindServiceLocked`, `bringUpServiceLocked`, `requestServiceBindingLocked`,
 * `publishServiceLocked`, `removeConnectionLocked`, `bringDownServiceLocked`,
 * `serviceDoneExecutingLocked`) and
 * `ActivityManagerService.attachApplicationLocked`; the AOSP classes are
 * inseparable from `ActivityManagerService` (the spike showed every record
 * references `mAm`), so the record model is reproduced with the same names.
 * Every callback into an app process is oneway and runs on that process's
 * main looper, which preserves AOSP ordering: onCreate before onBind before
 * onServiceConnected.
 */
class HostedActivityManagerService(private val context: android.content.Context? = null) : android.app.IActivityManager.Stub(), android.app.IActivityManager by unsupported<android.app.IActivityManager>() {
    override fun asBinder(): android.os.IBinder = this

    /** Launches a process for a package that is not attached (production: the launcher's --app-process). */
    fun interface ProcessLauncher {
        /** Returns false when the package cannot be launched. */
        fun launch(packageName: String, apkPath: String): Boolean
    }

    // AppExitInfoTracker equivalent for this hosted session. Binder death supplies no
    // wait status or tombstone, so retain REASON_UNKNOWN instead of inventing one.
    private val processExits = java.util.ArrayDeque<android.app.ApplicationExitInfo>()

    private inner class ProcessRecord(
        val thread: IApplicationThread,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
    ) : IBinder.DeathRecipient {
        val binder: IBinder = thread.asBinder()
        var exitReason = android.app.ApplicationExitInfo.REASON_UNKNOWN
        var exitDescription: String? = null
        override fun binderDied() = handleProcessDied(this)
    }

    /** AOSP Intent.FilterComparison: bindings are keyed by the intent's filter fields. */
    private class FilterComparison(val intent: Intent) {
        override fun equals(other: Any?): Boolean = other is FilterComparison && intent.filterEquals(other.intent)
        override fun hashCode(): Int {
            var result = intent.action?.hashCode() ?: 0
            result = 31 * result + (intent.data?.toString()?.hashCode() ?: 0)
            result = 31 * result + (intent.type?.hashCode() ?: 0)
            result = 31 * result + (intent.component?.hashCode() ?: 0)
            result = 31 * result + (intent.`package`?.hashCode() ?: 0)
            return result
        }
    }

    private inner class ServiceRecord(val name: ComponentName, val entry: android.content.pm.ServiceInfo) {
        val token: IBinder = Binder()
        var app: ProcessRecord? = null
        var created = false
        var startRequested = false
        var lastStartId = 0
        /** ServiceRecord.foregroundId/isForeground: the posted foreground notification, 0 when none. */
        var foregroundId = 0
        var isForeground = false
        val bindings = LinkedHashMap<FilterComparison, IntentBindRecord>()
        val connections = LinkedHashMap<IBinder, ConnectionRecord>()
    }

    private class IntentBindRecord(val intent: Intent) {
        var binder: IBinder? = null
        var received = false
        var requested = false
        /** The app has an active onBind for this intent (ActiveServices' IntentBindRecord.hasBound). */
        var hasBound = false
        var doRebind = false
    }

    private class ContentProviderRecord(
        val holder: ContentProviderHolder,
        /** Null for providers the system process installed in-process. */
        val app: ProcessRecord?,
    )

    private class ProviderWaiter(val packageName: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
    }

    private inner class ConnectionRecord(
        val conn: IServiceConnection,
        val client: ProcessRecord,
        val record: ServiceRecord,
        val binding: IntentBindRecord,
        val flags: Int,
    )

    private val lock = Any()
    private val processes = LinkedHashMap<IBinder, ProcessRecord>()

    /**
     * Source note: ActivityManagerService.handleApplicationCrash/handleApplicationWtf.
     * The host has no AppErrors dialog or Android process supervisor: RuntimeInit
     * terminates the client, while this service records the report and annotates
     * the eventual Binder death. System WTFs are posted without making them fatal.
     */
    override fun handleApplicationCrash(app: IBinder?, crashInfo: android.app.ApplicationErrorReport.ParcelableCrashInfo?) {
        val info = crashInfo ?: throw NullPointerException("crashInfo")
        val record = errorProcess(app)
        recordApplicationError("crash", app, record, null, info, Binder.getCallingUid(), Binder.getCallingPid())
        markCrash(record, info)
    }

    override fun handleApplicationWtf(
        app: IBinder?, tag: String?, system: Boolean,
        crashInfo: android.app.ApplicationErrorReport.ParcelableCrashInfo?, immediateCallerPid: Int,
    ): Boolean {
        val info = crashInfo ?: throw NullPointerException("crashInfo")
        val record = errorProcess(app)
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (system || immediateCallerPid == android.os.Process.myPid()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                recordApplicationError("wtf", app, record, tag, info, uid, pid)
            }
            return false
        }
        recordApplicationError("wtf", app, record, tag, info, uid, pid)
        val identity = Binder.clearCallingIdentity()
        val fatal = try {
            android.os.Build.IS_ENG || (context?.let {
                android.provider.Settings.Global.getInt(it.contentResolver,
                    android.provider.Settings.Global.WTF_IS_FATAL, 0)
            } ?: 0) != 0
        } finally { Binder.restoreCallingIdentity(identity) }
        val flags = record?.let { packageService?.getApplicationInfo(it.packageName, 0L, 0)?.flags } ?: 0
        if (fatal && record != null && flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT == 0) {
            markCrash(record, info)
            return true
        }
        return false
    }

    private fun errorProcess(app: IBinder?): ProcessRecord? = synchronized(lock) {
        val record = processes[app] ?: return@synchronized null
        val uid = Binder.getCallingUid()
        if (uid != record.uid && uid != android.os.Process.SYSTEM_UID && uid != android.os.Process.ROOT_UID) {
            throw SecurityException("Caller UID $uid cannot report errors for UID ${record.uid}")
        }
        record
    }

    private fun markCrash(record: ProcessRecord?, info: android.app.ApplicationErrorReport.CrashInfo) {
        if (record == null) return
        synchronized(lock) {
            record.exitReason = android.app.ApplicationExitInfo.REASON_CRASH
            record.exitDescription = "${info.exceptionClassName}: ${info.exceptionMessage}"
        }
    }

    private fun recordApplicationError(
        eventType: String, app: IBinder?, record: ProcessRecord?, subject: String?,
        info: android.app.ApplicationErrorReport.CrashInfo, uid: Int, pid: Int,
    ) {
        val processName = if (app == null) "system_server" else record?.processName ?: "unknown"
        val identity = Binder.clearCallingIdentity()
        try {
            val application = record?.let { packageService?.getApplicationInfo(it.packageName, 0L, 0) }
            if (eventType == "wtf") {
                com.android.server.am.EventLogTags.writeAmWtf(UserHandle.getUserId(uid), pid,
                    processName, application?.flags ?: -1, subject, info.exceptionMessage)
                com.android.internal.util.FrameworkStatsLog.write(
                    com.android.internal.util.FrameworkStatsLog.WTF_OCCURRED, uid, subject, processName, pid,
                    when {
                        record == null -> android.server.ServerProtoEnums.ERROR_SOURCE_UNKNOWN
                        record.pid == android.os.Process.myPid() -> android.server.ServerProtoEnums.SYSTEM_SERVER
                        application == null -> android.server.ServerProtoEnums.ERROR_SOURCE_UNKNOWN
                        application.isSystemApp -> android.server.ServerProtoEnums.SYSTEM_APP
                        else -> android.server.ServerProtoEnums.DATA_APP
                    })
            } else {
                com.android.server.am.EventLogTags.writeAmCrash(pid, UserHandle.getUserId(uid),
                    processName, application?.flags ?: -1, info.exceptionClassName,
                    info.exceptionMessage, info.throwFileName, info.throwLineNumber, 0)
            }
            val dropBox = context?.getSystemService(android.os.DropBoxManager::class.java) ?: return
            val processClass = when {
                record == null || record.pid == android.os.Process.myPid() -> "system_server"
                application?.isSystemApp == true || application?.isSystemExt == true -> "system_app"
                else -> "data_app"
            }
            val dropBoxTag = "${processClass}_$eventType"
            if (!dropBox.isTagEnabled(dropBoxTag)) return
            dropBox.addText(dropBoxTag, buildString {
                append("SystemUptimeMs: ").append(android.os.SystemClock.uptimeMillis()).append('\n')
                append("Process: ").append(processName).append('\n')
                if (record != null) {
                    append("PID: ").append(record.pid).append('\n')
                    append("UID: ").append(record.uid).append('\n')
                    append("Package: ").append(record.packageName).append('\n')
                }
                if (subject != null) append("Subject: ").append(subject).append('\n')
                append("Build: ").append(android.os.Build.FINGERPRINT).append('\n')
                append('\n').append(info.stackTrace)
            })
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private data class PackageConfigurationKey(val packageName: String, val userId: Int)
    /**
     * AOSP ActivityTaskManager's global configuration and PackageConfigPersister keep these
     * values and merge them when a process attaches. The hosted session has one user and only
     * exposes grammatical-gender overrides today, but keeps the ownership here so services do
     * not have to know about process records or client transactions.
     */
    private var globalGrammaticalGender = Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED
    private val packageGrammaticalGenders = HashMap<PackageConfigurationKey, Int>()

    /**
     * Sends a configuration delta (Configuration.updateFrom form) to every attached process of
     * [packageName], or to every process when it is null, as a ConfigurationChangeItem
     * transaction (AOSP ActivityTaskManagerService.updateGlobalConfiguration /
     * updatePackageConfiguration deliver configuration changes the same way).
     */
    fun scheduleConfigurationDelta(delta: android.content.res.Configuration, packageName: String?) =
        scheduleConfigurationDelta(delta) { packageName == null || it.packageName == packageName }

    /** The same delta to every process of [uid] (a package's processes share its uid). */
    fun scheduleConfigurationDeltaForUid(delta: android.content.res.Configuration, uid: Int) =
        scheduleConfigurationDelta(delta) { it.uid == uid }

    private fun scheduleConfigurationDelta(delta: android.content.res.Configuration, select: (ProcessRecord) -> Boolean) {
        val targets = synchronized(lock) { processes.values.filter(select) }
        for (record in targets) sendConfigurationDelta(record, delta)
    }

    private fun sendConfigurationDelta(record: ProcessRecord, delta: Configuration) {
        try {
            val transaction = android.app.servertransaction.ClientTransaction(record.thread)
            transaction.addTransactionItem(android.app.servertransaction.ConfigurationChangeItem(delta, android.content.Context.DEVICE_ID_DEFAULT))
            record.thread.scheduleTransaction(transaction)
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "configuration delta not delivered to ${record.processName}", e)
        }
    }

    private fun grammaticalGenderDelta(gender: Int) = Configuration().apply {
        setGrammaticalGender(gender)
    }

    /**
     * Applies and remembers the app-specific override represented by
     * ActivityTaskManagerInternal.PackageConfigurationUpdater.setGrammaticalGender().
     * NOT_SPECIFIED clears the persisted package override after delivering that delta to a
     * currently running process, just as PackageConfigPersister does.
     */
    fun setPackageGrammaticalGender(packageName: String, userId: Int, gender: Int): Boolean {
        if (userId != UserHandle.USER_SYSTEM || (packageService?.getPackageUidInternal(packageName) ?: -1) < 0) {
            return false
        }
        synchronized(lock) {
            val key = PackageConfigurationKey(packageName, userId)
            if (gender == Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) {
                packageGrammaticalGenders.remove(key)
            } else {
                packageGrammaticalGenders[key] = gender
            }
        }
        scheduleConfigurationDelta(grammaticalGenderDelta(gender)) { it.packageName == packageName }
        return true
    }

    /** Applies a global grammatical-gender delta, then restores every package override. */
    fun setGlobalGrammaticalGender(userId: Int, gender: Int) {
        if (userId != UserHandle.USER_SYSTEM) return
        val targets = synchronized(lock) {
            globalGrammaticalGender = gender
            processes.values.map { record ->
                record to packageGrammaticalGenders[PackageConfigurationKey(record.packageName, userId)]
            }
        }
        val global = grammaticalGenderDelta(gender)
        for ((record, packageGender) in targets) {
            sendConfigurationDelta(record, global)
            if (packageGender != null) {
                sendConfigurationDelta(record, grammaticalGenderDelta(packageGender))
            }
        }
    }

    /** Replays the global value and then the package override for a newly attached process. */
    private fun replayConfigurationOverrides(record: ProcessRecord) {
        val (global, packageGender) = synchronized(lock) {
            globalGrammaticalGender to
                packageGrammaticalGenders[PackageConfigurationKey(record.packageName, UserHandle.USER_SYSTEM)]
        }
        if (global != Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) {
            sendConfigurationDelta(record, grammaticalGenderDelta(global))
        }
        if (packageGender != null) {
            sendConfigurationDelta(record, grammaticalGenderDelta(packageGender))
        }
    }
    private val servicesByComponent = LinkedHashMap<ComponentName, ServiceRecord>()
    private val servicesByToken = LinkedHashMap<IBinder, ServiceRecord>()
    /** Bind/start operations waiting for a package's process to attach. */
    private val pendingByPackage = LinkedHashMap<String, MutableList<() -> Unit>>()
    /** Activity launches wait for the manifest-selected process, not merely the package. */
    private val pendingByProcess = LinkedHashMap<Pair<String, String>, MutableList<() -> Unit>>()
    /** Published content providers, one record per declared authority
     * (source note: ActivityManagerService.mProviderMap). */
    private val providersByAuthority = LinkedHashMap<String, ContentProviderRecord>()
    /** getContentProvider calls blocked until the owning package publishes. */
    private val providerWaiters = LinkedHashMap<String, MutableList<ProviderWaiter>>()
    @Volatile var processLauncher: ProcessLauncher? = null
    /** Foreground-service notifications are posted and cancelled through the hosted notification service. */
    @Volatile var notificationService: HostedNotificationManagerService? = null
    /** Process attach (foreground = true) and death, for the usage stats service (UsageStatsService's activity events). */
    @Volatile var processObserver: ((packageName: String, uid: Int, foreground: Boolean) -> Unit)? = null
    /** The session's package manager; consulted for APK paths of packages with no running process. */
    @Volatile var packageService: HostedPackageManagerService? = null
    @Volatile var platformCompat: com.android.server.compat.PlatformCompat? = null
    /** Activity/task ownership is separate in AOSP; this is the host seam between AMS and ATMS. */
    @Volatile internal var activityTaskManager: HostedActivityTaskManagerService? = null

    // ------------------------------------------------------------- attach

    /** uid -> (package, process name) announced through IHostedInstaller.bindProcessPackage
     * (AOSP learns both from the zygote fork it requested; no zygote here). */
    private data class ProcessAnnouncement(
        val packageName: String,
        val processName: String,
        val configuration: Configuration?,
    )
    private val announced = HashMap<Int, ProcessAnnouncement>()

    internal fun noteProcessPackage(uid: Int, packageName: String, processName: String,
        configuration: Configuration? = null) {
        synchronized(lock) {
            announced[uid] = ProcessAnnouncement(packageName, processName, configuration?.let(::Configuration))
        }
    }

    override fun attachApplication(app: IApplicationThread?, startSeq: Long) {
        val thread = checkNotNull(app) { "attachApplication without a thread" }
        val callingUid = Binder.getCallingUid()
        val announcement = synchronized(lock) { announced[callingUid] }
            ?: if (callingUid == android.os.Process.SYSTEM_UID) ProcessAnnouncement("android", "system_server", null)
            else throw SecurityException("uid " + callingUid + " attached without announcing its package")
        val packageName = announcement.packageName
        val processName = announcement.processName
        val record = ProcessRecord(
            thread, Binder.getCallingPid(), callingUid, packageName, processName,
        )
        val drained: List<() -> Unit>
        // Insert before linking so a death in the window between the two runs
        // handleProcessDied against a record that is actually registered.
        synchronized(lock) {
            processes[record.binder] = record
            drained = buildList {
                addAll(pendingByPackage.remove(packageName) ?: emptyList())
                addAll(pendingByProcess.remove(packageName to processName) ?: emptyList())
            }
        }
        try {
            record.binder.linkToDeath(record, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "process died before attach completed: " + packageName)
            handleProcessDied(record)
            return
        }
        Log.i(TAG, "attached " + packageName + " pid=" + record.pid + " uid=" + record.uid)
        // ActivityManagerService.attachApplicationLocked sends bindApplication
        // before ATMS launch transactions or pending service/broadcast work.
        val identity = Binder.clearCallingIdentity()
        try {
            if (record.uid != android.os.Process.SYSTEM_UID || record.pid != android.os.Process.myPid()) {
                bindApplication(record, announcement)
            }
        } catch (e: Exception) {
            handleProcessDied(record)
            throw e
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        replayConfigurationOverrides(record)
        activityTaskManager?.onProcessAttached(
            record.thread,
            record.pid,
            record.uid,
            record.packageName,
            record.processName,
        )
        if (callingUid != android.os.Process.SYSTEM_UID) processObserver?.invoke(packageName, callingUid, true)
        // Replay bind/start operations that were waiting for this process. Each
        // op re-locks internally and performs its Binder callbacks lock-free.
        for (op in drained) op()
    }

    private fun bindApplication(record: ProcessRecord, announcement: ProcessAnnouncement) {
        val packages = checkNotNull(packageService) { "package service is not configured" }
        val userId = UserHandle.getUserId(record.uid)
        val appInfo = checkNotNull(packages.getApplicationInfo(record.packageName,
            android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES.toLong(), userId)) {
            "No application metadata for ${record.packageName}"
        }
        // ContentProviderHelper.generateApplicationProvidersLocked uses the
        // package manager's enabled, process/UID-scoped and initOrder-sorted query.
        val providerFlags = android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES or
            android.content.pm.PackageManager.GET_URI_PERMISSION_PATTERNS or
            android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO
        val providers = packages.queryContentProviders(record.processName, record.uid,
            providerFlags.toLong(), null).list
        val configuration = Configuration(announcement.configuration ?: checkNotNull(context).resources.configuration)
        val compatibility = android.content.res.CompatibilityInfo(appInfo,
            configuration.screenLayout, configuration.smallestScreenWidthDp, false)
        platformCompat?.resetReporting(appInfo)
        record.thread.bindApplication(
            record.processName, appInfo, null, null, false,
            android.content.pm.ProviderInfoList.fromList(providers),
            null, null, null, null, null, 0, false, false, false,
            appInfo.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT != 0,
            configuration, compatibility, null, android.os.Bundle(), android.os.Build.UNKNOWN,
            null, null, platformCompat?.getDisabledChanges(appInfo),
            platformCompat?.getLoggableChanges(appInfo), null, null,
            android.os.SystemClock.elapsedRealtime(), android.os.SystemClock.uptimeMillis())
    }

    private fun handleProcessDied(record: ProcessRecord) {
        val notify = ArrayList<Pair<ConnectionRecord, ServiceRecord>>()
        synchronized(lock) {
            if (processes.remove(record.binder) == null) return
            processExits.addFirst(android.app.ApplicationExitInfo().apply {
                pid = record.pid
                realUid = record.uid
                packageUid = record.uid
                definingUid = record.uid
                processName = record.processName
                packageName = record.packageName
                reason = record.exitReason
                description = record.exitDescription
                importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                timestamp = System.currentTimeMillis()
            })
            while (processExits.size > 64) processExits.removeLast()
            // Services hosted by the dead process: tell every connection (ActiveServices.killServicesLocked).
            val dead = servicesByComponent.values.filter { it.app === record }
            for (service in dead) {
                for (connection in service.connections.values) notify.add(connection to service)
                servicesByComponent.remove(service.name)
                servicesByToken.remove(service.token)
                if (service.isForeground && service.foregroundId != 0) {
                    notificationService?.cancelForegroundServiceNotification(service.name.packageName, service.foregroundId)
                }
            }
            // Connections owned by the dead process are dropped.
            for (service in servicesByComponent.values) {
                val owned = service.connections.values.filter { it.client === record }
                for (connection in owned) removeConnectionLocked(connection)
            }
            // Provider records the dead process published, and waiters that
            // were counting on that package publishing.
            providersByAuthority.entries.removeAll { it.value.app === record }
            for (waiters in providerWaiters.values) {
                val failed = waiters.filter { it.packageName == record.packageName }
                for (waiter in failed) waiter.latch.countDown()
            }
        }
        for ((connection, service) in notify) {
            try {
                connection.conn.connected(service.name, null, true)
            } catch (e: RemoteException) {
                // The client is gone too; its own death handling cleans up.
            }
        }
        Log.i(TAG, "process died: " + record.packageName + " pid=" + record.pid)
        activityTaskManager?.onProcessDied(record.binder)
        processObserver?.invoke(record.packageName, record.uid, false)
    }

    internal data class AttachedProcess(
        val thread: IApplicationThread,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
    )

    internal fun attachedProcess(thread: IApplicationThread?): AttachedProcess? = synchronized(lock) {
        val record = thread?.asBinder()?.let(processes::get) ?: return@synchronized null
        AttachedProcess(record.thread, record.pid, record.uid, record.packageName, record.processName)
    }

    internal fun attachedProcessForPackage(
        packageName: String,
        processName: String? = null,
    ): AttachedProcess? = synchronized(lock) {
        val record = processes.values.firstOrNull {
            it.packageName == packageName && (processName == null || it.processName == processName)
        }
            ?: return@synchronized null
        AttachedProcess(record.thread, record.pid, record.uid, record.packageName, record.processName)
    }

    /**
     * ActivityTaskSupervisor.startSpecificActivity: run immediately in an attached process, or
     * retain the launch while AMS starts the package process and replay it after attach.
     */
    internal fun withApplicationThread(
        packageName: String,
        processName: String? = null,
        spawnIfMissing: Boolean = true,
        action: (AttachedProcess) -> Unit,
    ): Boolean {
        var attached: AttachedProcess? = null
        val accepted = synchronized(lock) {
            val record = processes.values.firstOrNull {
                it.packageName == packageName &&
                    (processName == null || it.processName == processName)
            }
            if (record != null) {
                attached = AttachedProcess(
                    record.thread, record.pid, record.uid, record.packageName, record.processName,
                )
                true
            } else if (!spawnIfMissing) {
                // The production launcher starts service-only, windowless app processes. An
                // activity start must not report success after routing its target to that host.
                false
            } else {
                val replay = {
                    attachedProcessForPackage(packageName, processName)?.let(action)
                    Unit
                }
                val pending = if (processName == null) {
                    pendingByPackage.getOrPut(packageName) { ArrayList() }
                } else {
                    pendingByProcess.getOrPut(packageName to processName) { ArrayList() }
                }
                pending.add(replay)
                if (bringUpProcessLocked(packageName)) {
                    true
                } else {
                    pending.remove(replay)
                    if (processName == null && pending.isEmpty()) {
                        pendingByPackage.remove(packageName)
                    } else if (processName != null && pending.isEmpty()) {
                        pendingByProcess.remove(packageName to processName)
                    }
                    false
                }
            }
        }
        if (accepted) attached?.let(action)
        return accepted
    }

    internal fun updateGlobalConfiguration(values: Configuration) {
        scheduleConfigurationDelta(values, null)
    }

    internal fun sendActivityIntentSender(
        target: android.content.IIntentSender?,
        fillIn: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        flagsMask: Int,
        flagsValues: Int,
        options: android.os.Bundle?,
    ): Int = pendingIntents.sendActivity(
        target,
        fillIn,
        resolvedType,
        resultTo,
        resultWho,
        requestCode,
        flagsMask,
        flagsValues,
        options,
    )

    // ---------------------------------------------------------- resolution

    /** Resolves a service intent through the package manager's real filters,
     * then finds the attached process hosting that package. */
    private fun resolveServiceLocked(service: Intent, callingPackage: String?): Pair<ProcessRecord, android.content.pm.ServiceInfo>? {
        val info = packageService?.resolveServiceInfo(service) ?: return null
        val process = processes.values.firstOrNull { it.packageName == info.packageName } ?: return null
        return process to info
    }

    /** The package that would host this service intent, whether or not attached. */
    private fun targetPackageForLocked(service: Intent): String? {
        service.component?.let { return it.packageName }
        return packageService?.resolveServiceInfo(service)?.packageName
    }

    /** Not attached: spawn the package's process; the pending op replays on attach. Requires `lock`. */
    private fun bringUpProcessLocked(packageName: String): Boolean {
        val launcher = processLauncher ?: return false
        val apkPath = packageService?.getApkPathInternal(packageName) ?: return false
        return launcher.launch(packageName, apkPath)
    }

    /**
     * Access gate before a bind/start reaches a service (source note:
     * ActiveServices bind permission + exported check). A non-exported service
     * is reachable only from its own uid (or system); a declared permission
     * must be held by the caller.
     */
    private fun checkAccessLocked(
        entry: android.content.pm.ServiceInfo,
        ownerUid: Int,
        callingUid: Int,
    ): Boolean {
        if (!entry.exported && callingUid != ownerUid && callingUid != android.os.Process.SYSTEM_UID) {
            return false
        }
        val permission = entry.permission ?: return true
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == ownerUid) return true
        return packageService?.checkUidPermission(permission, callingUid) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun serviceForLocked(process: ProcessRecord, entry: android.content.pm.ServiceInfo): ServiceRecord {
        val name = ComponentName(process.packageName, entry.name)
        return servicesByComponent.getOrPut(name) {
            ServiceRecord(name, entry).also {
                it.app = process
                servicesByToken[it.token] = it
            }
        }
    }

    // ------------------------------------------------------------ services

    // status: 1 running, 0 unresolved, -1 denied, 2 pending a spawn.
    private class PostStart(val schedule: (() -> Unit)?, val name: ComponentName?, val status: Int)

    override fun startService(
        caller: IApplicationThread?,
        service: Intent?,
        resolvedType: String?,
        requireForeground: Boolean,
        callingPackage: String?,
        callingFeatureId: String?,
        userId: Int,
    ): ComponentName? {
        service ?: return null
        val callingUid = Binder.getCallingUid()
        val post = synchronized(lock) { doStartLocked(callingUid, service, callingPackage) }
        if (post.status == -1) throw SecurityException("startService denied")
        post.schedule?.invoke()
        return post.name
    }

    private fun doStartLocked(
        callingUid: Int,
        service: Intent,
        callingPackage: String?,
        allowSpawn: Boolean = true,
    ): PostStart {
        val resolved = resolveServiceLocked(service, callingPackage) ?: run {
            val pkg = targetPackageForLocked(service)
            if (allowSpawn && pkg != null && bringUpProcessLocked(pkg)) {
                pendingByPackage.getOrPut(pkg) { ArrayList() }.add {
                    // allowSpawn=false on replay: the process has attached, so a
                    // still-unresolved service is genuinely absent, not un-spawned.
                    val p = synchronized(lock) { doStartLocked(callingUid, service, callingPackage, false) }
                    p.schedule?.invoke()
                }
                val name = service.component
                    ?: packageService?.resolveServiceInfo(service)
                        ?.let { ComponentName(it.packageName, it.name) }
                return PostStart(null, name, 2)
            }
            Log.w(TAG, "startService: no process hosts " + service)
            return PostStart(null, null, 0)
        }
        val (process, entry) = resolved
        if (!checkAccessLocked(entry, process.uid, callingUid)) return PostStart(null, null, -1)
        val record = serviceForLocked(process, entry)
        record.startRequested = true
        val startId = ++record.lastStartId
        val create = !record.created
        record.created = true
        return PostStart({
            if (create) process.thread.scheduleCreateService(record.token, entry, null, 0)
            process.thread.scheduleServiceArgs(
                record.token,
                android.content.pm.ParceledListSlice(listOf(android.app.ServiceStartArgs(false, startId, 0, service))),
            )
        }, record.name, 1)
    }

    override fun stopService(caller: IApplicationThread?, service: Intent?, resolvedType: String?, userId: Int): Int {
        service ?: return 0
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val component = service.component
                ?: resolveServiceLocked(service, null)?.let { ComponentName(it.first.packageName, it.second.name) }
                ?: return 0
            val record = servicesByComponent[component] ?: return 0
            record.startRequested = false
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        return 1
    }

    override fun stopServiceToken(className: ComponentName?, token: IBinder?, startId: Int): Boolean {
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val record = servicesByComponent[className ?: return false] ?: return false
            if (startId >= 0 && startId != record.lastStartId) return false
            record.startRequested = false
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        return true
    }

    // status: 1 bound/pending-in-process, 0 unresolved, -1 denied, 2 pending a spawn.
    private class PostBind(
        val schedule: (() -> Unit)?,
        val name: ComponentName?,
        val deliver: Boolean,
        val deliverBinder: IBinder?,
        val status: Int,
    )

    override fun bindServiceInstance(
        caller: IApplicationThread?,
        token: IBinder?,
        service: Intent?,
        resolvedType: String?,
        connection: IServiceConnection?,
        flags: Long,
        instanceName: String?,
        callingPackage: String?,
        userId: Int,
    ): Int {
        service ?: return 0
        connection ?: return 0
        val flags = flags.toInt()
        val callingUid = Binder.getCallingUid()
        val post = synchronized(lock) {
            val client = caller?.asBinder()?.let { processes[it] } ?: run {
                Log.w(TAG, "bindService from an unattached process")
                return 0
            }
            doBindLocked(client, callingUid, service, connection, flags, callingPackage)
        }
        if (post.status == -1) return -1
        applyPostBind(post, connection)
        return if (post.status == 0) 0 else 1
    }

    private fun doBindLocked(
        client: ProcessRecord,
        callingUid: Int,
        service: Intent,
        connection: IServiceConnection,
        flags: Int,
        callingPackage: String?,
        allowSpawn: Boolean = true,
    ): PostBind {
        val resolved = resolveServiceLocked(service, callingPackage) ?: run {
            // ActiveServices.bindServiceLocked only brings a process up for
            // BIND_AUTO_CREATE; the connection is retained and completed when
            // the spawned process attaches.
            val autoCreate = (flags and android.content.Context.BIND_AUTO_CREATE) != 0
            val pkg = targetPackageForLocked(service)
            if (allowSpawn && autoCreate && pkg != null && bringUpProcessLocked(pkg)) {
                Log.i(TAG, "bindService: spawning host for " + pkg)
                pendingByPackage.getOrPut(pkg) { ArrayList() }.add {
                    // allowSpawn=false on replay (process now attached).
                    val p = synchronized(lock) {
                        doBindLocked(client, callingUid, service, connection, flags, callingPackage, false)
                    }
                    if (p.status != -1) applyPostBind(p, connection)
                }
                return PostBind(null, null, false, null, 2)
            }
            Log.w(TAG, "bindService: no process hosts " + service)
            return PostBind(null, null, false, null, 0)
        }
        val (process, entry) = resolved
        if (!checkAccessLocked(entry, process.uid, callingUid)) return PostBind(null, null, false, null, -1)
        val record = serviceForLocked(process, entry)
        val comparison = FilterComparison(service)
        val binding = record.bindings.getOrPut(comparison) { IntentBindRecord(service) }
        record.connections[connection.asBinder()] =
            ConnectionRecord(connection, client, record, binding, flags)
        val autoCreate = (flags and android.content.Context.BIND_AUTO_CREATE) != 0
        val create = autoCreate && !record.created
        if (create) record.created = true
        if (binding.received) {
            // Already published; deliver the retained binder (null -> onNullBinding)
            // and, if the service asked to rebind on the previous unbind, schedule it.
            var schedule: (() -> Unit)? = null
            if (binding.doRebind && !binding.requested && record.created) {
                binding.requested = true
                binding.doRebind = false
                schedule = { process.thread.scheduleBindService(record.token, binding.intent, true, 0, 0) }
            }
            return PostBind(schedule, record.name, true, binding.binder, 1)
        }
        if (!binding.requested && record.created) {
            binding.requested = true
            val rebind = binding.doRebind
            binding.doRebind = false
            return PostBind({
                if (create) process.thread.scheduleCreateService(record.token, entry, null, 0)
                process.thread.scheduleBindService(record.token, binding.intent, rebind, 0, 0)
            }, record.name, false, null, 1)
        }
        // Bound but the service is not up yet (no BIND_AUTO_CREATE): waits for creation.
        return PostBind(null, record.name, false, null, 1)
    }

    private fun applyPostBind(post: PostBind, connection: IServiceConnection) {
        post.schedule?.invoke()
        if (post.deliver) {
            try {
                connection.connected(post.name, post.deliverBinder, false)
            } catch (e: RemoteException) {
                // client gone; its death handling cleans up
            }
        }
    }

    override fun unbindService(connection: IServiceConnection?): Boolean {
        connection ?: return false
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val record = servicesByComponent.values.firstOrNull { it.connections.containsKey(connection.asBinder()) }
                ?: return false
            val connectionRecord = record.connections[connection.asBinder()] ?: return false
            removeConnectionLocked(connectionRecord)
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        return true
    }

    /** ActiveServices.removeConnectionLocked: last connection on a binding unbinds it. */
    private fun removeConnectionLocked(connection: ConnectionRecord) {
        val record = connection.record
        record.connections.remove(connection.conn.asBinder())
        val binding = connection.binding
        val bindingInUse = record.connections.values.any { it.binding === binding }
        if (!bindingInUse && binding.hasBound) {
            binding.hasBound = false
            binding.requested = false
            // Keep binding.binder and binding.received: ActiveServices retains
            // the published binding in IntentBindRecord so a later bind delivers
            // it immediately and, if onUnbind returned true, schedules onRebind.
            record.app?.let { app ->
                try {
                    app.thread.scheduleUnbindService(record.token, binding.intent)
                } catch (e: RemoteException) {
                }
            }
        }
    }

    /** ActiveServices.bringDownServiceIfNeededLocked. */
    private fun bringDownIfNeededLocked(record: ServiceRecord): (() -> Unit)? {
        if (record.startRequested || record.connections.isNotEmpty()) return null
        servicesByComponent.remove(record.name)
        servicesByToken.remove(record.token)
        // bringDownServiceLocked: a foreground service's notification goes with it.
        if (record.isForeground && record.foregroundId != 0) {
            notificationService?.cancelForegroundServiceNotification(record.name.packageName, record.foregroundId)
            record.isForeground = false
        }
        val app = record.app ?: return null
        return {
            try {
                app.thread.scheduleStopService(record.token)
            } catch (e: RemoteException) {
            }
        }
    }

    override fun publishService(token: IBinder?, intent: Intent?, service: IBinder?) {
        token ?: return
        intent ?: return
        val deliveries = ArrayList<Pair<IServiceConnection, ComponentName>>()
        synchronized(lock) {
            val record = servicesByToken[token] ?: return
            val comparison = FilterComparison(intent)
            val binding = record.bindings[comparison] ?: return
            binding.binder = service
            binding.received = true
            binding.requested = false
            binding.hasBound = true
            for (connection in record.connections.values) {
                if (connection.binding === binding) deliveries.add(connection.conn to record.name)
            }
        }
        for ((conn, name) in deliveries) {
            try {
                conn.connected(name, service, false)
            } catch (e: RemoteException) {
            }
        }
    }

    override fun unbindFinished(token: IBinder?, intent: Intent?) {
        synchronized(lock) {
            val record = servicesByToken[token ?: return] ?: return
            intent ?: return
            record.bindings[FilterComparison(intent)]?.doRebind = true
        }
    }

    override fun serviceDoneExecuting(token: IBinder?, type: Int, startId: Int, res: Int, intent: Intent?) {
        // Bookkeeping only in v1 (no ANR timers, no START_NOT_STICKY policy).
    }

    // ---------------------------------------------------------- broadcasts

    private val broadcasts = HostedBroadcastQueue(this)

    // ---------------------------------------------------------- pending intents (AOSP PendingIntentController)

    private val pendingIntents = HostedPendingIntentController(this)

    override fun getIntentSenderWithFeature(
        type: Int, packageName: String?, featureId: String?, token: IBinder?, resultWho: String?,
        requestCode: Int, intents: Array<Intent?>?, resolvedTypes: Array<String?>?, flags: Int,
        options: android.os.Bundle?, userId: Int,
    ): android.content.IIntentSender? {
        val callingUid = Binder.getCallingUid()
        if (packageService?.isSameAppInternal(packageName, callingUid) != true) {
            throw SecurityException(
                "uid $callingUid is not allowed to create an IntentSender as $packageName",
            )
        }
        return pendingIntents.getIntentSender(
            type,
            packageName,
            callingUid,
            token,
            resultWho,
            requestCode,
            intents,
            resolvedTypes,
            flags,
        )
    }

    override fun getIntentSender(
        type: Int, packageName: String?, token: IBinder?, resultWho: String?, requestCode: Int,
        intents: Array<Intent?>?, resolvedTypes: Array<String?>?, flags: Int, options: android.os.Bundle?, userId: Int,
    ): android.content.IIntentSender? =
        getIntentSenderWithFeature(type, packageName, null, token, resultWho, requestCode, intents, resolvedTypes, flags, options, userId)

    override fun sendIntentSender(
        caller: IApplicationThread?, target: android.content.IIntentSender?, whitelistToken: IBinder?, code: Int,
        intent: Intent?, resolvedType: String?, finishedReceiver: android.content.IIntentReceiver?,
        requiredPermission: String?, options: android.os.Bundle?,
    ): Int {
        val record = pendingIntents.record(target) ?: return android.app.ActivityManager.START_CANCELED
        return pendingIntents.sendInner(record, code, intent, resolvedType, finishedReceiver, requiredPermission, options)
    }

    override fun cancelIntentSender(sender: android.content.IIntentSender?) =
        pendingIntents.cancel(sender, Binder.getCallingUid())

    override fun getInfoForIntentSender(sender: android.content.IIntentSender?): android.app.ActivityManager.PendingIntentInfo? {
        val record = pendingIntents.record(sender) ?: return null
        return android.app.ActivityManager.PendingIntentInfo(record.key.packageName, record.key.uid, record.immutable, record.key.type)
    }

    override fun getIntentForIntentSender(sender: android.content.IIntentSender?): Intent? {
        enforceCallingPermission(
            android.Manifest.permission.GET_INTENT_SENDER_INTENT,
            "getIntentForIntentSender()",
        )
        return pendingIntents.record(sender)?.key?.requestIntent?.let { Intent(it) }
    }

    override fun getTagForIntentSender(sender: android.content.IIntentSender?, prefix: String?): String? = null

    override fun isIntentSenderTargetedToPackage(sender: android.content.IIntentSender?): Boolean {
        val intent = pendingIntents.record(sender)?.key?.requestIntent ?: return false
        return intent.getPackage() != null || intent.component != null
    }

    override fun isIntentSenderAnActivity(sender: android.content.IIntentSender?): Boolean =
        pendingIntents.record(sender)?.key?.type == android.app.ActivityManager.INTENT_SENDER_ACTIVITY

    override fun registerIntentSenderCancelListenerEx(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?): Boolean =
        pendingIntents.registerCancelListener(sender, receiver)

    override fun unregisterIntentSenderCancelListener(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?) =
        pendingIntents.unregisterCancelListener(sender, receiver)

    override fun queryIntentComponentsForIntentSender(
        sender: android.content.IIntentSender?,
        matchFlags: Int,
    ): android.content.pm.ParceledListSlice<*>? {
        enforceCallingPermission(
            android.Manifest.permission.GET_INTENT_SENDER_INTENT,
            "queryIntentComponentsForIntentSender()",
        )
        return android.content.pm.ParceledListSlice(ArrayList<android.content.pm.ResolveInfo>())
    }

    private fun enforceCallingPermission(permission: String, operation: String) {
        val uid = Binder.getCallingUid()
        if (checkUidPermission(permission, uid) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permission Denial: $operation from uid=$uid requires $permission")
        }
    }

    /** A broadcast PendingIntent sends as its creator (PendingIntentRecord.sendInner, broadcast branch). */
    internal fun broadcastFromPendingIntent(
        packageName: String?, uid: Int, intent: Intent, requiredPermission: String?,
        finishedReceiver: android.content.IIntentReceiver?, code: Int,
    ) {
        broadcasts.broadcastIntent(intent, finishedReceiver, code, requiredPermission, finishedReceiver != null, false, packageName, uid)
    }

    /** A system service's sticky broadcast (BatteryService.sendBatteryChangedIntentLocked) on the real queue. */
    internal fun broadcastStickyFromSystem(intent: Intent) {
        broadcasts.broadcastIntent(intent, null, 0, null, false, true, "android", android.os.Process.SYSTEM_UID)
    }

    /** A service PendingIntent starts the service as its creator. */
    internal fun startServiceFromPendingIntent(packageName: String?, uid: Int, intent: Intent) {
        val post = synchronized(lock) { doStartLocked(uid, intent, packageName) }
        if (post.status == -1) throw SecurityException("startService denied")
        post.schedule?.invoke()
    }

    internal fun checkUidPermission(permission: String, uid: Int): Int =
        packageService?.checkUidPermission(permission, uid)
            ?: android.content.pm.PackageManager.PERMISSION_DENIED

    override fun checkPermission(permission: String?, pid: Int, uid: Int): Int =
        permission?.let { checkUidPermission(it, uid) }
            ?: android.content.pm.PackageManager.PERMISSION_DENIED

    override fun handleIncomingUser(callingPid: Int, callingUid: Int, userId: Int,
        allowAll: Boolean, requireFull: Boolean, name: String?, callerPackage: String?): Int =
        HostedActivityManagerInternalService(this, HostedPermissions(checkNotNull(packageService)))
            .handleIncomingUser(callingPid, callingUid, userId, allowAll,
                if (requireFull) android.app.ActivityManagerInternal.ALLOW_FULL_ONLY
                else android.app.ActivityManagerInternal.ALLOW_NON_FULL, name, callerPackage)

    override fun checkPermissionForDevice(
        permission: String?,
        pid: Int,
        uid: Int,
        deviceId: Int,
    ): Int = checkPermission(permission, pid, uid)

    // ------------------------------------------------------------ providers

    /** In-process publication for providers the system process hosts. */
    fun addSystemProvider(holder: ContentProviderHolder) {
        val released = ArrayList<ProviderWaiter>()
        synchronized(lock) {
            for (authority in holder.info.authority?.split(";") ?: return) {
                providersByAuthority[authority] = ContentProviderRecord(holder, null)
                providerWaiters.remove(authority)?.let { released.addAll(it) }
            }
        }
        for (waiter in released) waiter.latch.countDown()
    }

    /** Access gate for acquiring a provider (the per-call gate lives in the
     * provider's Transport; source note ActivityManagerService.checkContentProviderPermission). */
    private fun checkProviderAccess(info: android.content.pm.ProviderInfo, callingUid: Int) {
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == 0) return
        val ownerUid = packageService?.getPackageUidInternal(info.packageName) ?: -1
        if (ownerUid == callingUid) return
        // ContentProviderHelper also accepts an authority with an existing URI grant;
        // the provider Transport checks the exact URI and modes on each operation.
        if (uriGrants().checkAuthorityGrants(callingUid, info, UserHandle.getUserId(info.applicationInfo.uid), true)) return
        if (!info.exported) {
            throw SecurityException(
                "provider " + info.authority + " from " + info.packageName + " is not exported to uid " + callingUid,
            )
        }
        val read = info.readPermission
        val write = info.writePermission
        if (read == null && write == null) return
        val granted = { permission: String? ->
            permission != null && packageService?.checkUidPermission(permission, callingUid) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted(read) || granted(write)) return
        if (read == null || write == null) return
        throw SecurityException(
            "uid " + callingUid + " holds neither " + read + " nor " + write + " for provider " + info.authority,
        )
    }

    override fun getContentProvider(
        caller: IApplicationThread?,
        callingPackage: String?,
        name: String?,
        userId: Int,
        stable: Boolean,
    ): ContentProviderHolder? {
        val authority = name ?: return null
        val callingUid = Binder.getCallingUid()
        val waiter: ProviderWaiter
        synchronized(lock) {
            providersByAuthority[authority]?.let {
                checkProviderAccess(it.holder.info, callingUid)
                return it.holder
            }
            val info = packageService?.resolveContentProvider(authority, 0L, 0) ?: return null
            checkProviderAccess(info, callingUid)
            // The owning package has published nothing yet: make sure its
            // process is coming up, then wait for publishContentProviders.
            if (processes.values.none { it.packageName == info.packageName }) {
                if (!bringUpProcessLocked(info.packageName)) return null
            }
            waiter = ProviderWaiter(info.packageName)
            providerWaiters.getOrPut(authority) { ArrayList() }.add(waiter)
        }
        // Wait outside the lock; publish/death arrive on other binder threads.
        val published = waiter.latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(lock) {
            providerWaiters[authority]?.remove(waiter)
            if (!published) Log.w(TAG, "timed out waiting for provider " + authority)
            val record = providersByAuthority[authority] ?: return null
            checkProviderAccess(record.holder.info, callingUid)
            return record.holder
        }
    }

    override fun publishContentProviders(caller: IApplicationThread?, providers: MutableList<ContentProviderHolder?>?) {
        if (providers == null) return
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val released = ArrayList<ProviderWaiter>()
        synchronized(lock) {
            val app = processes.values.firstOrNull { it.pid == callingPid && it.uid == callingUid }
            if (app == null && callingUid != android.os.Process.SYSTEM_UID) {
                // A null ProcessRecord is the system-installed sentinel (never
                // swept by process death); an unattached app peer must not
                // publish records that would outlive it.
                Log.w(TAG, "unattached uid " + callingUid + " pid " + callingPid + " may not publish providers")
                return
            }
            for (holder in providers) {
                if (holder?.provider == null) continue
                // A process may only publish authorities of its own package
                // (same interim identity model as attachApplication).
                val ownerUid = packageService?.getPackageUidInternal(holder.info.packageName) ?: -1
                if (callingUid != android.os.Process.SYSTEM_UID && ownerUid != callingUid) {
                    Log.w(TAG, "uid " + callingUid + " may not publish providers of " + holder.info.packageName)
                    continue
                }
                for (authority in holder.info.authority?.split(";") ?: continue) {
                    providersByAuthority[authority] = ContentProviderRecord(holder, app)
                    providerWaiters.remove(authority)?.let { released.addAll(it) }
                }
            }
        }
        for (waiter in released) waiter.latch.countDown()
    }

    override fun registerReceiverWithFeature(
        caller: IApplicationThread?,
        callerPackage: String?,
        callingFeatureId: String?,
        receiverId: String?,
        receiver: android.content.IIntentReceiver?,
        filter: android.content.IntentFilter?,
        requiredPermission: String?,
        userId: Int,
        flags: Int,
    ): Intent? {
        filter ?: return null
        val notExported = (flags and android.content.Context.RECEIVER_NOT_EXPORTED) != 0
        return broadcasts.registerReceiver(
            receiver, caller?.asBinder(), filter, callerPackage, Binder.getCallingUid(), requiredPermission, notExported,
        )
    }

    override fun unregisterReceiver(receiver: android.content.IIntentReceiver?) {
        broadcasts.unregisterReceiver(receiver ?: return)
    }

    override fun broadcastIntentWithFeature(
        caller: IApplicationThread?,
        callingFeatureId: String?,
        intent: Intent?,
        resolvedType: String?,
        resultTo: android.content.IIntentReceiver?,
        resultCode: Int,
        resultData: String?,
        map: android.os.Bundle?,
        requiredPermissions: Array<String?>?,
        excludePermissions: Array<String?>?,
        excludePackages: Array<String?>?,
        appOp: Int,
        options: android.os.Bundle?,
        serialized: Boolean,
        sticky: Boolean,
        userId: Int,
    ): Int {
        intent ?: return -1
        val callingPackage = synchronized(lock) { caller?.asBinder()?.let { processes[it] }?.packageName }
        return broadcasts.broadcastIntent(
            intent, resultTo, resultCode, requiredPermissions?.firstOrNull(), serialized, sticky,
            callingPackage, Binder.getCallingUid(), caller?.asBinder(),
            android.app.BroadcastOptions.fromBundleNullable(options)?.isShareIdentityEnabled == true,
            resultData, map,
        )
    }

    override fun finishReceiver(
        who: IBinder?,
        resultCode: Int,
        resultData: String?,
        map: android.os.Bundle?,
        abortBroadcast: Boolean,
        flags: Int,
    ) {
        broadcasts.finishReceiver(who ?: return, resultCode, resultData, map, abortBroadcast)
    }

    override fun unbroadcastIntent(caller: IApplicationThread?, intent: Intent?, userId: Int) {
        broadcasts.removeSticky(intent ?: return)
    }

    // ------------------------------------------------- process/user queries

    /** AOSP AMS read operations over the hosted process registry and single-user session. */
    override fun getCurrentUserId(): Int = UserHandle.USER_SYSTEM
    override fun isUserAMonkey(): Boolean = false // No Monkey or activity-controller mode.

    internal fun uidProcessState(uid: Int): Int = synchronized(lock) {
        if (processes.values.any { it.uid == uid }) android.app.ActivityManager.PROCESS_STATE_TOP
        else android.app.ActivityManager.PROCESS_STATE_NONEXISTENT
    }

    override fun getUidProcessState(uid: Int, callingPackage: String?): Int {
        val permissions = HostedPermissions(checkNotNull(packageService))
        permissions.enforceCallerPackage(callingPackage, "getUidProcessState")
        // AppOps usage-stats grants are not hosted yet; retain the manifest permission path.
        permissions.enforce(android.Manifest.permission.PACKAGE_USAGE_STATS, "getUidProcessState")
        if (UserHandle.getUserId(uid) != UserHandle.getCallingUserId()) {
            permissions.enforce(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "getUidProcessState")
        }
        return uidProcessState(uid)
    }

    override fun getMyMemoryState(outState: android.app.ActivityManager.RunningAppProcessInfo?) {
        requireNotNull(outState) { "outState is null" }
        checkNotIsolated("getMyMemoryState")
        synchronized(lock) {
            val process = processes.values.firstOrNull { it.pid == Binder.getCallingPid() } ?: return
            // ProcessList.fillInProcMemInfoLOSP: only memory-state fields are written.
            outState.pid = process.pid
            outState.uid = process.uid
            outState.processState = android.app.ActivityManager.PROCESS_STATE_TOP
            outState.importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            outState.lru = 0
            outState.lastTrimLevel = 0
            outState.importanceReasonCode = android.app.ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN
        }
    }

    private fun checkNotIsolated(operation: String) {
        if (android.os.Process.isIsolatedUid(Binder.getCallingUid())) {
            throw SecurityException("Isolated process not allowed to call $operation")
        }
    }

    override fun getHistoricalProcessExitReasons(
        packageName: String?, pid: Int, maxNum: Int, userId: Int,
    ): android.content.pm.ParceledListSlice<android.app.ApplicationExitInfo> {
        checkNotIsolated("getHistoricalProcessExitReasons")
        require(userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) { "Unsupported userId" }
        val packages = checkNotNull(packageService)
        val permissions = HostedPermissions(packages)
        val caller = Binder.getCallingUid()
        if (userId != UserHandle.getUserId(caller) &&
            !permissions.has(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            permissions.enforce(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "getHistoricalProcessExitReasons")
        }
        val uid = if (packageName.isNullOrEmpty()) caller else {
            val target = if (userId == UserHandle.USER_SYSTEM) packages.getPackageUidInternal(packageName) else -1
            if (UserHandle.getAppId(target) != UserHandle.getAppId(caller)) {
                permissions.enforce(android.Manifest.permission.DUMP, "getHistoricalProcessExitReasons")
            }
            target
        }
        return android.content.pm.ParceledListSlice(synchronized(lock) {
            processExits.filter { it.packageUid == uid && (pid == 0 || it.pid == pid) &&
                (packageName.isNullOrEmpty() || it.packageName == packageName) }
                .let { if (maxNum > 0) it.take(maxNum) else it }
        })
    }

    override fun getRunningAppProcesses(): MutableList<android.app.ActivityManager.RunningAppProcessInfo?>? =
        synchronized(lock) {
            processes.values.map { p ->
                android.app.ActivityManager.RunningAppProcessInfo().apply {
                    processName = p.processName; pid = p.pid; uid = p.uid
                    importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            }.toMutableList<android.app.ActivityManager.RunningAppProcessInfo?>()
        }

    override fun getProcessesInErrorState(): MutableList<android.app.ActivityManager.ProcessErrorStateInfo?>? = null

    override fun getRunningExternalApplications(): MutableList<android.content.pm.ApplicationInfo?>? = mutableListOf()

    override fun getServices(maxNum: Int, flags: Int): MutableList<android.app.ActivityManager.RunningServiceInfo?>? =
        synchronized(lock) {
            servicesByComponent.values.take(maxNum).map { r ->
                android.app.ActivityManager.RunningServiceInfo().apply {
                    service = r.name; pid = r.app?.pid ?: 0; uid = r.app?.uid ?: 0
                    process = r.app?.processName; started = r.startRequested; clientCount = r.connections.size
                }
            }.toMutableList<android.app.ActivityManager.RunningServiceInfo?>()
        }

    override fun getMemoryInfo(outInfo: android.app.ActivityManager.MemoryInfo?) {
        outInfo ?: return
        // ProcessList.getMemoryInfo uses free + cached Linux memory. There is no hosted
        // lmkd threshold, so its policy fields are zero rather than fabricated RAM sizes.
        val memory = com.android.internal.util.MemInfoReader()
        memory.readMemInfo()
        outInfo.availMem = memory.freeSize + memory.cachedSize
        outInfo.totalMem = memory.totalSize
        outInfo.advertisedMem = memory.totalSize
        outInfo.threshold = 0
        outInfo.lowMemory = false
        outInfo.hiddenAppThreshold = 0
        outInfo.secondaryServerThreshold = 0
        outInfo.visibleAppThreshold = 0
        outInfo.foregroundAppThreshold = 0
    }

    override fun isUserRunning(userid: Int, flags: Int): Boolean = userid == android.os.UserHandle.USER_SYSTEM
    override fun getRunningUserIds(): IntArray? = intArrayOf(android.os.UserHandle.USER_SYSTEM)
    override fun getCurrentUser(): android.content.pm.UserInfo? = android.content.pm.UserInfo(
        android.os.UserHandle.USER_SYSTEM, "host",
        android.content.pm.UserInfo.FLAG_PRIMARY or android.content.pm.UserInfo.FLAG_ADMIN or android.content.pm.UserInfo.FLAG_FULL,
    )
    override fun getPackageProcessState(packageName: String?, callingPackage: String?): Int =
        android.app.ActivityManager.PROCESS_STATE_TOP
    override fun isBackgroundRestricted(packageName: String?): Boolean = false
    override fun isTopOfTask(token: IBinder?): Boolean = true
    override fun getLaunchedFromUid(activityToken: IBinder?): Int = -1
    override fun getLaunchedFromPackage(activityToken: IBinder?): String? = null
    override fun isAppFreezerSupported(): Boolean = false
    override fun isAppFreezerEnabled(): Boolean = false
    override fun enableAppFreezer(enable: Boolean): Boolean = false
    override fun registerStrictModeCallback(binder: IBinder?) {}
    override fun handleApplicationStrictModeViolation(app: IBinder?, penaltyMask: Int, crashInfo: android.os.StrictMode.ViolationInfo?) {}
    override fun addPackageDependency(packageName: String?) {}
    override fun updateServiceGroup(connection: IServiceConnection?, group: Int, importance: Int) {}
    /**
     * ActiveServices.setServiceForegroundInnerLocked: startForeground posts the
     * notification with FLAG_FOREGROUND_SERVICE (ServiceRecord.postNotification);
     * stopForeground removes it (STOP_FOREGROUND_REMOVE) or detaches it.
     */
    override fun setServiceForeground(className: ComponentName?, token: IBinder?, id: Int, notification: android.app.Notification?, flags: Int, foregroundServiceType: Int) {
        val record = synchronized(lock) { servicesByToken[token ?: return] } ?: return
        val pkg = record.name.packageName
        val uid = record.app?.uid ?: Binder.getCallingUid()
        if (id != 0) {
            requireNotNull(notification) { "null notification" }
            if (record.foregroundId != id && record.foregroundId != 0) {
                notificationService?.cancelForegroundServiceNotification(pkg, record.foregroundId)
            }
            notification.flags = notification.flags or android.app.Notification.FLAG_FOREGROUND_SERVICE
            record.foregroundId = id
            record.isForeground = true
            notificationService?.postForegroundServiceNotification(pkg, uid, Binder.getCallingPid(), id, notification)
        } else if (record.isForeground) {
            record.isForeground = false
            if (flags and android.app.Service.STOP_FOREGROUND_REMOVE != 0) {
                notificationService?.cancelForegroundServiceNotification(pkg, record.foregroundId)
            } else if (flags and android.app.Service.STOP_FOREGROUND_DETACH != 0) {
                notificationService?.detachForegroundServiceNotification(pkg, record.foregroundId)
            }
            record.foregroundId = 0
        }
    }
    override fun getForegroundServiceType(className: ComponentName?, token: IBinder?): Int = 0
    override fun refContentProvider(connection: IBinder?, stableDelta: Int, unstableDelta: Int): Boolean = true
    override fun removeContentProvider(connection: IBinder?, stable: Boolean) {}
    override fun killApplication(pkg: String?, appId: Int, userId: Int, reason: String?, exitInfoReason: Int) {}
    override fun noteWakeupAlarm(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, sourcePkg: String?, tag: String?) {}
    /** AOSP AMS pins the render thread to the app's scheduling group; no scheduler on this host. */
    override fun setRenderThread(tid: Int) {}
    override fun noteAlarmStart(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    override fun noteAlarmFinish(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    /** AOSP AMS URI entry points; the stock local grant service owns permission state. */
    private fun uriGrants(): com.android.server.uri.UriGrantsManagerInternal =
        checkNotNull(com.android.server.LocalServices.getService(com.android.server.uri.UriGrantsManagerInternal::class.java))

    private fun checkUriAccess(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int,
                               full: Boolean): Int {
        if (UserHandle.isIsolated(Binder.getCallingUid())) throw SecurityException("Isolated process cannot check URI permissions")
        if (pid == android.os.Process.myPid()) return android.content.pm.PackageManager.PERMISSION_GRANTED
        val granted = uriGrants().checkUriPermission(
            com.android.server.uri.GrantUri(userId, uri, mode), uid, mode, full)
        return if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED
    }

    override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): Int =
        checkUriAccess(uri, pid, uid, mode, userId, false)
    override fun checkContentUriPermissionFull(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int): Int =
        checkUriAccess(uri, pid, uid, mode, userId, true)
    override fun checkUriPermissions(uris: MutableList<android.net.Uri?>?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): IntArray {
        val requested = checkNotNull(uris)
        return IntArray(requested.size) { index ->
            val uri = requested[index]
            checkUriAccess(android.content.ContentProvider.getUriWithoutUserId(uri), pid, uid, mode,
                android.content.ContentProvider.getUserIdFromUri(uri, userId), false)
        }
    }

    override fun grantUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) throw SecurityException("Isolated process cannot grant URI permissions")
        val process = attachedProcess(caller) ?: throw SecurityException("Unable to find app for caller $caller")
        requireNotNull(targetPkg) { "null target" }
        com.android.internal.util.Preconditions.checkFlagsArgument(mode, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val intent = Intent().setData(android.content.ContentProvider.maybeAddUserId(uri, userId)).setFlags(mode)
        val grants = uriGrants()
        val needed = grants.checkGrantUriPermissionFromIntent(intent, process.uid, targetPkg, UserHandle.getUserId(process.uid))
        grants.grantUriPermissionUncheckedFromIntent(needed, null)
    }

    override fun revokeUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) throw SecurityException("Isolated process cannot revoke URI permissions")
        val process = attachedProcess(caller) ?: throw SecurityException("Unable to find app for caller $caller")
        if (uri == null || !Intent.isAccessUriMode(mode)) return
        if (packageService?.resolveContentProvider(uri.authority,
                (android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE or android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong(), userId) == null) return
        uriGrants().revokeUriPermission(targetPkg, process.uid, com.android.server.uri.GrantUri(userId, uri, mode), mode)
    }

    /** AOSP ContentProviderHelper: dynamic URI permissions are checked by the provider itself. */
    internal fun checkProviderUriPermission(uri: android.net.Uri, userId: Int, callingUid: Int, modeFlags: Int): Int {
        val identity = Binder.clearCallingIdentity()
        try {
            val holder = getContentProvider(null, "android", uri.authority, userId, true)
                ?: return android.content.pm.PackageManager.PERMISSION_DENIED
            val packageName = packageService?.getPackagesForUid(Binder.getCallingUid())?.firstOrNull()
                ?: return android.content.pm.PackageManager.PERMISSION_DENIED
            return holder.provider.checkUriPermission(android.content.AttributionSource(callingUid, packageName, null),
                uri, callingUid, modeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check provider URI permission for $uri", e)
            return android.content.pm.PackageManager.PERMISSION_DENIED
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private companion object {
        const val TAG = "HostedActivityManager"
    }
}
