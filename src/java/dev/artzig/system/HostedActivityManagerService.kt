package dev.artzig.system

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
class HostedActivityManagerService : IHostedActivityManager.Stub() {
    /** Launches a process for a package that is not attached (production: the launcher's --app-process). */
    fun interface ProcessLauncher {
        /** Returns false when the package cannot be launched. */
        fun launch(packageName: String, apkPath: String): Boolean
    }

    private inner class ProcessRecord(
        val thread: IHostedApplicationThread,
        val pid: Int,
        val uid: Int,
        val manifest: HostedProcessManifest,
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

    private inner class ServiceRecord(val name: ComponentName, val entry: HostedProcessManifest.ServiceEntry) {
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
    /** package -> APK path, for respawning; filled from manifests. */
    private val packagePaths = LinkedHashMap<String, String>()
    /** Bind/start operations waiting for a package's process to attach. */
    private val pendingByPackage = LinkedHashMap<String, MutableList<() -> Unit>>()
    @Volatile var processLauncher: ProcessLauncher? = null
    /** The session's package manager; consulted for APK paths of packages with no running process. */
    @Volatile var packageService: HostedPackageManagerService? = null

    // ------------------------------------------------------------- attach

    override fun attachApplication(thread: IHostedApplicationThread, manifest: HostedProcessManifest) {
        val callingUid = Binder.getCallingUid()
        // Interim identity lock (full per-connection credential auth is a
        // follow-up): a process may only attach as a package whose known uid
        // is its own, and may not attach a package another uid already holds.
        val knownUid = packageService?.getPackageUid(manifest.packageName) ?: -1
        if (knownUid != -1 && knownUid != callingUid && callingUid != android.os.Process.SYSTEM_UID) {
            throw SecurityException(
                "uid " + callingUid + " may not attach as " + manifest.packageName + " (uid " + knownUid + ")",
            )
        }
        val record = ProcessRecord(thread, Binder.getCallingPid(), callingUid, manifest)
        val drained: List<() -> Unit>
        // Insert before linking so a death in the window between the two runs
        // handleProcessDied against a record that is actually registered.
        synchronized(lock) {
            val conflict = processes.values.firstOrNull {
                it.manifest.packageName == manifest.packageName && it.uid != callingUid
            }
            if (conflict != null) {
                throw SecurityException(
                    "package " + manifest.packageName + " already attached under uid " + conflict.uid,
                )
            }
            processes[record.binder] = record
            manifest.apkPath?.let { packagePaths[manifest.packageName] = it }
            drained = pendingByPackage.remove(manifest.packageName) ?: emptyList()
        }
        try {
            record.binder.linkToDeath(record, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "process died before attach completed: " + manifest.packageName)
            handleProcessDied(record)
            return
        }
        Log.i(TAG, "attached " + manifest.packageName + " pid=" + record.pid + " uid=" + record.uid)
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
        }
        for ((connection, service) in notify) {
            try {
                connection.conn.connected(service.name, null, true)
            } catch (e: RemoteException) {
                // The client is gone too; its own death handling cleans up.
            }
        }
        Log.i(TAG, "process died: " + record.manifest.packageName + " pid=" + record.pid)
    }

    // ---------------------------------------------------------- resolution

    private fun resolveServiceLocked(service: Intent, callingPackage: String?): Pair<ProcessRecord, HostedProcessManifest.ServiceEntry>? {
        val component = service.component
        for (process in processes.values) {
            val manifest = process.manifest
            if (component != null) {
                if (manifest.packageName != component.packageName) continue
                val entry = manifest.services.firstOrNull { it.className == component.className } ?: continue
                return process to entry
            }
            if (service.`package` != null && service.`package` != manifest.packageName) continue
            val action = service.action ?: continue
            val entry = manifest.services.firstOrNull { it.actions.contains(action) } ?: continue
            return process to entry
        }
        return null
    }

    /** The package that would host this service intent, whether or not attached. */
    private fun targetPackageForLocked(service: Intent): String? {
        service.component?.let { return it.packageName }
        return packageService?.queryIntentServices(service, 0)?.firstOrNull()?.serviceInfo?.packageName
    }

    /** Not attached: spawn the package's process; the pending op replays on attach. Requires `lock`. */
    private fun bringUpProcessLocked(packageName: String): Boolean {
        val launcher = processLauncher ?: return false
        val apkPath = packagePaths[packageName] ?: packageService?.getApkPath(packageName) ?: return false
        return launcher.launch(packageName, apkPath)
    }

    /**
     * Access gate before a bind/start reaches a service (source note:
     * ActiveServices bind permission + exported check). A non-exported service
     * is reachable only from its own uid (or system); a declared permission
     * must be held by the caller.
     */
    private fun checkAccessLocked(
        entry: HostedProcessManifest.ServiceEntry,
        ownerUid: Int,
        callingUid: Int,
    ): Boolean {
        if (!entry.exported && callingUid != ownerUid && callingUid != android.os.Process.SYSTEM_UID) {
            return false
        }
        val permission = entry.permission ?: return true
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == ownerUid) return true
        return processes.values.any { it.uid == callingUid && it.manifest.grantedPermissions.contains(permission) }
    }

