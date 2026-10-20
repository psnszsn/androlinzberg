package dev.artzig.system

import com.android.server.am.HostActivityPendingIntents

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
    // Pinned AMS retains this read-only descriptor for the system process lifetime.
    private val applicationSharedMemoryFd by lazy {
        com.android.internal.os.ApplicationSharedMemory.getInstance().readOnlyFileDescriptor
    }
    override fun asBinder(): android.os.IBinder = this

    private val intentCreators by lazy { HostedIntentCreators(context ?: android.app.ActivityThread.currentActivityThread().systemContext) }
    private val processHistory by lazy { HostedProcessHistory((context ?: android.app.ActivityThread.currentActivityThread().systemContext).resources.getInteger(
        com.android.internal.R.integer.config_app_exit_info_history_list_size)) }

    internal fun addCreatorToken(intent: Intent?, creatorPackage: String?) =
        intentCreators.add(intent, creatorPackage)

    override fun refreshIntentCreatorToken(intent: Intent?): IBinder? {
        enforceCallingPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            "refreshIntentCreatorToken()")
        return HostedIntentCreators.refresh(requireNotNull(intent))
    }

    // AppExitInfoTracker equivalent for this hosted session. Binder death supplies no
    // wait status or tombstone, so retain REASON_UNKNOWN instead of inventing one.

    private inner class ProcessRecord(
        val thread: IApplicationThread,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
        val startSeq: Long,
        val supervised: HostedProcessStarts.Request<android.content.pm.ApplicationInfo>?,
    ) : IBinder.DeathRecipient {
        val key = supervised?.key ?: HostedProcessStarts.Key(packageName, processName, UserHandle.getUserId(uid))
        val packageDependencies = HashSet<String>()
        var ready = false
        @Volatile var removed = false
        // Legacy attachment currently reports TOP; the stock process listener will
        // update this same authority as activity/service state changes.
        @Volatile var reportedProcState = android.app.ActivityManager.PROCESS_STATE_TOP
        val binder: IBinder = thread.asBinder()
        var exitReason = android.app.ApplicationExitInfo.REASON_UNKNOWN
        var exitSubReason = android.app.ApplicationExitInfo.SUBREASON_UNKNOWN
        var exitDescription: String? = null
        var waitingToKillForTask = false
        var windowProcess: com.android.server.wm.WindowProcessController? = null
        // Legacy process configuration most recently sent through bind/deltas.
        // Stock mode obtains the authoritative snapshot from WPC through ATMS.
        var sentConfiguration: Configuration? = null
        var pendingUiClean = false
        var runningRemoteAnimation = false
        var foregroundActivities = false
        var foregroundServiceTypes = 0
        var hasClientActivities = false
        var hasOverlayUi = false
        var importanceFollowUp: Runnable? = null
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

    private data class ServiceInstance(val userId: Int, val name: ComponentName)

    private class ServiceStart(val taskRemoved: Boolean, val id: Int, val intent: Intent?) {
        var deliveryCount = 0
        var doneExecutingCount = 0
        var deliveredTime = 0L
        fun arguments(): android.app.ServiceStartArgs {
            var flags = 0
            if (deliveryCount > 1) flags = flags or android.app.Service.START_FLAG_RETRY
            if (doneExecutingCount > 0) flags = flags or android.app.Service.START_FLAG_REDELIVERY
            return android.app.ServiceStartArgs(taskRemoved, id, flags, intent)
        }
    }

    private inner class ServiceRecord(val target: BindingTarget) {
        val name get() = target.name
        val instance get() = target.instance
        val entry get() = target.info
        val token: IBinder = Binder()
        var app: ProcessRecord? = null
        var created = false
        var pending = false
        val pendingStarts = ArrayList<ServiceStart>()
        val deliveredStarts = ArrayList<ServiceStart>()
        var callStart = false
        var stopIfKilled = false
        var restartTime = 0L
        var restartDelay = 0L
        var nextRestartTime = 0L
        var crashCount = 0
        var restarter: Runnable? = null
        var startRequested = false
        var lastStartId = 0
        fun nextStartId(): Int {
            lastStartId++
            if (lastStartId < 1) lastStartId = 1
            return lastStartId
        }
        /** ServiceRecord.foregroundId/isForeground: the posted foreground notification, 0 when none. */
        var foregroundId = 0
        var foregroundNotification: android.app.Notification? = null
        var isForeground = false
        var foregroundServiceType = 0
        val bindings = LinkedHashMap<FilterComparison, IntentBindRecord>()
        val connections = ArrayList<ConnectionRecord>()
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
    ) { var dead = false }

    // ContentProviderConnection's client reference counts and opaque Binder identity.
    // OOM/zygote policy remains outside this host service; death uses the existing
    // broker-backed process cleanup path.
    private class ProviderConnection(
        val provider: ContentProviderRecord,
        val client: IBinder?,
        val clientUid: Int,
        val clientPid: Int,
    ) : Binder() {
        var stableCount = 0
        var unstableCount = 0
    }

    private class ProviderWaiter(val key: HostedProcessStarts.Key) {
        val latch = java.util.concurrent.CountDownLatch(1)
    }

    private inner class ConnectionRecord(
        val conn: IServiceConnection,
        val client: ProcessRecord,
        val record: ServiceRecord,
        val binding: IntentBindRecord,
        val flags: Long,
    )

    private val lock = Any()
    @Volatile internal var booting = true
    @Volatile internal var booted = false
    private var bootAnimationFinished = false
    private var bootCompletionRequested = false
    private var bootCompletion: (() -> Unit)? = null

    /** AMS.finishBooting / bootAnimationComplete defer final boot work until WMS enables the display.
     * SystemServices supplies the host phase transition; consume it once and invoke outside the lock.
     */
    internal fun finishBooting(complete: () -> Unit) {
        val action = synchronized(lock) {
            if (bootCompletionRequested) return
            bootCompletionRequested = true
            if (bootAnimationFinished) complete else { bootCompletion = complete; null }
        }
        action?.invoke()
    }

    internal fun finishBooting() {
        val action = synchronized(lock) {
            if (!bootAnimationFinished) return
            bootCompletion.also { bootCompletion = null }
        }
        action?.invoke()
    }

    override fun bootAnimationComplete() {
        val action = synchronized(lock) {
            bootAnimationFinished = true
            bootCompletion.also { bootCompletion = null }
        }
        action?.invoke()
    }
    internal fun windowServiceLock(): Any = lock
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
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (system || immediateCallerPid == android.os.Process.myPid()) {
            // AMS.handleApplicationWtf defers even process lookup: callers can
            // hold WM's global lock, while death cleanup holds AMS's lock and
            // waits for WM. Taking AMS's lock before posting inverts that order.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val record = errorProcess(app, uid)
                recordApplicationError("wtf", app, record, tag, info, uid, pid)
            }
            return false
        }
        val record = errorProcess(app, uid)
        recordApplicationError("wtf", app, record, tag, info, uid, pid)
        val identity = Binder.clearCallingIdentity()
        val fatal = try {
            android.os.Build.IS_ENG || (context?.let {
                android.provider.Settings.Global.getInt(it.contentResolver,
                    android.provider.Settings.Global.WTF_IS_FATAL, 0)
            } ?: 0) != 0
        } finally { Binder.restoreCallingIdentity(identity) }
        val flags = record?.let { applicationInfo(it.packageName, 0L, 0)?.flags } ?: 0
        if (fatal && record != null && flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT == 0) {
            markCrash(record, info)
            return true
        }
        return false
    }

    private fun errorProcess(app: IBinder?, uid: Int = Binder.getCallingUid()): ProcessRecord? = synchronized(lock) {
        val record = processes[app] ?: return@synchronized null
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
            val application = record?.let { applicationInfo(it.packageName, 0L, 0) }
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
        synchronized(lock) { record.sentConfiguration?.updateFrom(delta) }
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
        if (userId != UserHandle.USER_SYSTEM || (packageUid(packageName, UserHandle.USER_SYSTEM) ?: -1) < 0) {
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
    private val servicesByComponent = LinkedHashMap<ServiceInstance, ServiceRecord>()
    private val servicesByToken = LinkedHashMap<IBinder, ServiceRecord>()
    private val destroyingServices = LinkedHashMap<IBinder, ServiceRecord>()
    // ActiveServices.mServiceConnections retains every bind for a dispatcher,
    // including terminal connections until its explicit unbind/client death.
    private val serviceConnections = LinkedHashMap<IBinder, MutableList<ConnectionRecord>>()
    private var nextIsolatedServiceInstance = 1L
    private class PendingWork(val ready: () -> Unit, val failed: () -> Unit = {})
    /** Bind/start operations waiting for a package's process to attach. */
    private val pendingByPackage = LinkedHashMap<String, MutableList<PendingWork>>()
    /** Activity launches wait for the manifest-selected process, not merely the package. */
    private val pendingByProcess = LinkedHashMap<HostedProcessStarts.Key, MutableList<PendingWork>>()
    /** Published content providers, one record per declared authority
     * (source note: ActivityManagerService.mProviderMap). */
    private val providersByAuthority = LinkedHashMap<String, ContentProviderRecord>()
    private val providerConnections = ArrayList<ProviderConnection>()
    /** getContentProvider calls blocked until the owning package publishes. */
    private val providerWaiters = LinkedHashMap<String, MutableList<ProviderWaiter>>()
    @Volatile internal var processLauncher: HostedProcessStarts.Launcher<android.content.pm.ApplicationInfo>? = null
    private val isolatedUidsByStart = HashMap<Long, Pair<Int, Int>>()
    private val processStarts by lazy {
        HostedProcessStarts<android.content.pm.ApplicationInfo>(
            schedule = { delay, callback ->
                val runnable = Runnable { synchronized(lock) { callback() } }
                importanceHandler.postDelayed(runnable, delay)
                AutoCloseable { importanceHandler.removeCallbacks(runnable) }
            },
            finished = ::processStartFinishedLocked,
        )
    }

    internal fun dispatchProcessExit(callback: () -> Unit) {
        importanceHandler.post { synchronized(lock) { callback() } }
    }
    private val processObservers by lazy {
        HostedProcessObservers(com.android.server.UiThread.getHandler())
    }

    override fun registerProcessObserver(observer: android.app.IProcessObserver) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
            "registerProcessObserver()")
        processObservers.register(observer)
    }

    override fun unregisterProcessObserver(observer: android.app.IProcessObserver) {
        processObservers.unregister(observer)
    }

    private val uidObservers by lazy {
        com.android.server.am.HostedUidObservers(importanceHandler,
            { serviceConstants().getLong("background_settle_time", 60_000L) })
    }

    /** AMS allows usage AppOps or PACKAGE_USAGE_STATS, then the stock observer
     * dispatcher applies the caller's cross-user permission and UID filter. */
    private fun enforceUidObserverPermission(callingPackage: String?, operation: String) {
        val mode = appOps?.noteOperation(android.app.AppOpsManager.OP_GET_USAGE_STATS,
            Binder.getCallingUid(), callingPackage, null, false, "", false)?.opMode
            ?: android.app.AppOpsManager.MODE_DEFAULT
        if (mode != android.app.AppOpsManager.MODE_ALLOWED)
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS, operation)
    }

    override fun registerUidObserver(observer: android.app.IUidObserver?, which: Int,
        cutpoint: Int, callingPackage: String?) {
        registerUidObserverForUids(observer, which, cutpoint, callingPackage, null)
    }

    override fun registerUidObserverForUids(observer: android.app.IUidObserver?, which: Int,
        cutpoint: Int, callingPackage: String?, uids: IntArray?): IBinder {
        enforceUidObserverPermission(callingPackage, "registerUidObserver")
        return uidObservers.register(observer ?: throw NullPointerException("observer"),
            which, cutpoint, callingPackage, Binder.getCallingUid(), uids)
    }

    override fun unregisterUidObserver(observer: android.app.IUidObserver?) {
        uidObservers.unregister(observer ?: throw NullPointerException("observer"))
    }

    override fun addUidToObserver(token: IBinder?, callingPackage: String?, uid: Int) {
        enforceUidObserverPermission(callingPackage, "registerUidObserver")
        uidObservers.add(token, uid)
    }

    override fun removeUidFromObserver(token: IBinder?, callingPackage: String?, uid: Int) {
        enforceUidObserverPermission(callingPackage, "registerUidObserver")
        uidObservers.remove(token, uid)
    }

    override fun isUidActive(uid: Int, callingPackage: String?): Boolean {
        enforceUidObserverPermission(callingPackage, "isUidActive")
        return uidObservers.isActive(uid) || pendingTopUids.contains(uid)
    }

    /** Foreground-service notifications are posted and cancelled through the hosted notification service. */
    @Volatile internal var batteryService: HostedBatteryService? = null
    @Volatile var notificationService: HostedNotificationManagerService? = null
    /** Process attach (foreground = true) and death, for the usage stats service (UsageStatsService's activity events). */
    @Volatile var appOps: com.android.server.appop.AppOpsService? = null
    @Volatile var processObserver: ((packageName: String, uid: Int, foreground: Boolean) -> Unit)? = null
    /** The session's package manager; consulted for APK paths of packages with no running process. */
    @Volatile var packageService: android.content.pm.IPackageManager? = null
    // Stock ActivityManagerService resolves packages as the system: queries made
    // on an app's behalf run with the caller identity cleared, so package
    // visibility never hides an app from process and service bookkeeping.
    private fun <T> systemPackages(block: (android.content.pm.IPackageManager) -> T?): T? =
        packageService?.let { packages -> android.os.Binder.withCleanCallingIdentity<T?> { block(packages) } }
    private fun applicationInfo(packageName: String, flags: Long, userId: Int): android.content.pm.ApplicationInfo? =
        systemPackages { it.getApplicationInfo(packageName, flags, userId) }
    private fun packageUid(packageName: String, userId: Int): Int =
        systemPackages { it.getPackageUid(packageName, 0L, userId) } ?: -1
    private fun isSameApp(packageName: String?, uid: Int): Boolean = packageName != null &&
        UserHandle.getAppId(packageUid(packageName, UserHandle.getUserId(uid))) == UserHandle.getAppId(uid)
    @Volatile var platformCompat: com.android.server.compat.PlatformCompat? = null
    private val uriAccess by lazy {
        HostedUriAccess(checkNotNull(packageService)) { caller ->
            synchronized(lock) {
                val record = caller?.asBinder()?.let { processes[it] }
                    ?: throw SecurityException("Unable to find app for URI permission caller $caller")
                val uid = Binder.getCallingUid()
                if (uid != record.uid && uid != android.os.Process.SYSTEM_UID && uid != 0) {
                    throw SecurityException("URI permission caller belongs to another uid")
                }
                record.uid
            }
        }
    }

    internal fun checkProviderUriPermissionFromHost(uri: android.net.Uri, userId: Int, uid: Int, mode: Int): Int =
        uriAccess.checkProvider(uri, userId, uid, mode,
            release = { holder -> removeContentProvider(holder.connection, true) }) { authority, providerUser ->
            getContentProvider(null, "android", authority, providerUser, true)
        }

    private var stockTasks: com.android.server.wm.ActivityTaskManagerService? = null
    private val stockTaskLocal: com.android.server.wm.ActivityTaskManagerInternal
        get() = checkNotNull(com.android.server.LocalServices.getService(
            com.android.server.wm.ActivityTaskManagerInternal::class.java))
    internal val pendingTopUids = HostedPendingTopUids()
    internal val processInfo by lazy {
        HostedProcessInfoService(lock, { pid ->
            processes.values.firstOrNull { it.pid == pid && !it.removed }?.let {
                HostedProcessInfoService.Entry(it.reportedProcState, pendingTopUids.time(it.uid, pid))
            }
        }, {
            enforceCallingPermission(android.Manifest.permission.GET_PROCESS_STATE_AND_OOM_SCORE,
                "getProcessStatesAndOomScoresForPIDs()")
        })
    }

    private val importanceHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val cpuTracker by lazy { com.android.internal.os.ProcessCpuTracker(false) }
    private val cpuUpdatePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastCpuUpdate = 0L

    /** AppProfiler.updateCpuStats/updateCpuStatsNow: coalesce asynchronous samples
     * at the stock five-second minimum interval. ProcessCpuTracker owns Linux
     * parsing/deltas; only registered session PIDs enter Android UID accounting.
     * The host has no phantom-process reaper or persistent BatteryStats database. */
    internal fun updateCpuStats() {
        if (!cpuUpdatePending.compareAndSet(false, true)) return
        com.android.internal.os.BackgroundThread.getHandler().post {
            try {
                data class Sample(val pid: Int, val uptime: Long, val user: Int, val system: Int)
                val samples = synchronized(cpuTracker) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (lastCpuUpdate >= now - 5000) return@post
                    lastCpuUpdate = now
                    cpuTracker.update()
                    if (!cpuTracker.hasGoodLastStats()) return@post
                    buildList {
                        for (i in 0 until cpuTracker.countStats()) {
                            val stat = cpuTracker.getStats(i)
                            if (stat.working) add(Sample(stat.pid, stat.rel_uptime,
                                stat.rel_utime.coerceAtLeast(0), stat.rel_stime.coerceAtLeast(0)))
                        }
                    }
                }
                synchronized(lock) {
                    for (sample in samples) {
                        val record = processes.values.firstOrNull { it.pid == sample.pid } ?: continue
                        batteryService?.updateProcessCpu(record.processName, record.uid,
                            sample.user.toLong(), sample.system.toLong())
                        com.android.server.am.EventLogTags.writeAmCpu(record.pid.toLong(), record.uid.toLong(),
                            record.processName, sample.uptime, sample.user.toLong(), sample.system.toLong())
                    }
                }
            } finally { cpuUpdatePending.set(false) }
        }
    }

    /**
     * AMS.setSystemProcess / ProcessList.MyProcessMap: use the same process record
     * for AMS and stock ATMS. Host process attachment replaces zygote/ProcessList;
     * WPC owns configuration and activity membership, including the system UI context.
     */
    internal fun attachSystemWindowProcess(tasks: com.android.server.wm.ActivityTaskManagerService) {
        synchronized(lock) {
            check(stockTasks == null)
            stockTasks = tasks
            val record = checkNotNull(processes.values.singleOrNull {
                it.pid == android.os.Process.myPid() && it.uid == android.os.Process.SYSTEM_UID
            }) { "System process must attach to AMS before WMS startup" }
            attachWindowProcessLocked(record)
            record.windowProcess!!.setThread(record.thread)
        }
    }

    /** Called with the AMS lock, following AMS -> ATMS lock order. */
    private fun attachWindowProcessLocked(record: ProcessRecord) {
        val tasks = stockTasks ?: return
        check(record.windowProcess == null)
        val info = record.supervised?.metadata ?: checkNotNull(applicationInfo(record.packageName, 0L,
            UserHandle.getUserId(record.uid)))
        val persistent = record.pid == android.os.Process.myPid() &&
            record.uid == android.os.Process.SYSTEM_UID
        val listener = object : com.android.server.wm.WindowProcessListener by unsupported<com.android.server.wm.WindowProcessListener>() {
            override fun isRemoved(): Boolean = record.removed
            override fun isCached(): Boolean =
                record.reportedProcState >= android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
            override fun getCpuTime(): Long = synchronized(cpuTracker) { cpuTracker.getCpuTimeForPid(record.pid) }
            override fun appDied(reason: String) {
                synchronized(lock) { record.exitDescription = reason }
                handleProcessDied(record)
            }
            override fun setPendingUiClean(pending: Boolean): Unit = synchronized(lock) {
                record.pendingUiClean = pending
                record.windowProcess?.setPendingUiClean(pending)
            }
            override fun setPendingUiCleanAndForceProcessStateUpTo(newState: Int) = synchronized(lock) {
                if (record.removed) return@synchronized
                setPendingUiClean(true)
                if (record.reportedProcState > newState) publishProcessStateLocked(record, newState)
            }
            override fun updateServiceConnectionActivities() = synchronized(lock) {
                updateServiceClientActivitiesLocked()
            }
            override fun updateProcessInfo(updateServices: Boolean, activityChange: Boolean, updateOomAdj: Boolean) = synchronized(lock) {
                if (updateServices) updateServiceClientActivitiesLocked()
                if (record.removed) return@synchronized
                if (updateOomAdj) updateProcessImportanceLocked(record)
            }
            override fun onStartActivity(topProcessState: Int, setProfileProc: Boolean, packageName: String?, versionCode: Long) = synchronized(lock) {
                if (record.removed) return@synchronized
                if (setProfileProc) throw dev.artzig.ArtzigTestDiagnostics.unsupported(
                    "WindowProcessListener", "onStartActivity", "Activity profiling is not hosted")
                packageName?.let { record.windowProcess?.addPackage(it) }
                updateProcessImportanceLocked(record)
                setPendingUiCleanAndForceProcessStateUpTo(topProcessState)
            }
            override fun clearProfilerIfNeeded() {
                // Profiling launches are rejected before installing any profiler state.
                // AppProfiler.clearProfilerLPf has no descriptors or target to clear here.
            }
            override fun setRunningRemoteAnimation(running: Boolean): Unit = synchronized(lock) {
                if (persistent) Log.wtf(TAG, "system can't run remote animation")
                else if (!record.removed && record.runningRemoteAnimation != running) {
                    record.runningRemoteAnimation = running
                    updateProcessImportanceLocked(record)
                }
            }
            override fun dumpDebug(proto: android.util.proto.ProtoOutputStream, fieldId: Long) {
                val token = proto.start(fieldId)
                proto.write(com.android.server.am.ProcessRecordProto.PID, record.pid)
                proto.write(com.android.server.am.ProcessRecordProto.PROCESS_NAME, record.processName)
                proto.write(com.android.server.am.ProcessRecordProto.UID, record.uid)
                if (UserHandle.getAppId(record.uid) >= android.os.Process.FIRST_APPLICATION_UID) {
                    proto.write(com.android.server.am.ProcessRecordProto.USER_ID, UserHandle.getUserId(record.uid))
                    proto.write(com.android.server.am.ProcessRecordProto.APP_ID, UserHandle.getAppId(record.uid))
                }
                proto.write(com.android.server.am.ProcessRecordProto.PERSISTENT, persistent)
                proto.end(token)
            }
        }
        val controller = com.android.server.wm.WindowProcessController(tasks, info,
            record.processName, record.uid, UserHandle.getUserId(record.uid), record, listener)
        record.windowProcess = controller
        controller.addPackage(record.packageName)
        stockTaskLocal.onProcessAdded(controller)
        controller.setPersistent(persistent)
        controller.setPid(record.pid)
        record.reportedProcState = if (persistent) android.app.ActivityManager.PROCESS_STATE_PERSISTENT
            else android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY
        controller.setCurrentProcState(record.reportedProcState)
        controller.setReportedProcState(record.reportedProcState)
        stockTaskLocal.onProcessMapped(record.pid, controller)
        val uidProcesses = processes.values.filter { it.uid == record.uid && it.windowProcess != null }
        val uidState = uidProcesses.minOf { it.reportedProcState }
        if (uidProcesses.size == 1) stockTaskLocal.onUidActive(record.uid, uidState)
        else stockTaskLocal.onUidProcStateChanged(record.uid, uidState)
    }

    /** ProcessRecord's activity callbacks and OomAdjuster's activity-state order.
     * WPC supplies the ranked activity flags. Android memory scoring, cgroups and
     * LMKD are absent; Linux owns reclamation. Service binding priority inheritance
     * remains separate from these window-driven states. */
    private fun updateProcessImportanceLocked(record: ProcessRecord) {
        val controller = record.windowProcess ?: return
        if (record.removed) return
        val started = android.os.SystemClock.elapsedRealtime()
        record.importanceFollowUp?.let { importanceHandler.removeCallbacks(it) }
        record.importanceFollowUp = null
        // ActivityTaskSupervisor.updateTopResumedActivityIfNeeded retains the
        // previous top process while awaiting the next resumed activity. Android
        // normally resumes HOME; the desktop host can instead have no task at
        // all. A controller with no activities must not retain TOP indefinitely.
        val top = stockTaskLocal.topApp === controller && controller.hasActivities()
        val flags = if (controller.hasActivities()) controller.activityStateFlags else 0
        val visible = flags and com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_VISIBLE != 0
        val paused = flags and com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED != 0
        val stopping = flags and com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING != 0
        val foreground = top || visible || paused || stopping
        var state = android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY
        if (top || record.runningRemoteAnimation || visible || paused) state = stockTaskLocal.topProcessState
        else if (stopping) {
            if (flags and com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING == 0)
                state = android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        } else if (controller.hasActivities()) {
            state = android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
            val stoppedAt = controller.perceptibleTaskStoppedTimeMillis
            if (com.android.server.am.Flags.perceptibleTasks() && stoppedAt >= 0) {
                val remaining = 5 * 60 * 1000L - (android.os.SystemClock.uptimeMillis() - stoppedAt)
                state = if (remaining > 0) android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                    else android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY
                if (remaining > 0) {
                    val followUp = Runnable { synchronized(lock) { updateProcessImportanceLocked(record) } }
                    record.importanceFollowUp = followUp
                    importanceHandler.postDelayed(followUp, remaining)
                }
            }
        }
        val hostedServices = servicesByToken.values.filter { it.app === record }
        val foregroundService = hostedServices.any { it.isForeground }
        controller.setHasForegroundServices(foregroundService)
        val serviceTypes = hostedServices.filter { it.isForeground }.fold(0) { mask, service ->
            mask or service.foregroundServiceType
        }
        if (record.foregroundServiceTypes != serviceTypes) {
            record.foregroundServiceTypes = serviceTypes
            processObservers.servicesChanged(record.pid, record.uid, serviceTypes)
        }
        if (foregroundService) state = minOf(state, android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE)
        else if (hostedServices.any { it.startRequested }) state = minOf(state, android.app.ActivityManager.PROCESS_STATE_SERVICE)
        if (record.hasOverlayUi) state = minOf(state, android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
        if (record.hasClientActivities) state = minOf(state, android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT)
        if (controller.hasRecentTasks()) state = minOf(state, android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT)
        if (record.pid == android.os.Process.myPid() && record.uid == android.os.Process.SYSTEM_UID) state = android.app.ActivityManager.PROCESS_STATE_PERSISTENT
        if (record.foregroundActivities != foreground) {
            record.foregroundActivities = foreground
            processObservers.activitiesChanged(record.pid, record.uid, foreground)
        }
        publishProcessStateLocked(record, state)
        pendingTopUids.delete(record.uid, started)
        if (!record.removed && record.pendingUiClean && state >= android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
            try {
                record.thread.scheduleTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                record.pendingUiClean = false
                controller.setPendingUiClean(false)
            } catch (_: android.os.RemoteException) { handleProcessDied(record) }
        }
        killWaitingTaskProcessLocked(record)
    }

    private fun publishProcessStateLocked(record: ProcessRecord, state: Int) {
        if (record.removed || record.reportedProcState == state) return
        record.reportedProcState = state
        record.windowProcess?.setCurrentProcState(state)
        record.windowProcess?.setReportedProcState(state)
        try { record.thread.setProcessState(state) }
        catch (_: android.os.RemoteException) { handleProcessDied(record); return }
        val uidState = processes.values.filter { it.uid == record.uid }.minOf { it.reportedProcState }
        stockTaskLocal.onUidProcStateChanged(record.uid, uidState)
        publishUidState(record.uid)
    }

    /** OomAdjuster applies UID state to AppOps after aggregating its processes.
     * Stock AppOps must see WM transitions, not just process attachment/death.
     * getDefaultCapability's non-instrumented defaults are sufficient for this
     * window state update; service-specific while-in-use grants are separate.
     */
    private fun publishUidState(uid: Int) = synchronized(lock) {
        // A death notification can race a new process attachment. Snapshot all
        // remaining processes under the same lock before publishing either API.
        val alive = processes.values.filter { it.uid == uid && !it.removed }
        // OomAdjuster publishes its computed state; the temporary pending-top
        // query override is cleared immediately after this update.
        val state = alive.minOfOrNull { it.reportedProcState }
            ?: android.app.ActivityManager.PROCESS_STATE_NONEXISTENT
        val capability = when (state) {
            android.app.ActivityManager.PROCESS_STATE_PERSISTENT,
            android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI,
            android.app.ActivityManager.PROCESS_STATE_TOP -> android.app.ActivityManager.PROCESS_CAPABILITY_ALL
            android.app.ActivityManager.PROCESS_STATE_BOUND_TOP -> android.app.ActivityManager.PROCESS_CAPABILITY_BFSL
            else -> android.app.ActivityManager.PROCESS_CAPABILITY_NONE
        } or android.net.NetworkPolicyManager.getDefaultProcessNetworkCapabilities(state)
        appOps?.updateUidProcState(uid, state, capability)
        val adjustment = alive.minOfOrNull { HostedProcessInfoService.linuxOomScore(it.pid) } ?: -10000
        uidObservers.update(uid, state, capability, adjustment)
    }

    /** ActiveServices.updateServiceConnectionActivitiesLocked: only a distinct
     * live client with activities contributes to the hosting process's flag. */
    private fun updateServiceClientActivitiesLocked() {
        for (record in processes.values) {
            val controller = record.windowProcess ?: continue
            record.hasClientActivities = servicesByToken.values.any { service ->
                service.app === record && service.connections.any {
                    it.client !== record && !it.client.removed && it.client.windowProcess?.hasActivities() == true
                }
            }
            controller.setHasClientActivities(record.hasClientActivities)
        }
    }

    internal fun updateWindowProcessStates() = synchronized(lock) {
        if (stockTasks == null) return@synchronized
        updateServiceClientActivitiesLocked()
        for (record in processes.values.toList()) updateProcessImportanceLocked(record)
    }

    /** AMS.LocalService.setHasOverlayUi / ProcessStateRecord: a late window
     * callback for a dead PID is ignored; changes update WPC and importance. */
    internal fun setHasOverlayUi(pid: Int, hasOverlayUi: Boolean) = synchronized(lock) {
        val record = processes.values.firstOrNull { it.pid == pid } ?: return@synchronized
        if (record.hasOverlayUi == hasOverlayUi) return@synchronized
        record.hasOverlayUi = hasOverlayUi
        record.windowProcess?.setHasOverlayUi(hasOverlayUi)
        updateProcessImportanceLocked(record)
    }

    // ------------------------------------------------------------- attach

    /** (uid, pid) -> package/process announced through IHostedInstaller.bindProcessPackage
     * (AOSP learns both from the zygote fork it requested; no zygote here). */
    private data class ProcessAnnouncement(
        val packageName: String,
        val processName: String,
        val configuration: Configuration?,
    )
    private val announced = HashMap<Pair<Int, Int>, ProcessAnnouncement>()

    internal fun noteProcessPackage(uid: Int, packageName: String, processName: String,
        configuration: Configuration? = null) {
        synchronized(lock) {
            announced[uid to Binder.getCallingPid()] =
                ProcessAnnouncement(packageName, processName, configuration?.let(::Configuration))
        }
    }

    override fun attachApplication(app: IApplicationThread?, startSeq: Long) {
        val thread = checkNotNull(app) { "attachApplication without a thread" }
        val callingUid = Binder.getCallingUid()
        val callerIdentity = HostedProcessStarts.Identity(Binder.getCallingPid(), callingUid)
        val supervised = synchronized(lock) { processStarts.request(startSeq, callerIdentity) }
        if (startSeq != 0L && supervised == null)
            throw SecurityException("No supervised child matches PID/UID/start sequence")
        val announcement = if (supervised != null) ProcessAnnouncement(
            supervised.key.packageName, supervised.key.processName, null)
        else synchronized(lock) { announced.remove(callingUid to callerIdentity.pid) }
            ?: if (callingUid == android.os.Process.SYSTEM_UID) ProcessAnnouncement("android", "system_server", null)
            else throw SecurityException("uid " + callingUid + " attached without announcing its package")
        val packageName = announcement.packageName
        val processName = announcement.processName
        val record = ProcessRecord(
            thread, callerIdentity.pid, callingUid, packageName, processName, startSeq, supervised,
        )
        // Insert before linking so a death in the window between the two runs
        // handleProcessDied against a record that is actually registered.
        synchronized(lock) {
            if (stockTasks != null && processes.values.any {
                    it.pid == record.pid || (it.uid == record.uid && it.processName == record.processName)
                }) throw IllegalStateException("Process is already attached: ${record.processName}/${record.pid}")
            if (!processStarts.attach(record.key, startSeq, callerIdentity))
                throw SecurityException("Stale or duplicate process start: $processName/$startSeq")
            processes[record.binder] = record
            HostActivityManagerAccess.attached(record.pid, record.binder,
                com.android.server.LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)?.getPackage(packageName)?.processes?.get(processName)?.deniedPermissions)
        }
        try {
            record.binder.linkToDeath(record, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "process died before attach completed: " + packageName)
            handleProcessDied(record)
            return
        }
        Log.i(TAG, "attached " + packageName + " pid=" + record.pid + " uid=" + record.uid)
        processObservers.started(record.pid, record.uid,
            applicationInfo(record.packageName, 0L,
                UserHandle.getUserId(record.uid))?.uid ?: record.uid,
            record.packageName, record.processName)
        // ActivityManagerService.attachApplicationLocked sends bindApplication
        // before ATMS launch transactions or pending service/broadcast work.
        val identity = Binder.clearCallingIdentity()
        try {
            // AMS holds its lock across the oneway bind and makeActive. A fast
            // finishAttachApplication cannot release component work between them.
            synchronized(lock) {
                if (processes[record.binder] !== record) return
                attachWindowProcessLocked(record)
                if (record.uid != android.os.Process.SYSTEM_UID || record.pid != android.os.Process.myPid()) {
                    bindApplication(record, announcement)
                } else {
                    completeApplicationAttachment(record)
                }
            }
        } catch (e: Exception) {
            handleProcessDied(record)
            throw e
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Pinned AMS.finishAttachApplication: launch components only after Application.onCreate.
     * The host has no process-group supervisor; a stale or unauthenticated acknowledgement
     * is rejected without releasing work or signalling an unrelated desktop process. */
    override fun finishAttachApplication(startSeq: Long, timestampApplicationOnCreateNs: Long) {
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        if (pid == android.os.Process.myPid() && uid == android.os.Process.SYSTEM_UID) return
        val record = synchronized(lock) {
            processes.values.lastOrNull { it.pid == pid && it.uid == uid && it.startSeq == startSeq }
        } ?: throw SecurityException("finishAttachApplication has no matching process/start sequence")
        val identity = Binder.clearCallingIdentity()
        try {
            completeApplicationAttachment(record)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun completeApplicationAttachment(record: ProcessRecord) {
        val drained = synchronized(lock) {
            if (processes[record.binder] !== record || record.ready) return
            processStarts.ready(record.key, record.startSeq)
            record.ready = true
            buildList {
                if (!record.key.isolated) addAll(pendingByPackage.remove(record.packageName) ?: emptyList())
                addAll(pendingByProcess.remove(record.key) ?: emptyList())
                for (service in servicesByComponent.values.toList()) {
                    if (service.pending && service.target.process == record.key) bringUpServiceLocked(service)
                }
            }
        }
        if (record.windowProcess != null) {
            synchronized(lock) {
                if (processes[record.binder] !== record) return
                stockTaskLocal.attachApplication(record.windowProcess!!)
            }
        }
        replayConfigurationOverrides(record)
        publishUidState(record.uid)
        if (record.uid != android.os.Process.SYSTEM_UID) processObserver?.invoke(record.packageName, record.uid, true)
        // Replay bind/start operations that were waiting for this process. Each
        // op re-locks internally and performs its Binder callbacks lock-free.
        for (op in drained) op.ready()
    }

    private fun bindApplication(record: ProcessRecord, announcement: ProcessAnnouncement) {
        val packages = checkNotNull(packageService) { "package service is not configured" }
        val userId = UserHandle.getUserId(record.uid)
        val appInfo = record.supervised?.metadata ?: checkNotNull(applicationInfo(record.packageName,
            android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES.toLong(), userId)) {
            "No application metadata for ${record.packageName}"
        }
        // ContentProviderHelper.generateApplicationProvidersLocked uses the
        // package manager's enabled, process/UID-scoped and initOrder-sorted query.
        val providerFlags = android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES or
            android.content.pm.PackageManager.GET_URI_PERMISSION_PATTERNS or
            android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO
        @Suppress("UNCHECKED_CAST")
        val providers: List<android.content.pm.ProviderInfo> = if (record.key.isolated) emptyList() else
            systemPackages { it.queryContentProviders(record.processName, record.uid, providerFlags.toLong(), null) }
                ?.list as List<android.content.pm.ProviderInfo>? ?: emptyList()
        // Stock preBindApplication selects pending activity overrides before the
        // first client configuration. Never let a launcher geometry override ATMS.
        val preBind = synchronized(lock) {
            record.windowProcess?.let { stockTaskLocal.preBindApplication(it, appInfo) }
        }
        val configuration = preBind?.configuration ?: Configuration(
            announcement.configuration ?: checkNotNull(context).resources.configuration)
        val compatibility = preBind?.compatibilityInfo ?: android.content.res.CompatibilityInfo(appInfo,
            configuration.screenLayout, configuration.smallestScreenWidthDp, false)
        record.sentConfiguration = Configuration(configuration)
        platformCompat?.resetReporting(appInfo)
        record.thread.bindApplication(
            record.processName, appInfo, null, null, false,
            android.content.pm.ProviderInfoList.fromList(providers),
            null, null, null, null, null, 0, false, false, false,
            appInfo.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT != 0,
            configuration, compatibility, if (record.key.isolated) android.util.ArrayMap<String, IBinder>().apply {
                // Pinned AMS.getCommonServicesLocked's two explicit isolated exemptions.
                for (name in arrayOf("package", "permissionmgr"))
                    android.os.ServiceManager.getService(name)?.let { put(name, it) }
            } else null, android.os.Bundle(), android.os.Build.UNKNOWN,
            null, null, platformCompat?.getDisabledChanges(appInfo),
            platformCompat?.getLoggableChanges(appInfo), null, applicationSharedMemoryFd,
            android.os.SystemClock.elapsedRealtime(), android.os.SystemClock.uptimeMillis())
        synchronized(lock) {
            if (processes[record.binder] === record) record.windowProcess?.setThread(record.thread)
        }
    }

    private fun handleProcessDied(record: ProcessRecord) {
        synchronized(lock) {
            if (processes[record.binder] !== record) return
            record.removed = true
            pendingTopUids.died(record.uid, record.pid)
            record.importanceFollowUp?.let { importanceHandler.removeCallbacks(it) }
            record.importanceFollowUp = null
            processes.remove(record.binder)
            destroyingServices.entries.removeAll { it.value.app === record }
            processStarts.died(record.key, record.startSeq)
            processHistory.died(record.binder, android.app.ApplicationExitInfo().apply {
                pid = record.pid
                realUid = record.uid
                packageUid = record.uid
                definingUid = record.uid
                packageName = record.packageName
                packageList = arrayOf(record.packageName)
                processName = record.processName
                reason = record.exitReason
                subReason = record.exitSubReason
                description = record.exitDescription
                timestamp = System.currentTimeMillis()
                importance = android.app.ActivityManager.RunningAppProcessInfo.procStateToImportance(record.reportedProcState)
            })
            HostActivityManagerAccess.died(record.pid, record.binder)
            // AMS.cleanUpApplicationRecordLocked / handleAppDiedLocked: make
            // the thread inactive, remove both process indexes (which destroys
            // configuration listeners), then let stock task records handle death.
            record.windowProcess?.let { controller ->
                controller.setThread(null)
                stockTaskLocal.onProcessRemoved(record.processName, record.uid)
                stockTaskLocal.clearHeavyWeightProcessIfEquals(controller)
                stockTaskLocal.onCleanUpApplicationRecord(controller)
                stockTaskLocal.onProcessUnMapped(record.pid)
                stockTaskLocal.handleAppDied(controller, false, Runnable {
                    error("Instrumentation was not registered for ${record.processName}")
                })
                record.windowProcess = null
                val remaining = processes.values.filter { it.uid == record.uid && it.windowProcess != null }
                if (remaining.isEmpty()) stockTaskLocal.onUidInactive(record.uid)
                else stockTaskLocal.onUidProcStateChanged(record.uid, remaining.minOf { it.reportedProcState })
            }
            // ActiveServices.killServicesLocked first releases the dead client's
            // own bindings, then resets hosted services and retains restartable ones.
            val owned = serviceConnections.values.flatten().filter { it.client === record }
            for (connection in owned) removeConnectionLocked(connection)
            val dead = servicesByComponent.values.filter { it.app === record }
            for (service in dead) {
                service.app = null
                service.created = false
                service.pending = false
                for (binding in service.bindings.values) {
                    binding.binder = null
                    binding.received = false
                    binding.requested = false
                    binding.hasBound = false
                    binding.doRebind = false
                }
                if (record.exitReason == android.app.ApplicationExitInfo.REASON_CRASH) {
                    // ProcessServiceRecord.incServiceCrashCountLocked resets after
                    // a stable run, independently of the restart backoff window.
                    val interval = serviceConstants().getInt("min_crash_interval", 120_000)
                    service.crashCount = if (android.os.SystemClock.uptimeMillis() > service.restartTime + interval)
                        1 else service.crashCount + 1
                }
                for (connection in service.connections) {
                    try { connection.conn.connected(service.name, null, false) } catch (_: RemoteException) { }
                }
                if (!record.ready || !scheduleServiceRestartLocked(service)) bringDownServiceLocked(service)?.invoke()
            }
            for (service in owned.map { it.record }.distinct()) bringDownIfNeededLocked(service)?.invoke()
            // ContentProviderHelper.cleanupAppInLaunchingProvidersLocked keys
            // waiting providers by their launching process, including siblings.
            providersByAuthority.values.filter { it.app === record }.forEach { it.dead = true }
            providerConnections.filter { it.provider.app === record }.forEach { it.provider.dead = true }
            providerConnections.removeAll { it.client == record.binder || it.provider.app === record }
            providersByAuthority.entries.removeAll { it.value.app === record }
            for (waiters in providerWaiters.values) {
                val failed = waiters.filter {
                    it.key == HostedProcessStarts.Key(record.packageName, record.processName)
                }
                for (waiter in failed) waiter.latch.countDown()
            }
            // Publish final-process death before a replacement process can
            // attach under the same UID and hide the GONE transition.
            publishUidState(record.uid)
        }
        updateWindowProcessStates()
        Log.i(TAG, "process died: " + record.packageName + " pid=" + record.pid)
        processObservers.died(record.pid, record.uid)
        processObserver?.invoke(record.packageName, record.uid, false)
    }

    internal data class AttachedProcess(
        val thread: IApplicationThread,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
        val ready: Boolean,
    )

    internal fun attachedProcess(thread: IApplicationThread?): AttachedProcess? = synchronized(lock) {
        val record = thread?.asBinder()?.let(processes::get) ?: return@synchronized null
        AttachedProcess(record.thread, record.pid, record.uid, record.packageName, record.processName, record.ready)
    }

    internal fun attachedProcessForPackage(
        packageName: String,
        processName: String? = null,
    ): AttachedProcess? = synchronized(lock) {
        val record = processes.values.firstOrNull {
            it.ready && it.packageName == packageName && (processName == null || it.processName == processName)
        }
            ?: return@synchronized null
        AttachedProcess(record.thread, record.pid, record.uid, record.packageName, record.processName, record.ready)
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
            if (record?.ready == true) {
                attached = AttachedProcess(
                    record.thread, record.pid, record.uid, record.packageName, record.processName, record.ready,
                )
                true
            } else if (!spawnIfMissing && record == null) {
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
                    pendingByProcess.getOrPut(HostedProcessStarts.Key(packageName, processName)) { ArrayList() }
                }
                val work = PendingWork(replay)
                pending.add(work)
                if (record != null || bringUpProcessLocked(packageName, processName ?: packageName)) {
                    true
                } else {
                    pending.remove(work)
                    if (processName == null && pending.isEmpty()) {
                        pendingByPackage.remove(packageName)
                    } else if (processName != null && pending.isEmpty()) {
                        pendingByProcess.remove(HostedProcessStarts.Key(packageName, processName))
                    }
                    false
                }
            }
        }
        if (accepted) attached?.let(action)
        return accepted
    }

    /** Pinned AMS.getConfiguration delegates to ATMS.getConfiguration, which
     * copies the calling process's configuration under its WM lock and clears
     * userSetLocale. AMS/ProcessList are hosted; stock ATMS owns this state. */
    override fun getConfiguration(): Configuration {
        val tasks = synchronized(lock) { stockTasks }
        if (tasks != null) return tasks.configuration
        val pid = Binder.getCallingPid()
        if (pid >= 0 && pid != android.os.Process.myPid()) {
            val sent = synchronized(lock) {
                processes.values.firstOrNull { it.pid == pid }?.sentConfiguration?.let(::Configuration)
            }
            if (sent != null) return sent.apply { userSetLocale = false }
        }
        return Configuration()
    }

    internal fun updateGlobalConfiguration(values: Configuration) {
        scheduleConfigurationDelta(values, null)
    }

    // ---------------------------------------------------------- resolution

    /** ActiveServices.getProcessNameForService / bringUpServiceLocked: ordinary
     * services run in the manifest-selected process, even if a sibling is ready. */
    private fun resolveServiceLocked(service: Intent, callingPackage: String?): Pair<ProcessRecord, android.content.pm.ServiceInfo>? {
        val info = systemPackages { it.resolveService(service, null, 0L, UserHandle.USER_SYSTEM) }?.serviceInfo ?: return null
        val process = processes.values.firstOrNull { it.ready && it.packageName == info.packageName &&
            it.processName == (info.processName ?: info.packageName) } ?: return null
        return process to info
    }

    /** Not attached: spawn the manifest process; the pending op replays on attach. Requires `lock`. */
    private fun bringUpProcessLocked(packageName: String, processName: String = packageName): Boolean {
        val launcher = processLauncher ?: return false
        val apkPath = applicationInfo(packageName, 0L, UserHandle.USER_SYSTEM)?.sourceDir ?: return false
        return try { processStarts.start(HostedProcessStarts.Key(packageName, processName), apkPath, launcher) }
        catch (failure: Exception) { Log.e(TAG, "Cannot launch $processName", failure); false }
    }

    /** AMS.handleProcessStartOrKillTimeoutLocked / ContentProviderHelper cleanup:
     * release only this start's component waiters, then clean any attached WPC. */
    private fun processStartFinishedLocked(request: HostedProcessStarts.Request<android.content.pm.ApplicationInfo>,
        failure: HostedProcessStarts.Failure?) {
        isolatedUidsByStart.remove(request.sequence)?.let { (uid, owner) ->
            com.android.server.LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
                .removeIsolatedUid(uid)
            batteryService?.isolatedUidRemoved(uid, owner)
        }
        val key = request.key
        val record = processes.values.firstOrNull { it.packageName == key.packageName &&
            it.processName == key.processName && it.startSeq == request.sequence }
        if (failure != null) {
            Log.e(TAG, "Process start ${request.sequence} failed: ${key.processName}: $failure")
            pendingByProcess.remove(key)?.forEach { it.failed() }
            for (service in servicesByComponent.values.toList()) {
                if (service.pending && service.target.process == key) bringDownServiceLocked(service)?.invoke()
            }
            if (!key.isolated && key.processName == key.packageName)
                pendingByPackage.remove(key.packageName)?.forEach { it.failed() }
            for (waiters in providerWaiters.values)
                waiters.filter { it.key == key }.forEach { it.latch.countDown() }
            if (record != null) {
                record.exitReason = android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                record.exitDescription = failure.name
            }
        }
        if (record != null) handleProcessDied(record)
    }

    /** AMS.LocalService.startProcess -> ProcessList.startProcessLocked. Stock ATMS
     * owns the pending activity; attachment releases it after bindApplication and
     * Application.onCreate. The host launcher substitutes for the zygote fork. */
    internal fun startActivityProcess(processName: String, info: android.content.pm.ApplicationInfo,
        knownToBeDead: Boolean, isTop: Boolean, hostingType: String,
        hostingName: ComponentName) {
        synchronized(lock) {
            val installed = checkNotNull(applicationInfo(info.packageName, 0L,
                UserHandle.getUserId(info.uid))) { "Cannot start uninstalled package ${info.packageName}" }
            check(installed.uid == info.uid && info.uid >= android.os.Process.FIRST_APPLICATION_UID)
            val old = processes.values.firstOrNull { it.uid == info.uid && it.processName == processName }
            if (old != null && !knownToBeDead) return
            if (old != null) {
                try { old.thread.scheduleExit() } catch (_: RemoteException) { }
                handleProcessDied(old)
            }
            check(bringUpProcessLocked(info.packageName, processName)) {
                "Cannot start $processName for $hostingType $hostingName"
            }
            Log.i(TAG, "Starting $processName uid=${info.uid} top=$isTop for $hostingType $hostingName")
        }
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
        return android.app.ActivityManager.checkComponentPermission(entry.permission, callingUid,
            ownerUid, entry.exported) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun serviceForLocked(target: BindingTarget): ServiceRecord {
        return servicesByComponent.getOrPut(target.instance) {
            // AOSP stores an isolated ProcessRecord on the ServiceRecord. A new
            // unshared service must not reuse its predecessor during onDestroy.
            val key = if (target.process.isolated && !target.sharedProcess)
                target.process.copy(isolatedInstance = nextIsolatedServiceInstance++) else target.process
            ServiceRecord(target.copy(process = key)).also { servicesByToken[it.token] = it }
        }
    }

    private data class BindingTarget(val info: android.content.pm.ServiceInfo,
        val name: ComponentName, val instance: ServiceInstance, val process: HostedProcessStarts.Key,
        val intent: Intent, val sharedProcess: Boolean)

    /** Pinned ActiveServices.retrieveServiceLocked and getProcessNameForService:
     * class callbacks, instance identity and process sharing are separate names.
     * External services keep their defining APK but use the caller's app identity. */
    private fun bindingTarget(source: android.content.pm.ServiceInfo, intent: Intent, flags: Long,
        instanceName: String?, callingPackage: String, userId: Int): BindingTarget {
        val info = android.content.pm.ServiceInfo(source)
        info.applicationInfo = android.content.pm.ApplicationInfo(source.applicationInfo)
        var name = ComponentName(info.applicationInfo.packageName, info.name)
        var instance = intent.component ?: name
        if (instanceName != null) instance = ComponentName(instance.packageName, instance.className + ":" + instanceName)
        val bindingIntent = Intent(intent)
        if (info.flags and android.content.pm.ServiceInfo.FLAG_EXTERNAL_SERVICE != 0) {
            val callerInfo = applicationInfo(callingPackage, 0L, userId)
                ?: throw SecurityException("BIND_EXTERNAL_SERVICE failed, could not resolve client package $callingPackage")
            info.applicationInfo.packageName = callerInfo.packageName
            info.applicationInfo.uid = callerInfo.uid
            instance = ComponentName(callingPackage, instance.className)
            name = ComponentName(callingPackage, info.name + (instanceName?.let { ":$it" } ?: ""))
            bindingIntent.component = instance
        }
        val isolated = info.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0
        val privateShared = flags and android.content.Context.BIND_PACKAGE_ISOLATED_PROCESS.toLong() != 0L &&
            android.content.flags.Flags.enableBindPackageIsolatedProcess()
        val shared = flags and android.content.Context.BIND_SHARED_ISOLATED_PROCESS.toLong() != 0L
        val processName = when {
            !isolated || privateShared -> info.processName
            shared -> "$callingPackage:ishared:$instanceName"
            else -> info.processName + ":" + instance.className
        }
        return BindingTarget(info, name, ServiceInstance(userId, instance),
            HostedProcessStarts.Key(info.applicationInfo.packageName, processName, userId, isolated), bindingIntent,
            shared || privateShared)
    }

    private fun bringUpServiceProcessLocked(target: BindingTarget): Boolean {
        val launcher = processLauncher ?: return false
        val apkPath = target.info.applicationInfo.sourceDir ?: return false
        return try {
            val started = processStarts.start(target.process, apkPath, launcher, android.content.pm.ApplicationInfo(target.info.applicationInfo))
            if (started && target.process.isolated) {
                val identity = checkNotNull(processStarts.identity(target.process))
                val request = checkNotNull(processStarts.requestFor(target.process))
                // ProcessList.newProcessRecordLocked records the owning app in PM;
                // this is metadata, never a permission grant to the isolated UID.
                // Another service can share this already-starting process.
                if (!isolatedUidsByStart.containsKey(request.sequence)) {
                    com.android.server.LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
                        .addIsolatedUid(identity.uid, target.info.applicationInfo.uid)
                    batteryService?.isolatedUidAdded(identity.uid, target.info.applicationInfo.uid)
                    isolatedUidsByStart[request.sequence] = identity.uid to target.info.applicationInfo.uid
                }
            }
            started
        } catch (failure: Exception) { Log.e(TAG, "Cannot launch ${target.process.processName}", failure); false }
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
        val callingUid = Binder.getCallingUid()
        if (UserHandle.isIsolated(callingUid)) throw SecurityException("Isolated process not allowed to call startService")
        require(service == null || !service.hasFileDescriptors()) { "File descriptors passed in Intent" }
        requireNotNull(callingPackage) { "callingPackage cannot be null" }
        service ?: return null
        service.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH)
        if (isSameApp(callingPackage, callingUid) != true) {
            throw SecurityException("uid $callingUid does not own $callingPackage")
        }
        addCreatorToken(service, callingPackage)
        val post = synchronized(lock) {
            validateServiceCallerLocked(caller, service, "starting")
            val targetUser = serviceUser(service, callingUid, userId, callingPackage)
            doStartLocked(callingUid, service, callingPackage, resolvedType, targetUser)
        }
        post.schedule?.invoke()
        return post.name
    }

    private fun doStartLocked(
        callingUid: Int,
        service: Intent,
        callingPackage: String?,
        resolvedType: String? = service.type,
        userId: Int = UserHandle.getUserId(callingUid),
    ): PostStart {
        val targetInfo = systemPackages { it.resolveService(service, resolvedType, 0L, userId) }?.serviceInfo
            ?: return PostStart(null, null, 0)
        validateBindModes(targetInfo, service, 0, null, callingPackage ?: targetInfo.packageName, callingUid)
        if (!checkAccessLocked(targetInfo, targetInfo.applicationInfo.uid, callingUid)) {
            // ActiveServices.startServiceLocked returns the denial marker over
            // Binder; original ContextImpl turns it into SecurityException.
            val permission = if (!targetInfo.exported) "not exported from uid ${targetInfo.applicationInfo.uid}"
                else targetInfo.permission ?: "private to package"
            return PostStart(null, ComponentName("!", permission), -1)
        }
        val target = bindingTarget(targetInfo, service, 0, null,
            callingPackage ?: targetInfo.packageName, userId)
        val record = serviceForLocked(target)
        record.startRequested = true
        record.stopIfKilled = false
        record.pendingStarts.add(ServiceStart(false, record.nextStartId(), Intent(service)))
        unscheduleServiceRestartLocked(record, callingUid)
        val accepted = bringUpServiceLocked(record)
        updateWindowProcessStates()
        return PostStart(null, if (accepted) record.name else null, if (accepted) 1 else 0)
    }

    /** ActiveServices.bringUpServiceLocked / realStartServiceLocked: retain the
     * record before process attachment and order create, all bindings, then starts.
     * As in AOSP, send these one-way thread transactions while holding AMS's lock
     * so concurrent unbind/start calls cannot overtake service creation. */
    private fun bringUpServiceLocked(record: ServiceRecord): Boolean {
        if (servicesByToken[record.token] !== record) return false
        if (record.restarter != null) return true
        if (!record.created) {
            if (!serviceNeededLocked(record) && !record.pending) return true
            val app = processes.values.firstOrNull { it.ready && it.key == record.target.process }
            if (app == null) {
                record.pending = true
                if (processes.values.any { it.key == record.target.process } ||
                    bringUpServiceProcessLocked(record.target)) return true
                record.pending = false
                bringDownServiceLocked(record)?.invoke()
                return false
            }
            record.app = app
            record.pending = false
            record.created = true
            record.restartTime = android.os.SystemClock.uptimeMillis()
            if (record.startRequested && record.callStart && record.pendingStarts.isEmpty())
                record.pendingStarts.add(ServiceStart(false, record.nextStartId(), null))
            try { app.thread.scheduleCreateService(record.token, record.entry, null, app.reportedProcState) }
            catch (_: RemoteException) { handleProcessDied(app); return true }
            // realStartServiceLocked reposts the retained foreground notification
            // after create, with the new process PID, before bindings and starts.
            postForegroundNotificationLocked(record)
        }
        for (binding in record.bindings.values) requestServiceBindingLocked(record, binding)
        val app = record.app ?: return true
        sendServiceStartsLocked(record, app)
        bringDownIfNeededLocked(record)?.invoke()
        return true
    }

    private fun sendServiceStartsLocked(record: ServiceRecord, app: ProcessRecord) {
        if (record.pendingStarts.isEmpty()) return
        val count = record.pendingStarts.size
        val arguments = ArrayList<android.app.ServiceStartArgs>()
        for (start in record.pendingStarts) {
            if (start.intent == null && count > 1) continue
            start.deliveredTime = android.os.SystemClock.uptimeMillis()
            start.deliveryCount++
            record.deliveredStarts.add(start)
            arguments.add(start.arguments())
        }
        record.pendingStarts.clear()
        try { app.thread.scheduleServiceArgs(record.token, android.content.pm.ParceledListSlice(arguments)) }
        catch (_: RemoteException) { handleProcessDied(app) }
    }

    private fun serviceConstants(): android.util.KeyValueListParser {
        val constants = android.util.KeyValueListParser(',')
        val identity = Binder.clearCallingIdentity()
        try {
            constants.setString(context?.let { android.provider.Settings.Global.getString(
                it.contentResolver, android.provider.Settings.Global.ACTIVITY_MANAGER_CONSTANTS) })
        } catch (_: IllegalArgumentException) { /* Original constants retain defaults for bad input. */ }
        finally { Binder.restoreCallingIdentity(identity) }
        return constants
    }

    /** ActiveServices.scheduleServiceRestartLocked's start redelivery and backoff.
     * Original ActivityManagerConstants reads these settings; the host process
     * graph uses the same keys/defaults without importing Android's zygote/AMS. */
    private fun scheduleServiceRestartLocked(record: ServiceRecord): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val constants = serviceConstants()
        var duration = constants.getLong("service_restart_duration", 1_000)
        var reset = constants.getLong("service_reset_run_duration", 60_000)
        var canceled = false
        for (start in record.deliveredStarts.asReversed()) {
            if (start.intent == null) continue
            if (start.deliveryCount < 3 && start.doneExecutingCount < 6) {
                record.pendingStarts.add(0, start)
                val elapsed = (now - start.deliveredTime) * 2
                duration = maxOf(duration, elapsed)
                reset = maxOf(reset, elapsed)
            } else canceled = true
        }
        record.deliveredStarts.clear()
        if (record.startRequested && (record.stopIfKilled || canceled) && record.pendingStarts.isEmpty())
            record.startRequested = false
        if (!serviceNeededLocked(record)) return false
        val persistent = record.entry.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT != 0
        if (!persistent && record.crashCount >= constants.getInt("service_crash_max_retry", 16)) return false
        record.restartDelay = when {
            persistent -> 0
            record.restartDelay == 0L -> duration
            record.crashCount > 1 -> constants.getLong("service_crash_restart_duration", 30 * 60_000) * (record.crashCount - 1)
            now > record.restartTime + reset -> duration
            else -> maxOf(duration, record.restartDelay * constants.getInt("service_restart_duration_factor", 4))
        }
        record.nextRestartTime = now + record.restartDelay
        val spacing = constants.getLong("service_min_restart_time_between", 10_000)
        if (!persistent) for (other in servicesByComponent.values.filter { it.restarter != null }
            .sortedBy { it.nextRestartTime }) {
            if (record.nextRestartTime >= other.nextRestartTime - spacing &&
                record.nextRestartTime < other.nextRestartTime + spacing)
                record.nextRestartTime = other.nextRestartTime + spacing
        }
        record.restartDelay = record.nextRestartTime - now
        // scheduleServiceRestartLocked uses the same retained/shared notification
        // policy as normal teardown, including a previous stopForeground(0).
        cancelForegroundNotificationLocked(record)
        val restart = Runnable {
            synchronized(lock) {
                record.restarter = null
                if (servicesByToken[record.token] === record) {
                    if (serviceNeededLocked(record)) bringUpServiceLocked(record)
                    else bringDownServiceLocked(record)?.invoke()
                }
            }
        }
        record.restarter?.let { importanceHandler.removeCallbacks(it) }
        record.restarter = restart
        importanceHandler.postAtTime(restart, record.nextRestartTime)
        return true
    }

    private fun unscheduleServiceRestartLocked(record: ServiceRecord, callingUid: Int) {
        if (record.restarter != null || callingUid != record.entry.applicationInfo.uid) record.restartDelay = 0
        record.restarter?.let { importanceHandler.removeCallbacks(it) }
        record.restarter = null
    }

    private fun requestServiceBindingLocked(record: ServiceRecord, binding: IntentBindRecord) {
        val app = record.app ?: return
        if (!record.created || binding.requested || record.connections.none { it.binding === binding }) return
        if (binding.received && !binding.doRebind) return
        val rebind = binding.doRebind
        binding.requested = true
        binding.hasBound = true
        binding.doRebind = false
        try { app.thread.scheduleBindService(record.token, binding.intent, rebind, app.reportedProcState, 0) }
        catch (_: RemoteException) { handleProcessDied(app) }
    }


    /** ActiveServices.cleanUpServices: task removal stops FLAG_STOP_WITH_TASK
     * services and delivers onTaskRemoved to other started services. ActiveServices
     * depends on AMS's process graph; this host owns the equivalent records here.
     * Pending starts retain their ordering across the hosted process-attach seam. */
    fun cleanUpServices(userId: Int, component: ComponentName, baseIntent: Intent) {
        val intent = Intent(baseIntent)
        val callbacks = synchronized(lock) {
            cleanUpServicesLocked(userId, component.packageName, intent)
        }
        callbacks.forEach { it() }
        updateWindowProcessStates()
    }

    private fun cleanUpServicesLocked(userId: Int, packageName: String, baseIntent: Intent,
        processName: String? = null): List<() -> Unit> {
        val callbacks = ArrayList<() -> Unit>()
        for (record in servicesByComponent.values.toList()) {
            if (record.name.packageName != packageName || !record.startRequested ||
                UserHandle.getUserId(record.entry.applicationInfo.uid) != userId ||
                (processName != null && record.app?.processName != processName)) continue
            if (record.entry.flags and android.content.pm.ServiceInfo.FLAG_STOP_WITH_TASK != 0) {
                record.startRequested = false
                bringDownIfNeededLocked(record)?.let(callbacks::add)
            } else {
                val start = ServiceStart(true, record.lastStartId, Intent(baseIntent))
                val app = record.app
                if (app == null) { record.pendingStarts.add(start); continue }
                record.pendingStarts.add(start)
                sendServiceStartsLocked(record, app)
            }
        }
        return callbacks
    }

    /** ActiveServices.retrieveServiceLocked resolves user and access for start,
     * stop and bind before mutating a record or launching an application. */
    private fun serviceUser(service: Intent, uid: Int, userId: Int, callingPackage: String?): Int {
        val allowMode = if (callingPackage != null && service.component?.packageName == callingPackage)
            android.app.ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL
            else android.app.ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE
        return HostedActivityManagerInternalService(this, HostedPermissions(checkNotNull(packageService)))
            .handleIncomingUser(Binder.getCallingPid(), uid, userId, false, allowMode, "service", callingPackage)
    }

    private fun validateServiceCallerLocked(caller: IApplicationThread?, service: Intent, operation: String) {
        if (caller != null && processes[caller.asBinder()] == null)
            throw SecurityException("Unable to find app for caller $caller (pid=${Binder.getCallingPid()}) when $operation service $service")
    }

    override fun stopService(caller: IApplicationThread?, service: Intent?, resolvedType: String?, userId: Int): Int {
        val uid = Binder.getCallingUid()
        if (UserHandle.isIsolated(uid)) throw SecurityException("Isolated process not allowed to call stopService")
        require(service == null || !service.hasFileDescriptors()) { "File descriptors passed in Intent" }
        service ?: return 0
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            validateServiceCallerLocked(caller, service, "stopping")
            val targetUser = serviceUser(service, uid, userId, null)
            val info = systemPackages { it.resolveService(service, resolvedType, 0L, targetUser) }?.serviceInfo ?: return 0
            // retrieveServiceLocked validates flags and access even when no
            // service instance is running. External services require a bind.
            validateBindModes(info, service, 0, null, info.packageName, uid)
            if (!checkAccessLocked(info, info.applicationInfo.uid, uid)) return -1
            val component = ComponentName(info.applicationInfo.packageName, info.name)
            val record = servicesByComponent[ServiceInstance(targetUser, component)] ?: return 0
            if (!checkAccessLocked(record.entry, record.entry.applicationInfo.uid, uid)) return -1
            record.startRequested = false
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        updateWindowProcessStates()
        return 1
    }

    override fun stopServiceToken(className: ComponentName?, token: IBinder?, startId: Int): Boolean {
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val record = servicesByComponent[ServiceInstance(UserHandle.getCallingUserId(),
                className ?: return false)] ?: return false
            if (record.token != token) return false
            if (startId >= 0) {
                val delivered = record.deliveredStarts.indexOfFirst { !it.taskRemoved && it.id == startId }
                if (delivered >= 0) record.deliveredStarts.subList(0, delivered + 1).clear()
                if (startId != record.lastStartId) return false
            }
            record.startRequested = false
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        updateWindowProcessStates()
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
        val callingUid = Binder.getCallingUid()
        // ActivityManagerService.bindServiceInstance validates before registry work.
        if (UserHandle.isIsolated(callingUid)) throw SecurityException("Isolated process not allowed to call bindService")
        require(!service.hasFileDescriptors()) { "File descriptors passed in Intent" }
        requireNotNull(callingPackage) { "callingPackage cannot be null" }
        if (instanceName != null) require(instanceName.matches(Regex("[a-zA-Z0-9_.]+"))) { "Illegal instanceName" }
        service.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH)
        if (callingPackage != null && isSameApp(callingPackage, callingUid) != true) {
            throw SecurityException("uid $callingUid does not own $callingPackage")
        }
        addCreatorToken(service, callingPackage)
        val post = synchronized(lock) {
            val client = caller?.asBinder()?.let { processes[it] } ?: run {
                throw SecurityException("Unable to find app for caller $caller (pid=${Binder.getCallingPid()}) when binding service $service")
            }
            validateBindPrivileges(client, service, flags)
            val allowMode = if (service.component?.packageName == callingPackage)
                android.app.ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL
                else android.app.ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE
            val targetUser = HostedActivityManagerInternalService(this, HostedPermissions(checkNotNull(packageService)))
                .handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, allowMode, "service", callingPackage)
            doBindLocked(client, callingUid, service, connection, flags, callingPackage,
                instanceName, resolvedType, targetUser)
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
        flags: Long,
        callingPackage: String,
        instanceName: String?,
        resolvedType: String?,
        userId: Int,
    ): PostBind {
        if (instanceName != null && service.component == null) throw IllegalArgumentException(
            "Can't use custom instance name '$instanceName' without expicit component in Intent")
        val queryFlags = (if (flags and android.content.Context.BIND_ALLOW_INSTANT.toLong() != 0L)
            android.content.pm.PackageManager.MATCH_INSTANT.toLong() else 0L) or
            (if (flags and android.content.Context.BIND_MATCH_QUARANTINED_COMPONENTS != 0L)
                android.content.pm.PackageManager.MATCH_QUARANTINED_COMPONENTS else 0L)
        val info = systemPackages { it.resolveService(service, resolvedType, queryFlags, userId) }?.serviceInfo
            ?: return PostBind(null, null, false, null, 0)
        validateBindModes(info, service, flags, instanceName, callingPackage, callingUid)
        // ActiveServices.retrieveServiceLocked checks permission before bringUpServiceLocked:
        // a denied cold bind must never create a process or retain pending work.
        if (!checkAccessLocked(info, info.applicationInfo.uid, callingUid))
            return PostBind(null, null, false, null, -1)
        val target = bindingTarget(info, service, flags, instanceName, callingPackage, userId)
        val record = serviceForLocked(target)
        val comparison = FilterComparison(target.intent)
        val binding = record.bindings.getOrPut(comparison) { IntentBindRecord(target.intent) }
        val retained = ConnectionRecord(connection, client, record, binding, flags)
        record.connections.add(retained)
        serviceConnections.getOrPut(connection.asBinder()) { ArrayList() }.add(retained)
        if (flags and android.content.Context.BIND_AUTO_CREATE.toLong() != 0L)
            unscheduleServiceRestartLocked(record, callingUid)
        if (!bringUpServiceLocked(record)) return PostBind(null, null, false, null, 0)
        updateWindowProcessStates()
        return PostBind(null, record.name, binding.received, binding.binder, 1)
    }

    /** ActiveServices.bindServiceLocked flag privileges, before any service/process mutation. */
    private fun validateBindPrivileges(client: ProcessRecord, service: Intent, flags: Long) {
        val system = client.uid == android.os.Process.SYSTEM_UID
        for ((flag, name) in listOf(
            android.content.Context.BIND_SCHEDULE_LIKE_TOP_APP to "BIND_SCHEDULE_LIKE_TOP_APP",
            android.content.Context.BIND_ALLOW_WHITELIST_MANAGEMENT to "BIND_ALLOW_WHITELIST_MANAGEMENT",
            android.content.Context.BIND_ALLOW_INSTANT to "BIND_ALLOW_INSTANT",
            android.content.Context.BIND_ALMOST_PERCEPTIBLE to "BIND_ALMOST_PERCEPTIBLE")) {
            if (flags and flag.toLong() != 0L && !system) throw SecurityException(
                "Non-system caller (pid=${Binder.getCallingPid()}) set $name when binding service $service")
        }
        for ((flag, permission, name) in listOf(
            Triple(android.content.Context.BIND_TREAT_LIKE_ACTIVITY,
                android.Manifest.permission.MANAGE_ACTIVITY_TASKS, "BIND_TREAT_LIKE_ACTIVITY"),
            Triple(android.content.Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND, "BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS"),
            Triple(android.content.Context.BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND,
                android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND, "BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND"))) {
            if (flags and flag.toLong() != 0L) enforceCallingPermission(permission, name)
        }
    }

    /** ActiveServices.retrieveServiceLocked validation order, before instance creation. */
    private fun validateBindModes(info: android.content.pm.ServiceInfo, intent: Intent, flags: Long,
        instanceName: String?, callingPackage: String, callingUid: Int) {
        val isolated = info.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0
        if (instanceName != null && !isolated) throw IllegalArgumentException(
            "Can't use instance name '$instanceName' with non-isolated non-sdk sandbox service '${info.name}'")
        val external = flags and Integer.toUnsignedLong(android.content.Context.BIND_EXTERNAL_SERVICE) != 0L ||
            flags and android.content.Context.BIND_EXTERNAL_SERVICE_LONG != 0L
        val shared = flags and android.content.Context.BIND_SHARED_ISOLATED_PROCESS.toLong() != 0L
        val privateShared = flags and android.content.Context.BIND_PACKAGE_ISOLATED_PROCESS.toLong() != 0L &&
            android.content.flags.Flags.enableBindPackageIsolatedProcess()
        val className = ComponentName(info.applicationInfo.packageName, info.name)
        val component = intent.component ?: className
        if (info.flags and android.content.pm.ServiceInfo.FLAG_EXTERNAL_SERVICE != 0) {
            if (!external) throw SecurityException("BIND_EXTERNAL_SERVICE required for $component")
            if (!info.exported) throw SecurityException("BIND_EXTERNAL_SERVICE failed, $className is not exported")
            if (privateShared) throw SecurityException("BIND_PACKAGE_ISOLATED_PROCESS cannot be applied to an external service.")
            if (!isolated) throw SecurityException("BIND_EXTERNAL_SERVICE failed, $className is not an isolatedProcess")
            if (packageService?.getPackagesForUid(callingUid)?.contains(callingPackage) != true)
                throw SecurityException("BIND_EXTERNAL_SERVICE failed, calling package not owned by calling UID ")
        } else if (external) throw SecurityException("BIND_EXTERNAL_SERVICE failed, $component is not an externalService")
        if (shared && privateShared) throw SecurityException(
            "Either BIND_SHARED_ISOLATED_PROCESS or BIND_PACKAGE_ISOLATED_PROCESS should be set. Not both.")
        if ((shared || privateShared) && !isolated) throw SecurityException(
            "BIND_SHARED_ISOLATED_PROCESS failed, $className is not an isolatedProcess")
        if (privateShared && info.processName == info.applicationInfo.processName) throw SecurityException(
            "BIND_PACKAGE_ISOLATED_PROCESS cannot be used for services running in the main app process.")
        if (shared) {
            require(instanceName != null) { "instanceName must be provided for binding a service into a shared isolated process." }
            if (info.flags and android.content.pm.ServiceInfo.FLAG_ALLOW_SHARED_ISOLATED_PROCESS == 0)
                throw SecurityException("BIND_SHARED_ISOLATED_PROCESS failed, $className has not set the allowSharedIsolatedProcess  attribute.")
        }
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
        synchronized(lock) {
            val connections = serviceConnections[connection.asBinder()]?.toList() ?: return false
            for (retained in connections) removeConnectionLocked(retained)
            for (record in connections.map { it.record }.distinct()) bringDownIfNeededLocked(record)?.invoke()
        }
        updateWindowProcessStates()
        return true
    }

    /** ActiveServices.removeConnectionLocked: last connection on a binding unbinds it. */
    private fun removeConnectionLocked(connection: ConnectionRecord) {
        val record = connection.record
        record.connections.remove(connection)
        serviceConnections[connection.conn.asBinder()]?.let {
            it.remove(connection)
            if (it.isEmpty()) serviceConnections.remove(connection.conn.asBinder())
        }
        val binding = connection.binding
        val bindingInUse = record.connections.any { it.binding === binding }
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
        if (serviceNeededLocked(record) || record.pending) return null
        return bringDownServiceLocked(record)
    }

    private fun serviceNeededLocked(record: ServiceRecord): Boolean = record.startRequested ||
        record.connections.any { it.flags and android.content.Context.BIND_AUTO_CREATE.toLong() != 0L }

    private fun bringDownServiceLocked(record: ServiceRecord): (() -> Unit)? {
        if (servicesByToken[record.token] !== record) return null
        record.pending = false
        unscheduleServiceRestartLocked(record, record.entry.applicationInfo.uid)
        record.pendingStarts.clear()
        record.deliveredStarts.clear()
        for (connection in record.connections) {
            try { connection.conn.connected(record.name, null, true) } catch (_: RemoteException) { }
        }
        for (binding in record.bindings.values) {
            if (binding.hasBound) {
                binding.hasBound = false
                binding.requested = false
                try { record.app?.thread?.scheduleUnbindService(record.token, binding.intent) }
                catch (_: RemoteException) { }
            }
        }
        servicesByComponent.remove(record.instance)
        servicesByToken.remove(record.token)
        // bringDownServiceLocked also cancels a notification retained by
        // stopForeground(0); DETACH has already cleared that association.
        cancelForegroundNotificationLocked(record)
        record.isForeground = false
        record.foregroundId = 0
        record.foregroundNotification = null
        val app = record.app?.takeIf { record.created } ?: return null
        destroyingServices[record.token] = record
        Log.d(TAG, "Stopping service ${record.instance} in ${app.pid}")
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
            // requested/hasBound were set when scheduleBindService was sent.
            // A publish racing an unbind must not resurrect that binding.
            for (connection in record.connections) {
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
            val binding = record.bindings[FilterComparison(intent)] ?: return
            binding.doRebind = true
            requestServiceBindingLocked(record, binding)
        }
    }

    override fun serviceDoneExecuting(token: IBinder?, type: Int, startId: Int, res: Int, intent: Intent?) {
        // ActiveServices.serviceDoneExecutingLocked removes the destroying
        // record before OomAdjuster retires an isolated process with no services.
        // Wait for the stock onDestroy acknowledgement, not merely the unbind call.
        synchronized(lock) {
            val service = (if (type == android.app.ActivityThread.SERVICE_DONE_EXECUTING_STOP)
                destroyingServices[token] else servicesByToken[token]) ?: return
            val app = service.app ?: return
            // A one-way Binder callback has caller PID zero. The service
            // token and authenticated UID identify the completing owner.
            if (app.uid != Binder.getCallingUid())
                throw SecurityException("Service completion from an unrelated UID")
            if (type == android.app.ActivityThread.SERVICE_DONE_EXECUTING_START) {
                service.callStart = true
                val start = service.deliveredStarts.firstOrNull {
                    it.id == startId && it.taskRemoved == (res == android.app.Service.START_TASK_REMOVED_COMPLETE)
                }
                when (res) {
                    android.app.Service.START_STICKY_COMPATIBILITY, android.app.Service.START_STICKY -> {
                        service.deliveredStarts.remove(start)
                        service.stopIfKilled = false
                    }
                    android.app.Service.START_NOT_STICKY -> {
                        service.deliveredStarts.remove(start)
                        if (service.lastStartId == startId) service.stopIfKilled = true
                    }
                    android.app.Service.START_REDELIVER_INTENT -> if (start != null) {
                        start.deliveryCount = 0
                        start.doneExecutingCount++
                        service.stopIfKilled = true
                    }
                    android.app.Service.START_TASK_REMOVED_COMPLETE -> service.deliveredStarts.remove(start)
                    else -> throw IllegalArgumentException("Unknown service start result: $res")
                }
                if (res == android.app.Service.START_STICKY_COMPATIBILITY) service.callStart = false
                return
            }
            if (type != android.app.ActivityThread.SERVICE_DONE_EXECUTING_STOP) return
            destroyingServices.remove(token)
            Log.d(TAG, "Service destroyed ${service.instance} in ${app.pid}: isolated=${app.key.isolated}")
            if (app.key.isolated && servicesByComponent.values.none { it.app === app } &&
                destroyingServices.values.none { it.app === app } && pendingByProcess[app.key].isNullOrEmpty()) {
                app.exitReason = android.app.ApplicationExitInfo.REASON_OTHER
                app.exitSubReason = android.app.ApplicationExitInfo.SUBREASON_ISOLATED_NOT_NEEDED
                app.exitDescription = "isolated not needed"
                val identity = Binder.clearCallingIdentity()
                try { handleProcessDied(app) }
                finally { Binder.restoreCallingIdentity(identity) }
            }
        }
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
        if (isSameApp(packageName, callingUid) != true) {
            throw SecurityException(
                "uid $callingUid is not allowed to create an IntentSender as $packageName",
            )
        }
        if (stockTasks != null && (type == android.app.ActivityManager.INTENT_SENDER_ACTIVITY ||
                type == android.app.ActivityManager.INTENT_SENDER_ACTIVITY_RESULT)) {
            return HostActivityPendingIntents.create(type, packageName, featureId, callingUid,
                userId, token, resultWho, requestCode, intents, resolvedTypes, flags, options)
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
        if (HostActivityPendingIntents.owns(target)) {
            addCreatorToken(intent, HostActivityPendingIntents.creator(checkNotNull(target)))
            return HostActivityPendingIntents.send(caller, target, whitelistToken, code, intent,
                resolvedType, finishedReceiver, requiredPermission, options)
        }
        val record = pendingIntents.record(target)
        if (record != null) return pendingIntents.sendInner(record, code, intent, resolvedType, finishedReceiver, requiredPermission, options)
        // AMS.sendIntentSender: a custom IIntentSender (for example a session's
        // LocalIntentReceiver) is sent directly, and its finish is reported here.
        val sender = target ?: return android.app.ActivityManager.START_CANCELED
        val sent = intent ?: Intent(Intent.ACTION_MAIN).also {
            android.util.Slog.wtf(TAG, "Can't use null intent with direct IIntentSender call")
        }
        try {
            val callingUid = Binder.getCallingUid()
            val packageName = Binder.withCleanCallingIdentity<String?> { packageService?.getNameForUid(callingUid) }
            if (whitelistToken != null) android.util.Slog.wtf(TAG, "Send a non-null allowlistToken to a non-PI target." +
                " Calling package: $packageName; intent: $sent; options: $options")
            addCreatorToken(sent, packageName)
            sender.send(code, sent, resolvedType, null, null, requiredPermission, options)
        } catch (_: android.os.RemoteException) {
        }
        if (finishedReceiver != null) {
            try {
                finishedReceiver.performReceive(sent, 0, null, null, false, false, UserHandle.getCallingUserId())
            } catch (_: android.os.RemoteException) {
            }
        }
        return 0
    }

    override fun cancelIntentSender(sender: android.content.IIntentSender?) =
        if (HostActivityPendingIntents.owns(sender)) HostActivityPendingIntents.cancel(checkNotNull(sender))
        else pendingIntents.cancel(sender, Binder.getCallingUid())

    override fun getInfoForIntentSender(sender: android.content.IIntentSender?): android.app.ActivityManager.PendingIntentInfo? {
        if (HostActivityPendingIntents.owns(sender)) return HostActivityPendingIntents.info(checkNotNull(sender))
        // ActivityManagerService.getInfoForIntentSender: a sender that is not a
        // PendingIntentRecord (for example a local IIntentSender) is unknown, mutable.
        val record = pendingIntents.record(sender) ?: return android.app.ActivityManager.PendingIntentInfo(
            null, android.os.Process.INVALID_UID, false, android.app.ActivityManager.INTENT_SENDER_UNKNOWN)
        return android.app.ActivityManager.PendingIntentInfo(record.key.packageName, record.key.uid, record.immutable, record.key.type)
    }

    override fun getIntentForIntentSender(sender: android.content.IIntentSender?): Intent? {
        enforceCallingPermission(
            android.Manifest.permission.GET_INTENT_SENDER_INTENT,
            "getIntentForIntentSender()",
        )
        if (HostActivityPendingIntents.owns(sender)) return HostActivityPendingIntents.intent(checkNotNull(sender))
        return pendingIntents.record(sender)?.key?.requestIntent?.let { Intent(it) }
    }

    override fun getTagForIntentSender(sender: android.content.IIntentSender?, prefix: String?): String? =
        if (HostActivityPendingIntents.owns(sender)) HostActivityPendingIntents.tag(checkNotNull(sender), prefix) else null

    override fun isIntentSenderTargetedToPackage(sender: android.content.IIntentSender?): Boolean {
        if (HostActivityPendingIntents.owns(sender)) return HostActivityPendingIntents.targeted(checkNotNull(sender))
        val intent = pendingIntents.record(sender)?.key?.requestIntent ?: return false
        return intent.getPackage() != null || intent.component != null
    }

    override fun isIntentSenderAnActivity(sender: android.content.IIntentSender?): Boolean =
        if (HostActivityPendingIntents.owns(sender)) HostActivityPendingIntents.activity(checkNotNull(sender))
        else pendingIntents.record(sender)?.key?.type == android.app.ActivityManager.INTENT_SENDER_ACTIVITY

    override fun registerIntentSenderCancelListenerEx(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?): Boolean =
        if (HostActivityPendingIntents.owns(sender)) HostActivityPendingIntents.register(checkNotNull(sender), receiver)
        else pendingIntents.registerCancelListener(sender, receiver)

    override fun unregisterIntentSenderCancelListener(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?) =
        if (HostActivityPendingIntents.owns(sender)) HostActivityPendingIntents.unregister(checkNotNull(sender), receiver)
        else pendingIntents.unregisterCancelListener(sender, receiver)

    override fun queryIntentComponentsForIntentSender(
        sender: android.content.IIntentSender?,
        matchFlags: Int,
    ): android.content.pm.ParceledListSlice<*>? {
        enforceCallingPermission(
            android.Manifest.permission.GET_INTENT_SENDER_INTENT,
            "queryIntentComponentsForIntentSender()",
        )
        if (HostActivityPendingIntents.owns(sender)) return HostActivityPendingIntents.query(checkNotNull(sender), matchFlags)
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
        broadcasts.broadcastIntent(intent, finishedReceiver, code, listOfNotNull(requiredPermission), finishedReceiver != null, false, packageName, uid)
    }

    /** AMS.LocalService.broadcastIntent: a system-service broadcast sent with the
     * caller's identity, without a caller process. */
    internal fun broadcastIntentFromSystem(
        intent: Intent, resultTo: android.content.IIntentReceiver?, requiredPermissions: Array<String>?,
        serialized: Boolean, userId: Int, appIdAllowList: IntArray?,
        filterExtras: java.util.function.BiFunction<Int, android.os.Bundle, android.os.Bundle>?, options: android.os.Bundle?,
    ): Int {
        if (intent.hasFileDescriptors()) throw IllegalArgumentException("File descriptors passed in Intent")
        val callingUid = Binder.getCallingUid()
        return Binder.withCleanCallingIdentity<Int> {
            broadcasts.broadcastIntent(intent, resultTo, 0, requiredPermissions.orEmpty().toList(), serialized, false,
                null, callingUid, null,
                android.app.BroadcastOptions.fromBundleNullable(options)?.isShareIdentityEnabled == true,
                userId = userId, appIdAllowList = appIdAllowList, filterExtras = filterExtras)
        }
    }

    /** A system service's sticky broadcast (BatteryService.sendBatteryChangedIntentLocked) on the real queue. */
    internal fun broadcastStickyFromSystem(intent: Intent) {
        broadcasts.broadcastIntent(intent, null, 0, emptyList(), false, true, "android", android.os.Process.SYSTEM_UID)
    }

    /** AMS.LocalService.broadcastGlobalConfigurationChanged, in its configuration/locale/split order.
     * This queue delivers unordered registered broadcasts immediately and does not freeze/cache apps:
     * there are no pending delivery groups to replace or inactive processes to defer. The host also
     * has no device-idle allowlist controller. Manifest receivers remain outside this queue's scope.
     */
    internal fun broadcastGlobalConfigurationChanged(changes: Int, initLocale: Boolean, processesReady: Boolean) {
        fun send(action: String, flags: Int, permission: String? = null) {
            broadcasts.broadcastIntent(Intent(action).addFlags(flags), null, 0, listOfNotNull(permission),
                false, false, "android", android.os.Process.SYSTEM_UID)
        }
        send(Intent.ACTION_CONFIGURATION_CHANGED, Intent.FLAG_RECEIVER_REGISTERED_ONLY or
            Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_FOREGROUND or
            Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS)
        if (changes and android.content.pm.ActivityInfo.CONFIG_LOCALE != 0) {
            var flags = Intent.FLAG_RECEIVER_OFFLOAD_FOREGROUND or Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND or
                Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS
            if (initLocale || !processesReady) flags = flags or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            send(Intent.ACTION_LOCALE_CHANGED, flags)
        }
        if (!initLocale && changes and (android.content.pm.ActivityInfo.CONFIG_LOCALE or
            android.content.pm.ActivityInfo.CONFIG_DENSITY) != 0) {
            send(Intent.ACTION_SPLIT_CONFIGURATION_CHANGED,
                Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND,
                android.Manifest.permission.INSTALL_PACKAGES)
        }
    }

    /** A service PendingIntent starts the service as its creator. */
    internal fun startServiceFromPendingIntent(packageName: String?, uid: Int, intent: Intent) {
        val post = synchronized(lock) { doStartLocked(uid, intent, packageName) }
        if (post.status == -1) throw SecurityException("startService denied")
        post.schedule?.invoke()
    }

    // ActivityManagerService.checkComponentPermission: root and the system
    // server hold every permission; other UIDs ask the permission manager.
    internal fun checkUidPermission(permission: String, uid: Int): Int =
        android.app.ActivityManager.checkComponentPermission(permission, uid, -1, true)

    override fun checkPermission(permission: String?, pid: Int, uid: Int): Int =
        permission?.let { HostActivityManagerAccess.checkComponentPermission(it, pid, uid, -1, true) }
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
            val record = ContentProviderRecord(holder, null)
            for (authority in holder.info.authority?.split(";") ?: return) {
                providersByAuthority[authority] = record
                providerWaiters.remove(authority)?.let { released.addAll(it) }
            }
        }
        for (waiter in released) waiter.latch.countDown()
    }

    /** Access gate for acquiring a provider (the per-call gate lives in the
     * provider's Transport; source note ActivityManagerService.checkContentProviderPermission). */
    private fun checkProviderAccess(info: android.content.pm.ProviderInfo, callingUid: Int) {
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == 0) return
        val ownerUid = packageUid(info.packageName, UserHandle.USER_SYSTEM) ?: -1
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
            permission != null && checkUidPermission(permission, callingUid) ==
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
                return acquireProviderLocked(it, caller, stable)
            }
            val info = systemPackages { it.resolveContentProvider(authority, 0L, 0) } ?: return null
            checkProviderAccess(info, callingUid)
            // The owning package has published nothing yet: make sure its
            // process is coming up, then wait for publishContentProviders.
            val processName = info.processName ?: info.packageName
            if (processes.values.none { it.packageName == info.packageName && it.processName == processName }) {
                if (!bringUpProcessLocked(info.packageName, processName)) return null
            }
            waiter = ProviderWaiter(HostedProcessStarts.Key(info.packageName, processName))
            providerWaiters.getOrPut(authority) { ArrayList() }.add(waiter)
        }
        // Wait outside the lock; publish/death arrive on other binder threads.
        val published = waiter.latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(lock) {
            providerWaiters[authority]?.remove(waiter)
            if (!published) Log.w(TAG, "timed out waiting for provider " + authority)
            val record = providersByAuthority[authority] ?: return null
            checkProviderAccess(record.holder.info, callingUid)
            return acquireProviderLocked(record, caller, stable)
        }
    }

    /** ContentProviderRecord.newHolder + ContentProviderHelper.incProviderCountLocked.
     * Publication's local noReleaseNeeded flag must not escape to an ordinary remote client. */
    private fun acquireProviderLocked(record: ContentProviderRecord, caller: IApplicationThread?, stable: Boolean): ContentProviderHolder {
        val holder = ContentProviderHolder(record.holder.info)
        holder.provider = record.holder.provider
        holder.noReleaseNeeded = record.app == null || record.app.uid == android.os.Process.SYSTEM_UID
        if (!holder.noReleaseNeeded) {
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            val client = caller?.asBinder()
            val connection = providerConnections.firstOrNull {
                it.provider === record && it.client == client && it.clientUid == uid && it.clientPid == pid
            } ?: ProviderConnection(record, client, uid, pid).also(providerConnections::add)
            if (stable) connection.stableCount++ else connection.unstableCount++
            holder.connection = connection
        }
        return holder
    }

    private fun providerConnection(connection: IBinder?): ProviderConnection {
        if (connection == null) throw NullPointerException("connection is null")
        return connection as? ProviderConnection
            ?: throw IllegalArgumentException("$connection not a ContentProviderConnection")
    }

    override fun refContentProvider(connection: IBinder?, stableDelta: Int, unstableDelta: Int): Boolean = synchronized(lock) {
        val record = providerConnection(connection)
        val stable = record.stableCount + stableDelta
        val unstable = record.unstableCount + unstableDelta
        check(stable >= 0) { "stableCount < 0: $stable" }
        check(unstable >= 0) { "unstableCount < 0: $unstable" }
        check(stable + unstable > 0) { "ref counts can't go to zero here: stable=$stable unstable=$unstable" }
        record.stableCount = stable
        record.unstableCount = unstable
        !record.provider.dead
    }

    override fun removeContentProvider(connection: IBinder?, stable: Boolean) {
        synchronized(lock) {
            val record = providerConnection(connection)
            if (stable) record.stableCount-- else record.unstableCount--
            if (record.stableCount + record.unstableCount == 0) providerConnections.remove(record)
        }
    }

    // ContentProviderHelper.unstableProviderDied: ignore an already cleaned-up provider or a
    // false death report. Confirmed early death enters the same path as the broker notification.
    override fun unstableProviderDied(connection: IBinder?) {
        val record = providerConnection(connection).provider
        val provider = synchronized(lock) { if (record.dead) null else record.holder.provider } ?: return
        if (provider.asBinder().pingBinder()) return
        val process = synchronized(lock) {
            if (record.dead || record.holder.provider !== provider) null else record.app
        }
        if (process != null) {
            val identity = Binder.clearCallingIdentity()
            try { handleProcessDied(process) } finally { Binder.restoreCallingIdentity(identity) }
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
                val ownerUid = packageUid(holder.info.packageName, UserHandle.USER_SYSTEM) ?: -1
                if (callingUid != android.os.Process.SYSTEM_UID && ownerUid != callingUid) {
                    Log.w(TAG, "uid " + callingUid + " may not publish providers of " + holder.info.packageName)
                    continue
                }
                val record = ContentProviderRecord(holder, app)
                for (authority in holder.info.authority?.split(";") ?: continue) {
                    providersByAuthority[authority] = record
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
            intent, resultTo, resultCode, requiredPermissions?.filterNotNull().orEmpty(), serialized, sticky,
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
        if (pendingTopUids.contains(uid)) return@synchronized android.app.ActivityManager.PROCESS_STATE_TOP
        processes.values.filter { it.uid == uid }.minOfOrNull { it.reportedProcState }
            ?: android.app.ActivityManager.PROCESS_STATE_NONEXISTENT
    }

    /** AMS.LocalService.killProcessesForRemovedTask / OomAdjuster.applyOomAdjLSP.
     * Stock ATMS chooses the candidate controllers. This host owns their process
     * handles, so exit uses the existing generation-checked supervisor, not a
     * raw Linux PID. Deferred candidates are reconsidered on service, receiver
     * and window importance changes, as in AOSP's waitingToKill path. */
    internal fun killProcessesForRemovedTask(controllers: ArrayList<Any>) = synchronized(lock) {
        for (controller in controllers) {
            val record = processes.values.firstOrNull { it.windowProcess === controller } ?: continue
            if (record.pid == android.os.Process.myPid() || record.removed) continue
            record.waitingToKillForTask = true
            updateProcessImportanceLocked(record)
        }
    }

    private fun killWaitingTaskProcessLocked(record: ProcessRecord) {
        if (!record.waitingToKillForTask || record.removed ||
            !android.app.ActivityManager.isProcStateBackground(record.reportedProcState) ||
            servicesByToken.values.any { it.app === record && it.startRequested } ||
            broadcasts.hasCurrentReceiver(record.binder)) return
        record.waitingToKillForTask = false
        record.exitReason = android.app.ApplicationExitInfo.REASON_USER_REQUESTED
        record.exitSubReason = android.app.ApplicationExitInfo.SUBREASON_REMOVE_TASK
        record.exitDescription = "remove task"
        // OomAdjuster kills the owned process for a removed task. scheduleExit
        // instead asks ActivityThread to quit its non-quittable main Looper and
        // can crash before the supervisor gets to terminate the child below.
        handleProcessDied(record)
    }

    /** ProcessList.killAllBackgroundProcessesExceptLSP eligibility over the session process registry.
     * The host requests exit through the owned application Binder, avoiding a recycled Linux PID.
     * Unresponsive-client forced termination belongs to the session supervisor's recovery path.
     */
    internal fun killAllBackgroundProcessesExcept(minTargetSdk: Int, maxProcState: Int) {
        enforceCallingPermission(android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES,
            "killAllBackgroundProcessesExcept")
        val identity = Binder.clearCallingIdentity()
        try {
            val candidates = synchronized(lock) {
                processes.values.filter { record ->
                    val info = record.supervised?.metadata ?: checkNotNull(applicationInfo(record.packageName, 0L,
                        UserHandle.getUserId(record.uid)))
                    (minTargetSdk < 0 || info.targetSdkVersion < minTargetSdk) &&
                        (maxProcState < 0 || record.reportedProcState > maxProcState)
                }
            }
            for (record in candidates) {
                val stop = synchronized(lock) {
                    if (processes[record.binder] !== record || record.pid == android.os.Process.myPid()) false
                    else {
                        record.exitReason = android.app.ApplicationExitInfo.REASON_OTHER
                        record.exitDescription = "kill all background except"
                        true
                    }
                }
                if (!stop) continue
                try { record.thread.scheduleExit() } catch (_: RemoteException) { }
                handleProcessDied(record)
            }
        } finally { Binder.restoreCallingIdentity(identity) }
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
            outState.processState = process.reportedProcState
            outState.importance = android.app.ActivityManager.RunningAppProcessInfo.procStateToImportance(process.reportedProcState)
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

    override fun getRunningAppProcesses(): MutableList<android.app.ActivityManager.RunningAppProcessInfo?>? =
        synchronized(lock) {
            processes.values.map { p ->
                android.app.ActivityManager.RunningAppProcessInfo().apply {
                    processName = p.processName; pid = p.pid; uid = p.uid
                    processState = p.reportedProcState
                    importance = android.app.ActivityManager.RunningAppProcessInfo.procStateToImportance(p.reportedProcState)
                }
            }.toMutableList<android.app.ActivityManager.RunningAppProcessInfo?>()
        }

    override fun getProcessesInErrorState(): MutableList<android.app.ActivityManager.ProcessErrorStateInfo?>? = null

    override fun getRunningExternalApplications(): MutableList<android.content.pm.ApplicationInfo?>? = mutableListOf()

    override fun getServices(maxNum: Int, flags: Int): MutableList<android.app.ActivityManager.RunningServiceInfo?>? =
        synchronized(lock) {
            servicesByComponent.values.take(maxNum).map { r ->
                android.app.ActivityManager.RunningServiceInfo().apply {
                    service = r.name; pid = r.app?.pid ?: 0; uid = r.entry.applicationInfo.uid
                    process = r.target.process.processName; started = r.startRequested
                    // ActiveServices.makeRunningServiceInfoLocked counts distinct
                    // connection Binder keys, not repeated binds on one dispatcher.
                    clientCount = r.connections.map { it.conn.asBinder() }.distinct().size
                    foreground = r.isForeground; crashCount = r.crashCount
                    if (r.isForeground) this.flags = this.flags or android.app.ActivityManager.RunningServiceInfo.FLAG_FOREGROUND
                    if (r.startRequested) this.flags = this.flags or android.app.ActivityManager.RunningServiceInfo.FLAG_STARTED
                    if (r.app?.pid == android.os.Process.myPid()) this.flags = this.flags or android.app.ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS
                    if (r.app != null && r.entry.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT != 0)
                        this.flags = this.flags or android.app.ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS
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

    /** Pinned AMS permission checks followed by AppStartInfoTracker.getStartInfo's
     * disabled-collection behavior. The generated android.app.app_start_info flag
     * is disabled in this image, so AOSP returns an empty list.
     *
     * Replacement blocker: AppStartInfoTracker.init requires stock AMS/ProcessList,
     * including CachedAppOptimizer/Freezer and LowMemDetector JNI. The closure
     * reports 507 sources and 73 blockers. Do not silently claim empty history if
     * collection is enabled before that stock process-service closure is wired. */
    override fun getHistoricalProcessStartReasons(packageName: String?, maxNum: Int,
        userId: Int): android.content.pm.ParceledListSlice<android.app.ApplicationStartInfo> {
        val uid = Binder.getCallingUid()
        if (UserHandle.isIsolated(uid)) throw SecurityException("Isolated process cannot query start history")
        require(userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) { "Unsupported userId" }
        com.android.server.LocalServices.getService(android.app.ActivityManagerInternal::class.java)
            .handleIncomingUser(Binder.getCallingPid(), uid, userId, true, android.app.ActivityManagerInternal.ALLOW_NON_FULL,
                "getHistoricalProcessStartReasons", null)
        val target = if (packageName.isNullOrEmpty()) uid else {
            val sandbox = android.os.Process.isSdkSandboxUid(uid) &&
                systemPackages { it.sdkSandboxPackageName } == packageName
            val packageUid = if (sandbox) uid else systemPackages {
                it.getPackageUid(packageName,
                    (android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong(), userId)
            } ?: -1
            if (UserHandle.getAppId(packageUid) != UserHandle.getAppId(uid))
                enforceCallingPermission(android.Manifest.permission.DUMP, "getHistoricalProcessStartReasons")
            packageUid
        }
        if (target != -1 && android.app.Flags.appStartInfo())
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("IActivityManager", "getHistoricalProcessStartReasons",
                "Enabled start-history collection requires stock AMS/ProcessList/AppStartInfoTracker")
        return android.content.pm.ParceledListSlice(emptyList())
    }

    /** Pinned AMS.getHistoricalProcessExitReasons permission order over host records. */
    override fun getHistoricalProcessExitReasons(packageName: String?, pid: Int, maxNum: Int,
        userId: Int): android.content.pm.ParceledListSlice<android.app.ApplicationExitInfo> {
        val uid = Binder.getCallingUid()
        if (UserHandle.isIsolated(uid)) throw SecurityException("Isolated process cannot query exit history")
        require(userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) { "Unsupported userId" }
        com.android.server.LocalServices.getService(android.app.ActivityManagerInternal::class.java)
            .handleIncomingUser(Binder.getCallingPid(), uid, userId, true, android.app.ActivityManagerInternal.ALLOW_NON_FULL,
                "getHistoricalProcessExitReasons", packageName)
        val filterUid = if (packageName.isNullOrEmpty()) uid else {
            val target = packageUid(packageName, UserHandle.USER_SYSTEM) ?: -1
            if (UserHandle.getAppId(target) != UserHandle.getAppId(uid)) {
                enforceCallingPermission(android.Manifest.permission.DUMP, "getHistoricalProcessExitReasons")
            }
            target
        }
        return android.content.pm.ParceledListSlice(synchronized(lock) {
            processHistory.query(packageName, filterUid, pid, maxNum)
        })
    }

    override fun setProcessStateSummary(state: ByteArray?) {
        require(state == null || state.size <= 128) { "Data size is too large" }
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        synchronized(lock) {
            processes.values.firstOrNull { it.pid == pid && it.uid == uid }?.let {
                processHistory.summary(it.binder, state)
            }
        }
    }

    @Volatile internal var userLifecycle: HostedUserLifecycle? = null

    /** UserController.unlockUser over the host's user lifecycle. */
    override fun unlockUser2(userId: Int, listener: android.os.IProgressListener?): Boolean {
        enforceCallingPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "unlockUser")
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return checkNotNull(userLifecycle) { "user lifecycle is not started" }.unlockUser(userId, listener)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
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
    /** Source: AMS.addPackageDependency / killPackageDependents and ProcessList's
     * dependency test. Track the calling process; provider changes terminate every
     * attached dependent so an old native library cannot survive a provider update. */
    override fun addPackageDependency(packageName: String?) {
        val name = packageName ?: return
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        if (pid == android.os.Process.myPid() || packageUid(name, UserHandle.getUserId(uid)) == -1) return
        synchronized(lock) {
            processes.values.firstOrNull { it.pid == pid && it.uid == uid }?.packageDependencies?.add(name)
        }
    }
    override fun killPackageDependents(packageName: String?, userId: Int) {
        HostedPermissions(checkNotNull(packageService)).enforce(android.Manifest.permission.KILL_UID, "killPackageDependents")
        val name = packageName ?: throw NullPointerException("Cannot kill dependents without a package name")
        require(userId == 0 || userId == UserHandle.USER_ALL) { "Unknown hosted user: $userId" }
        if (userId != UserHandle.USER_ALL && packageUid(name, userId) == -1) {
            throw IllegalArgumentException("Cannot kill dependents of non-existing package $name")
        }
        val targets = synchronized(lock) {
            processes.values.filter { name in it.packageDependencies || it.packageName == name }.onEach {
                it.exitReason = android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED
                it.exitDescription = "dep: $name"
            }.map { it.pid }
        }
        targets.forEach { android.os.Process.killProcess(it) }
    }
    override fun updateServiceGroup(connection: IServiceConnection?, group: Int, importance: Int) {}
    /** ServiceRecord.postNotification and ActiveServices cancellation policy.
     * The host retains the Android notification across process death, while NMS
     * owns its posted copy and its foreground flag. */
    private fun postForegroundNotificationLocked(record: ServiceRecord) {
        val app = record.app ?: return
        val notification = record.foregroundNotification ?: return
        if (record.isForeground) notificationService?.postForegroundServiceNotification(
            record.name.packageName, record.entry.applicationInfo.uid, app.pid, record.foregroundId, notification)
    }

    private fun hasOtherForegroundNotificationLocked(record: ServiceRecord): Boolean =
        servicesByToken.values.any { other -> other !== record && other.isForeground &&
            other.instance.userId == record.instance.userId && other.name.packageName == record.name.packageName &&
            other.foregroundId == record.foregroundId }

    private fun cancelForegroundNotificationLocked(record: ServiceRecord) {
        if (record.foregroundNotification == null || hasOtherForegroundNotificationLocked(record)) return
        notificationService?.cancelForegroundServiceNotification(record.name.packageName, record.foregroundId)
    }

    /** ActiveServices.setServiceForegroundInnerLocked: retain, remove and detach
     * have different notification lifetimes, including the pre-L legacy case. */
    override fun setServiceForeground(className: ComponentName?, token: IBinder?, id: Int, notification: android.app.Notification?, flags: Int, foregroundServiceType: Int): Unit = synchronized(lock) {
        val record = servicesByToken[token ?: return@synchronized] ?: return@synchronized
        if (id != 0) {
            requireNotNull(notification) { "null notification" }
            if (record.foregroundId != id) cancelForegroundNotificationLocked(record)
            notification.flags = notification.flags or android.app.Notification.FLAG_FOREGROUND_SERVICE
            record.foregroundId = id
            record.foregroundNotification = notification
            record.isForeground = true
            record.foregroundServiceType = if (foregroundServiceType == android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
                record.entry.foregroundServiceType else foregroundServiceType
            postForegroundNotificationLocked(record)
        } else if (record.isForeground) {
            if (flags and android.app.Service.STOP_FOREGROUND_REMOVE != 0) {
                cancelForegroundNotificationLocked(record)
                record.foregroundId = 0
                record.foregroundNotification = null
            } else if (record.entry.applicationInfo.targetSdkVersion >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // ActiveServices.dropFgsNotificationStateLocked keeps the flag
                // while another foreground service owns the same notification.
                if (!hasOtherForegroundNotificationLocked(record))
                    notificationService?.detachForegroundServiceNotification(record.name.packageName, record.foregroundId)
                if (flags and android.app.Service.STOP_FOREGROUND_DETACH != 0) {
                    record.foregroundId = 0
                    record.foregroundNotification = null
                }
            }
            record.isForeground = false
            record.foregroundServiceType = 0
        }
        updateWindowProcessStates()
    }
    override fun getForegroundServiceType(className: ComponentName?, token: IBinder?): Int = synchronized(lock) {
        servicesByToken[token]?.takeIf { it.name == className }?.foregroundServiceType ?: 0
    }
    /** AMS.killApplication posts KILL_APPLICATION_MSG, whose forceStopPackageLocked
     * kills the package's processes; their activity, service and provider
     * records are released by the binder-death path of each process. */
    override fun killApplication(pkg: String?, appId: Int, userId: Int, reason: String?, exitInfoReason: Int) {
        val name = pkg ?: return
        if (appId < 0) return
        val callerUid = Binder.getCallingUid()
        if (UserHandle.getAppId(callerUid) != android.os.Process.SYSTEM_UID) throw SecurityException("$callerUid cannot kill pkg: $name")
        importanceHandler.post { killPackageProcesses(name, appId, userId, exitInfoReason, "stop $name due to $reason") }
    }
    override fun killUid(appId: Int, userId: Int, reason: String?) {
        HostedPermissions(checkNotNull(packageService)).enforce(android.Manifest.permission.KILL_UID, "killUid")
        Binder.withCleanCallingIdentity {
            killPackageProcesses(null, appId, userId, android.app.ApplicationExitInfo.REASON_OTHER, reason ?: "kill uid",
                android.app.ApplicationExitInfo.SUBREASON_KILL_UID)
        }
    }
    override fun killUidForPermissionChange(appId: Int, userId: Int, reason: String?) {
        HostedPermissions(checkNotNull(packageService)).enforce(android.Manifest.permission.KILL_UID, "killUid")
        Binder.withCleanCallingIdentity {
            killPackageProcesses(null, appId, userId, android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE, reason ?: "kill uid")
        }
    }

    /** ProcessList.killPackageProcessesLSP selection: without a package, every
     * process of [appId] in [userId]; with one, processes of [appId] that load
     * it, and processes that depend on it. USER_ALL matches every user. */
    private fun killPackageProcesses(packageName: String?, appId: Int, userId: Int, reason: Int, description: String,
        subReason: Int = android.app.ApplicationExitInfo.SUBREASON_UNKNOWN) {
        val targets = synchronized(lock) {
            processes.values.filter { record ->
                val inUser = userId == UserHandle.USER_ALL || UserHandle.getUserId(record.uid) == userId
                val sameApp = appId < 0 || UserHandle.getAppId(record.uid) == appId
                record.pid != android.os.Process.myPid() && inUser && if (packageName == null) sameApp else {
                    val dependent = packageName in record.packageDependencies
                    (dependent || sameApp) && (record.packageName == packageName || dependent)
                }
            }.onEach {
                it.exitReason = reason
                it.exitSubReason = subReason
                it.exitDescription = description
            }.map { it.pid }
        }
        targets.forEach { android.os.Process.killProcess(it) }
    }
    override fun noteWakeupAlarm(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, sourcePkg: String?, tag: String?) {}
    /** AOSP AMS pins the render thread to the app's scheduling group; no scheduler on this host. */
    override fun setRenderThread(tid: Int) {}
    override fun noteAlarmStart(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    override fun noteAlarmFinish(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): Int =
        uriAccess.check(uri, pid, uid, mode, userId, false)
    override fun checkContentUriPermissionFull(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int): Int =
        uriAccess.check(uri, pid, uid, mode, userId, true)
    override fun checkUriPermissions(uris: MutableList<android.net.Uri?>?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): IntArray =
        uriAccess.checkMany(checkNotNull(uris), pid, uid, mode, userId)
    override fun grantUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) =
        uriAccess.grant(caller, targetPkg, uri, mode, userId)
    override fun revokeUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) =
        uriAccess.revoke(caller, targetPkg, uri, mode, userId)

    private fun uriGrants(): com.android.server.uri.UriGrantsManagerInternal =
        checkNotNull(com.android.server.LocalServices.getService(com.android.server.uri.UriGrantsManagerInternal::class.java))

    internal fun checkProviderUriPermission(uri: android.net.Uri, userId: Int, callingUid: Int, modeFlags: Int): Int =
        checkProviderUriPermissionFromHost(uri, userId, callingUid, modeFlags)

    private companion object {
        const val TAG = "HostedActivityManager"
    }
}
