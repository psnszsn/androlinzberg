package dev.artzig.system

import android.app.ContentProviderHolder
import android.app.IApplicationThread
import android.app.IServiceConnection
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
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
class HostedActivityManagerService : UnsupportedActivityManager() {
    /** Launches a process for a package that is not attached (production: the launcher's --app-process). */
    fun interface ProcessLauncher {
        /** Returns false when the package cannot be launched. */
        fun launch(packageName: String, apkPath: String): Boolean
    }

    private inner class ProcessRecord(
        val thread: IApplicationThread,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
    ) : IBinder.DeathRecipient {
        val binder: IBinder = thread.asBinder()
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
    private val servicesByComponent = LinkedHashMap<ComponentName, ServiceRecord>()
    private val servicesByToken = LinkedHashMap<IBinder, ServiceRecord>()
    /** Bind/start operations waiting for a package's process to attach. */
    private val pendingByPackage = LinkedHashMap<String, MutableList<() -> Unit>>()
    /** Published content providers, one record per declared authority
     * (source note: ActivityManagerService.mProviderMap). */
    private val providersByAuthority = LinkedHashMap<String, ContentProviderRecord>()
    /** getContentProvider calls blocked until the owning package publishes. */
    private val providerWaiters = LinkedHashMap<String, MutableList<ProviderWaiter>>()
    @Volatile var processLauncher: ProcessLauncher? = null
    /** The session's package manager; consulted for APK paths of packages with no running process. */
    @Volatile var packageService: HostedPackageManagerService? = null

    // ------------------------------------------------------------- attach

    /** uid -> (package, process name) announced through IHostedInstaller.bindProcessPackage
     * (AOSP learns both from the zygote fork it requested; no zygote here). */
    private val announced = HashMap<Int, Pair<String, String>>()

    internal fun noteProcessPackage(uid: Int, packageName: String, processName: String) {
        synchronized(lock) { announced[uid] = packageName to processName }
    }

    override fun attachApplication(app: IApplicationThread?, startSeq: Long) {
        val thread = checkNotNull(app) { "attachApplication without a thread" }
        val callingUid = Binder.getCallingUid()
        val (packageName, processName) = synchronized(lock) { announced[callingUid] }
            ?: if (callingUid == android.os.Process.SYSTEM_UID) ("android" to "system_server")
            else throw SecurityException("uid " + callingUid + " attached without announcing its package")
        val record = ProcessRecord(
            thread, Binder.getCallingPid(), callingUid, packageName, processName,
        )
        val drained: List<() -> Unit>
        // Insert before linking so a death in the window between the two runs
        // handleProcessDied against a record that is actually registered.
        synchronized(lock) {
            processes[record.binder] = record
            drained = pendingByPackage.remove(packageName) ?: emptyList()
        }
        try {
            record.binder.linkToDeath(record, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "process died before attach completed: " + packageName)
            handleProcessDied(record)
            return
        }
        Log.i(TAG, "attached " + packageName + " pid=" + record.pid + " uid=" + record.uid)
        // Replay bind/start operations that were waiting for this process. Each
        // op re-locks internally and performs its Binder callbacks lock-free.
        for (op in drained) op()
    }

    private fun handleProcessDied(record: ProcessRecord) {
        val notify = ArrayList<Pair<ConnectionRecord, ServiceRecord>>()
        synchronized(lock) {
            processes.remove(record.binder)
            // Services hosted by the dead process: tell every connection (ActiveServices.killServicesLocked).
            val dead = servicesByComponent.values.filter { it.app === record }
            for (service in dead) {
                for (connection in service.connections.values) notify.add(connection to service)
                servicesByComponent.remove(service.name)
                servicesByToken.remove(service.token)
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
    }

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

    internal fun checkUidPermission(permission: String, uid: Int): Int =
        packageService?.checkUidPermission(permission, uid)
            ?: android.content.pm.PackageManager.PERMISSION_DENIED

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
            receiver, filter, callerPackage, Binder.getCallingUid(), requiredPermission, notExported,
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
            callingPackage, Binder.getCallingUid(),
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
        outInfo.availMem = 1L shl 31; outInfo.totalMem = 1L shl 32; outInfo.threshold = 1L shl 28; outInfo.lowMemory = false
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
    /** Foreground state is a notification concern; the host has no notification shade. */
    override fun setServiceForeground(className: ComponentName?, token: IBinder?, id: Int, notification: android.app.Notification?, flags: Int, foregroundServiceType: Int) {}
    override fun getForegroundServiceType(className: ComponentName?, token: IBinder?): Int = 0
    override fun refContentProvider(connection: IBinder?, stableDelta: Int, unstableDelta: Int): Boolean = true
    override fun removeContentProvider(connection: IBinder?, stable: Boolean) {}
    override fun killApplication(pkg: String?, appId: Int, userId: Int, reason: String?, exitInfoReason: Int) {}
    override fun noteWakeupAlarm(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, sourcePkg: String?, tag: String?) {}
    override fun noteAlarmStart(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    override fun noteAlarmFinish(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) {}
    // URI grants are not tracked yet (android.app core Phase 4 seam): nothing is granted.
    override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): Int =
        android.content.pm.PackageManager.PERMISSION_DENIED
    override fun checkContentUriPermissionFull(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int): Int =
        android.content.pm.PackageManager.PERMISSION_DENIED
    override fun checkUriPermissions(uris: MutableList<android.net.Uri?>?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: IBinder?): IntArray? =
        IntArray(uris?.size ?: 0) { android.content.pm.PackageManager.PERMISSION_DENIED }
    override fun grantUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) {}
    override fun revokeUriPermission(caller: IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) {}

    private companion object {
        const val TAG = "HostedActivityManager"
    }
}