    private fun serviceForLocked(process: ProcessRecord, entry: HostedProcessManifest.ServiceEntry): ServiceRecord {
        val name = ComponentName(process.manifest.packageName, entry.className)
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

    override fun startService(service: Intent, callingPackage: String?): ComponentName? {
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
                    ?: packageService?.queryIntentServices(service, 0)?.firstOrNull()?.serviceInfo
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
            if (create) process.thread.scheduleCreateService(record.token, record.name)
            process.thread.scheduleServiceArgs(record.token, startId, 0, service)
        }, record.name, 1)
    }

    override fun stopService(service: Intent): Int {
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val component = service.component
                ?: resolveServiceLocked(service, null)?.let { ComponentName(it.first.manifest.packageName, it.second.className) }
                ?: return 0
            val record = servicesByComponent[component] ?: return 0
            record.startRequested = false
            schedule = bringDownIfNeededLocked(record)
        }
        schedule?.invoke()
        return 1
    }

    override fun stopServiceToken(className: ComponentName, token: IBinder?, startId: Int): Boolean {
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            val record = servicesByComponent[className] ?: return false
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

    override fun bindService(
        caller: IHostedApplicationThread?,
        service: Intent,
        connection: IServiceConnection,
        flags: Int,
        callingPackage: String?,
    ): Int {
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
                schedule = { process.thread.scheduleBindService(record.token, binding.intent, true) }
            }
            return PostBind(schedule, record.name, true, binding.binder, 1)
        }
        if (!binding.requested && record.created) {
            binding.requested = true
            val rebind = binding.doRebind
            binding.doRebind = false
            return PostBind({
                if (create) process.thread.scheduleCreateService(record.token, record.name)
                process.thread.scheduleBindService(record.token, binding.intent, rebind)
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

    override fun unbindService(connection: IServiceConnection): Boolean {
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

    override fun publishService(token: IBinder, intent: Intent, service: IBinder?) {
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

    override fun unbindFinished(token: IBinder, intent: Intent) {
        synchronized(lock) {
            val record = servicesByToken[token] ?: return
            record.bindings[FilterComparison(intent)]?.doRebind = true
        }
    }

    override fun serviceDoneExecuting(token: IBinder?, type: Int, startId: Int, res: Int) {
        // Bookkeeping only in v1 (no ANR timers, no START_NOT_STICKY policy).
    }

    // ---------------------------------------------------------- broadcasts

    private val broadcasts = HostedBroadcastQueue(this)

    internal fun checkUidPermission(permission: String, uid: Int): Int =
        packageService?.checkUidPermission(permission, uid)
            ?: android.content.pm.PackageManager.PERMISSION_DENIED

    override fun registerReceiver(
        receiver: android.content.IIntentReceiver?,
        filter: android.content.IntentFilter,
        callingPackage: String?,
        broadcastPermission: String?,
        flags: Int,
    ): Intent? {
        val notExported = (flags and android.content.Context.RECEIVER_NOT_EXPORTED) != 0
        return broadcasts.registerReceiver(
            receiver, filter, callingPackage, Binder.getCallingUid(), broadcastPermission, notExported,
        )
    }

    override fun unregisterReceiver(receiver: android.content.IIntentReceiver) =
        broadcasts.unregisterReceiver(receiver)

    override fun broadcastIntent(
        intent: Intent,
        resultTo: android.content.IIntentReceiver?,
        resultCode: Int,
        requiredPermission: String?,
        ordered: Boolean,
        sticky: Boolean,
        callingPackage: String?,
    ): Int = broadcasts.broadcastIntent(
        intent, resultTo, resultCode, requiredPermission, ordered, sticky, callingPackage, Binder.getCallingUid(),
    )

    override fun finishReceiver(
        who: android.content.IIntentReceiver,
        resultCode: Int,
        resultData: String?,
        resultExtras: android.os.Bundle?,
        abortBroadcast: Boolean,
    ) = broadcasts.finishReceiver(who, resultCode, resultData, resultExtras, abortBroadcast)

    override fun removeStickyBroadcast(intent: Intent) = broadcasts.removeSticky(intent)

    private companion object {
        const val TAG = "HostedActivityManager"
    }
}
