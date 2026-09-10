package dev.artzig

import android.app.job.IJobService
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.IConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import dev.artzig.system.HostedConnectivityService
import dev.artzig.system.HostedJobSchedulerService
import dev.artzig.system.HostedNetworkSnapshot
import dev.artzig.system.HostedNetworkSource
import dev.artzig.system.HostedPackageManagerService
import java.util.concurrent.LinkedBlockingQueue

/** Stock network and JobService Binder protocols with deterministic network observations. */
internal object StockJobNetworkSmoke {
    private class JobContext(base: Context, private val owner: String, val handler: Handler) : ContextWrapper(base) {
        val events = LinkedBlockingQueue<Pair<String, JobParameters>>()
        var binds = 0
        var holdConnections = false
        var connectInline = false
        var finishOnStart = false
        val connections = LinkedBlockingQueue<Runnable>()
        var unbinds = 0
        override fun getOpPackageName(): String = owner
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        private val service = object : IJobService.Stub() {
            override fun startJob(params: JobParameters) {
                events.add("start" to params)
                params.callback.acknowledgeStartMessage(params.jobId, true)
                if (finishOnStart) params.callback.jobFinished(params.jobId, false)
            }
            override fun stopJob(params: JobParameters) {
                events.add("stop" to params)
                params.callback.acknowledgeStopMessage(params.jobId, false)
            }
            override fun onNetworkChanged(params: JobParameters) { events.add("network" to params) }
            override fun getTransferredDownloadBytes(params: JobParameters, work: JobWorkItem?) { error("unexpected byte query") }
            override fun getTransferredUploadBytes(params: JobParameters, work: JobWorkItem?) { error("unexpected byte query") }
        }
        private val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.transact(code, data, reply, flags)
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            val connected = Runnable { connection.onServiceConnected(checkNotNull(intent.component), remote) }
            if (holdConnections) connections.add(connected)
            else if (connectInline) connected.run()
            else handler.post(connected)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    private fun snapshot(id: Int, unmetered: Boolean): HostedNetworkSnapshot = HostedNetworkSnapshot(
        Network(id), NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "").apply {
            setIsAvailable(true); setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, "fixture0")
        }, NetworkCapabilities().apply {
            addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
            if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }, LinkProperties().apply { interfaceName = "fixture0" },
    )

    fun run(fixtureApk: String, pump: () -> Unit) {
        val resources = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle())).apply { installPlatform(resources) }
        val owner = packages.installPackage(fixtureApk)
        val handler = Handler(Looper.getMainLooper())
        val context = JobContext(HostContexts.systemContext(resources, 200, 200, 120, 0), owner, handler)
        var observed: HostedNetworkSnapshot? = snapshot(100, false)
        val networkService = HostedConnectivityService(context, packages, HostedNetworkSource { observed }, handler)
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                networkService.transact(code, data, reply, flags)
        }
        val manager = ConnectivityManager(context, IConnectivityManager.Stub.asInterface(remote))
        val scheduler = HostedJobSchedulerService(context).apply { packageService = packages }
        val component = ComponentName(owner, "dev.artzig.systemtest.AJobService")
        fun await(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!predicate() && SystemClock.uptimeMillis() < deadline) { pump(); Thread.sleep(1) }
            check(predicate()) { "Timed out waiting for job network state" }
        }
        fun event(kind: String, id: Int): JobParameters {
            await { context.events.isNotEmpty() }
            val result = checkNotNull(context.events.poll())
            check(result.first == kind && result.second.jobId == id) { "Unexpected job event $result" }
            return result.second
        }
        fun finish(params: JobParameters) {
            params.callback.jobFinished(params.jobId, false)
            await { scheduler.getPendingJob(null, params.jobId) == null }
        }
        scheduler.startNetworkTracking(manager, handler)
        try {
            pump()
            // A network exists, but it does not satisfy this job's unmetered constraint.
            check(scheduler.schedule(null, JobInfo.Builder(1, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build()) == JobScheduler.RESULT_SUCCESS)
            check(scheduler.getPendingJobReason(null, 1) == JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY)
            check(context.binds == 0)
            observed = snapshot(100, true)
            networkService.refresh(); pump()
            val required = checkNotNull(checkNotNull(scheduler.getPendingJob(null, 1)).requiredNetwork)
            val capabilities = checkNotNull(manager.getNetworkCapabilities(Network(100)))
            check(required.canBeSatisfiedBy(capabilities)) { "$required does not match $capabilities" }
            val started = event("start", 1)
            check(started.network == Network(100))
            // Capability changes on the same assigned network do not send a network switch.
            observed = snapshot(100, true).let { it.copy(capabilities = NetworkCapabilities(it.capabilities)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) }
            networkService.refresh(); pump()
            check(context.events.isEmpty())
            observed = null
            networkService.refresh(); pump()
            val stopped = event("stop", 1)
            check(stopped.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            await { scheduler.getPendingJob(null, 1) == null }
            check(context.unbinds == 1)

            // A deadline may run a connectivity job without a currently usable network.
            scheduler.schedule(null, JobInfo.Builder(2, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(100).build())
            val deadlineJob = event("start", 2)
            check(deadlineJob.network == null && deadlineJob.isOverrideDeadlineExpired)
            finish(deadlineJob)

            scheduler.schedule(null, JobInfo.Builder(3, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            check(scheduler.getPendingJobReason(null, 3) == JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY)
            observed = snapshot(101, true)
            networkService.refresh(); pump()
            val reconnected = event("start", 3)
            check(reconnected.network == Network(101))
            finish(reconnected)

            scheduler.schedule(null, JobInfo.Builder(4, component).build())
            val local = event("start", 4)
            check(local.network == null)
            finish(local)
            check(context.events.isEmpty())
            check(context.binds == 4 && context.unbinds == 4)

            // Losing the network while binding must reject a later service-connected callback.
            context.holdConnections = true
            scheduler.schedule(null, JobInfo.Builder(5, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            await { context.connections.isNotEmpty() }
            observed = null
            networkService.refresh(); pump()
            await { scheduler.getPendingJob(null, 5) == null }
            checkNotNull(context.connections.poll()).run()
            check(context.events.isEmpty() && context.unbinds == 5)

            // A local service may complete before bindService returns to the scheduler.
            context.holdConnections = false
            context.connectInline = true
            context.finishOnStart = true
            scheduler.schedule(null, JobInfo.Builder(6, component).build())
            event("start", 6)
            await { scheduler.getPendingJob(null, 6) == null }
            check(context.binds == 6 && context.unbinds == 6)
        } finally {
            scheduler.cancelAll()
            scheduler.stopNetworkTracking()
        }
        System.err.println("stock-job-network: PASS capability constraints, assigned network, loss/stop, reconnect, deadline and local jobs")
    }
}
