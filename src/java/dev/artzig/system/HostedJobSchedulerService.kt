package dev.artzig.system

import android.app.job.IJobCallback
import android.app.job.IJobService
import android.app.job.IUserVisibleJobObserver
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobSnapshot
import android.app.job.JobWorkItem
import android.app.job.PendingJobReasonsInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The session's job scheduler over the real `IJobScheduler` surface,
 * published as `Context.JOB_SCHEDULER_SERVICE`.
 *
 * Source note: `com.android.server.job.JobSchedulerService` (`scheduleAsPackage`,
 * `enforceValidJobRequest`, `cancelJobImplLocked`, `onJobCompletedLocked`,
 * `getRescheduleJobForFailureLocked`, `getRescheduleJobForPeriodic`),
 * `JobStatus` (run-time window, work queue, `stopTrackingJobLocked` work
 * transfer) and `JobServiceContext` (bind flags, `JobParameters`, the
 * `IJobCallback` protocol: start acknowledgement, `dequeueWork`,
 * `completeWork`, `jobFinished`, stop reasons and timeouts). The AOSP service
 * evaluates constraints through a dozen controllers (connectivity, idle,
 * battery, quota, standby); this host reduces them to what it knows:
 * charging, battery and storage are always satisfied; connectivity follows
 * the networks and capabilities supplied by ConnectivityManager. Device idle
 * and content triggers are never satisfied, so those jobs need a deadline. Jobs
 * execute by binding the app's JobService through the activity manager from
 * the system context, exactly as `JobServiceContext` does.
 */
class HostedJobSchedulerService(private val context: Context) : android.app.job.IJobScheduler.Stub(), android.app.job.IJobScheduler by unsupported<android.app.job.IJobScheduler>() {
    override fun asBinder(): android.os.IBinder = this

    @Volatile var packageService: HostedPackageManagerService? = null
    @Volatile var notificationService: HostedNotificationManagerService? = null

    private data class JobKey(val uid: Int, val namespace: String?, val jobId: Int)

    /** JobStatus: a scheduled job with its run-time window, work queue and execution state. */
    private inner class JobStatus(
        val job: JobInfo,
        val namespace: String?,
        val uid: Int,
        val packageName: String,
        val numFailures: Int,
        val earliestRunTimeElapsed: Long,
        val latestRunTimeElapsed: Long,
    ) {
        val key = JobKey(uid, namespace, job.id)
        val pendingWork = ArrayDeque<JobWorkItem>()
        val executingWork = ArrayList<JobWorkItem>()
        var nextPendingWorkId = 1
        var running: RunningJob? = null
        var timer: ScheduledFuture<*>? = null

        fun enqueueWorkLocked(work: JobWorkItem) {
            work.workId = nextPendingWorkId++
            pendingWork.add(work)
        }

        fun dequeueWorkLocked(): JobWorkItem? {
            val work = pendingWork.removeFirstOrNull() ?: return null
            work.bumpDeliveryCount()
            executingWork.add(work)
            return work
        }

        fun completeWorkLocked(workId: Int): Boolean = executingWork.removeAll { it.workId == workId }

        fun hasWorkLocked(): Boolean = pendingWork.isNotEmpty() || executingWork.isNotEmpty()

        /** The constraints the host can satisfy; deadline and timing are evaluated separately. */
        fun unsatisfiedConstraintReason(): Int = when {
            job.isRequireDeviceIdle -> JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE
            job.triggerContentUris?.isNotEmpty() == true -> JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER
            job.requiredNetwork != null && networkFor(job) == null -> JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY
            else -> JobScheduler.PENDING_JOB_REASON_UNDEFINED
        }

        /** When the job becomes runnable: its earliest time when the constraints hold, else its deadline. */
        fun readyTimeElapsed(): Long =
            if (unsatisfiedConstraintReason() == JobScheduler.PENDING_JOB_REASON_UNDEFINED) earliestRunTimeElapsed
            else latestRunTimeElapsed

        fun pendingReason(nowElapsed: Long): Int {
            if (running != null) return JobScheduler.PENDING_JOB_REASON_EXECUTING
            val constraint = unsatisfiedConstraintReason()
            if (constraint != JobScheduler.PENDING_JOB_REASON_UNDEFINED && nowElapsed < latestRunTimeElapsed) return constraint
            if (nowElapsed < earliestRunTimeElapsed) return JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY
            return JobScheduler.PENDING_JOB_REASON_UNDEFINED
        }
    }

    private val lock = Any()
    private val jobs = LinkedHashMap<JobKey, JobStatus>()
    private val observers = ArrayList<IUserVisibleJobObserver>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "JobScheduler").apply { isDaemon = true }
    }

    private val networks = LinkedHashMap<Network, NetworkCapabilities>()
    private var connectivity: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworks(network, capabilities)
        }
        override fun onLost(network: Network) { updateNetworks(network, null) }
    }

    /** ConnectivityController: observe usable network capabilities before scheduling app jobs.
     * The hosted connectivity service owns Linux discovery; this service owns job constraints,
     * the assigned JobParameters network, and callbacks when that assignment changes. */
    fun startNetworkTracking(manager: ConnectivityManager,
        handler: Handler = com.android.internal.os.BackgroundThread.getHandler()) {
        check(connectivity == null) { "Job network tracking already started" }
        connectivity = manager
        for (network in manager.allNetworks) {
            manager.getNetworkCapabilities(network)?.let { updateNetworks(network, it) }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), networkCallback, handler)
    }

    fun stopNetworkTracking() {
        connectivity?.unregisterNetworkCallback(networkCallback)
        connectivity = null
    }

    /** Requires lock; copy the chosen Network because JobParameters is a parcelable boundary. */
    private fun networkFor(job: JobInfo): Network? {
        val request = job.requiredNetwork ?: return null
        return networks.entries.firstOrNull { request.canBeSatisfiedBy(it.value) }?.key?.let(::Network)
    }

    private fun updateNetworks(network: Network, capabilities: NetworkCapabilities?) {
        val changes = ArrayList<() -> Unit>()
        synchronized(lock) {
            if (capabilities == null) networks.remove(network)
            else networks[Network(network)] = NetworkCapabilities(capabilities)
            val now = SystemClock.elapsedRealtime()
            for (status in jobs.values) {
                val run = status.running
                if (run == null) {
                    scheduleTimerLocked(status, now)
                } else if (status.job.requiredNetwork != null) {
                    val assigned = networkFor(status.job)
                    if (assigned == null) {
                        changes.add { run.stop(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY,
                            JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                            "connectivity constraint no longer satisfied") }
                    } else { changes.add { run.networkChanged(assigned) } }
                }
            }
        }
        // A remote JobService may synchronously call back; never hold the scheduler lock here.
        changes.forEach { executor.execute(it) }
    }

    // ------------------------------------------------------------ scheduling (JobSchedulerService)

    /** JobSchedulerService.enforceValidJobRequest. */
    private fun enforceValidJobRequest(uid: Int, job: JobInfo) {
        val service: ComponentName = job.service
        val info = packageService?.getServiceInfo(service, (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong(), 0)
            ?: throw IllegalArgumentException("Tried to schedule job for non-existent component: $service")
        if (info.applicationInfo.uid != uid) {
            throw IllegalArgumentException("uid $uid cannot schedule job in ${service.packageName}")
        }
        if (JobService.PERMISSION_BIND != info.permission) {
            throw IllegalArgumentException("Scheduled service $service does not require android.permission.BIND_JOB_SERVICE permission")
        }
        if (job.isPersisted && packageService?.checkUidPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED, uid) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalArgumentException("Requested job cannot be persisted without holding android.permission.RECEIVE_BOOT_COMPLETED permission")
        }
        if (job.requiredNetwork != null && !canAccessJobNetwork(uid, service.packageName)) {
            throw SecurityException("android.permission.ACCESS_NETWORK_STATE required for connectivity jobs")
        }
    }

    // Pinned JobSchedulerService change 271850009 is enabled after target SDK 33.
    // This hosted scheduler applies that default SDK gate; it does not consume server-only
    // compat metadata from the uncompiled JobSchedulerService implementation.
    private fun canAccessJobNetwork(uid: Int, packageName: String): Boolean {
        val app = packageService?.getApplicationInfo(packageName, 0, 0) ?: return false
        return app.targetSdkVersion <= android.os.Build.VERSION_CODES.TIRAMISU ||
            packageService?.checkUidPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, uid) == PackageManager.PERMISSION_GRANTED
    }

    private fun callingPackageFor(uid: Int, job: JobInfo): String =
        packageService?.getPackagesForUid(uid)?.firstOrNull { it == job.service.packageName }
            ?: job.service.packageName

    /** JobStatus.createFromJobInfo: the run-time window of a freshly scheduled job. */
    private fun newStatus(job: JobInfo, namespace: String?, uid: Int, packageName: String, nowElapsed: Long): JobStatus {
        val earliest: Long
        val latest: Long
        if (job.isPeriodic) {
            latest = nowElapsed + job.intervalMillis
            earliest = latest - job.flexMillis
        } else {
            earliest = if (job.minLatencyMillis > 0) nowElapsed + job.minLatencyMillis else NO_EARLIEST_RUNTIME
            latest = if (job.maxExecutionDelayMillis > 0) nowElapsed + job.maxExecutionDelayMillis else NO_LATEST_RUNTIME
        }
        return JobStatus(job, namespace, uid, packageName, 0, earliest, latest)
    }

    private fun scheduleInternal(namespace: String?, job: JobInfo, work: JobWorkItem?, uid: Int, packageName: String): Int {
        val nowElapsed = SystemClock.elapsedRealtime()
        var stop: (() -> Unit)? = null
        synchronized(lock) {
            if (jobs.values.count { it.uid == uid } >= MAX_JOBS_PER_APP && !jobs.containsKey(JobKey(uid, namespace, job.id))) {
                Log.w(TAG, "Too many jobs for uid $uid")
                throw IllegalStateException("Apps may not schedule more than $MAX_JOBS_PER_APP distinct jobs")
            }
            val key = JobKey(uid, namespace, job.id)
            val existing = jobs[key]
            if (existing != null && work != null && existing.job == job) {
                // JobSchedulerService.enqueue: same job, more work; a running
                // service picks it up on its next dequeueWork.
                existing.enqueueWorkLocked(work)
                if (existing.running == null) scheduleTimerLocked(existing, nowElapsed)
                return JobScheduler.RESULT_SUCCESS
            }
            val status = newStatus(job, namespace, uid, packageName, nowElapsed)
            if (work != null) status.enqueueWorkLocked(work)
            if (existing != null) {
                // cancelJobImplLocked(toCancel, incomingJob): the replacement takes the
                // pending work (JobStatus.stopTrackingJobLocked) and the old run stops.
                status.pendingWork.addAll(existing.pendingWork)
                status.nextPendingWorkId = existing.nextPendingWorkId
                stop = removeLocked(existing, JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.INTERNAL_STOP_REASON_CANCELED, "job rescheduled by app")
            }
            jobs[key] = status
            scheduleTimerLocked(status, nowElapsed)
        }
        stop?.invoke()
        Log.i(TAG, "schedule: pkg=$packageName id=${job.id} ns=$namespace service=${job.service.shortClassName} periodic=${job.isPeriodic} work=${work != null}")
        return JobScheduler.RESULT_SUCCESS
    }

    override fun schedule(namespace: String?, job: JobInfo?): Int {
        requireNotNull(job) { "job is null" }
        val uid = Binder.getCallingUid()
        enforceValidJobRequest(uid, job)
        return scheduleInternal(namespace, job, null, uid, callingPackageFor(uid, job))
    }

    override fun enqueue(namespace: String?, job: JobInfo?, work: JobWorkItem?): Int {
        requireNotNull(job) { "job is null" }
        requireNotNull(work) { "work is null" }
        val uid = Binder.getCallingUid()
        enforceValidJobRequest(uid, job)
        if (job.isPersisted) throw IllegalArgumentException("Can't enqueue work for persisted jobs")
        return scheduleInternal(namespace, job, work, uid, callingPackageFor(uid, job))
    }

    override fun scheduleAsPackage(namespace: String?, job: JobInfo?, packageName: String?, userId: Int, tag: String?): Int {
        requireNotNull(job) { "job is null" }
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("Caller uid ${Binder.getCallingUid()} not permitted to schedule jobs for other apps")
        }
        val uid = packageService?.getPackageUidInternal(packageName ?: job.service.packageName) ?: Process.SYSTEM_UID
        enforceValidJobRequest(uid, job)
        return scheduleInternal(namespace, job, null, uid, packageName ?: job.service.packageName)
    }

    /** Arms (or re-arms) the timer that starts the job once its window opens. Requires `lock`. */
    private fun scheduleTimerLocked(status: JobStatus, nowElapsed: Long) {
        status.timer?.cancel(false)
        val at = status.readyTimeElapsed()
        if (at == NO_LATEST_RUNTIME) {
            status.timer = null // never runnable on this host (idle/content-trigger job without a deadline)
            return
        }
        status.timer = executor.schedule({ startIfReady(status) }, Math.max(0L, at - nowElapsed), TimeUnit.MILLISECONDS)
    }

    private fun startIfReady(status: JobStatus) {
        val run: RunningJob
        synchronized(lock) {
            if (jobs[status.key] !== status || status.running != null) return
            if (SystemClock.elapsedRealtime() < status.readyTimeElapsed()) {
                scheduleTimerLocked(status, SystemClock.elapsedRealtime())
                return
            }
            run = RunningJob(status)
            status.running = run
        }
        if (!run.bind()) {
            onJobCompleted(status, run, reschedule = true, failed = true)
        }
    }

    /** Removes the job from the table and returns the stop to deliver outside the lock. */
    private fun removeLocked(status: JobStatus, stopReason: Int, internalStopReason: Int, debugReason: String): (() -> Unit)? {
        jobs.remove(status.key)
        status.timer?.cancel(false)
        val run = status.running ?: return null
        return { run.stop(stopReason, internalStopReason, debugReason) }
    }

    override fun cancel(namespace: String?, jobId: Int) {
        val uid = Binder.getCallingUid()
        val stop = synchronized(lock) {
            val status = jobs[JobKey(uid, namespace, jobId)] ?: return
            removeLocked(status, JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.INTERNAL_STOP_REASON_CANCELED, "cancel() called by app, callingUid=$uid, uid=$uid, jobId=$jobId")
        }
        stop?.invoke()
        Log.i(TAG, "cancel: uid=$uid id=$jobId ns=$namespace")
    }

    private fun cancelAllForUid(uid: Int, namespace: String?, all: Boolean) {
        val stops = synchronized(lock) {
            jobs.values.filter { it.uid == uid && (all || it.namespace == namespace) }.mapNotNull {
                removeLocked(it, JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.INTERNAL_STOP_REASON_CANCELED, "cancelAll() called by app, callingUid=$uid, uid=$uid")
            }
        }
        for (stop in stops) stop()
    }

    override fun cancelAll() = cancelAllForUid(Binder.getCallingUid(), null, true)
    override fun cancelAllInNamespace(namespace: String?) = cancelAllForUid(Binder.getCallingUid(), namespace, false)

    // ------------------------------------------------------------ queries

    override fun getAllPendingJobs(): MutableMap<String?, ParceledListSlice<JobInfo>?>? {
        val uid = Binder.getCallingUid()
        return synchronized(lock) {
            val byNamespace = LinkedHashMap<String?, ArrayList<JobInfo>>()
            for (status in jobs.values) if (status.uid == uid) byNamespace.getOrPut(status.namespace) { ArrayList() }.add(status.job)
            byNamespace.mapValuesTo(LinkedHashMap<String?, ParceledListSlice<JobInfo>?>()) { ParceledListSlice(it.value) }
        }
    }

    override fun getAllPendingJobsInNamespace(namespace: String?): ParceledListSlice<JobInfo>? {
        val uid = Binder.getCallingUid()
        return synchronized(lock) { ParceledListSlice(jobs.values.filter { it.uid == uid && it.namespace == namespace }.map { it.job }) }
    }

    override fun getPendingJob(namespace: String?, jobId: Int): JobInfo? =
        synchronized(lock) { jobs[JobKey(Binder.getCallingUid(), namespace, jobId)]?.job }

    override fun getPendingJobReason(namespace: String?, jobId: Int): Int = synchronized(lock) {
        jobs[JobKey(Binder.getCallingUid(), namespace, jobId)]?.pendingReason(SystemClock.elapsedRealtime())
            ?: JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID
    }

    override fun getPendingJobReasons(namespace: String?, jobId: Int): IntArray? = intArrayOf(getPendingJobReason(namespace, jobId))

    override fun getPendingJobReasonsHistory(namespace: String?, jobId: Int): MutableList<PendingJobReasonsInfo?>? = ArrayList()

    override fun canRunUserInitiatedJobs(packageName: String?): Boolean = true

    override fun hasRunUserInitiatedJobsPermission(packageName: String?, userId: Int): Boolean {
        val uid = packageService?.getPackageUidInternal(packageName ?: return false) ?: return false
        return packageService?.checkUidPermission(android.Manifest.permission.RUN_USER_INITIATED_JOBS, uid) == PackageManager.PERMISSION_GRANTED
    }

    private fun enforceSystem(what: String) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("$what requires android.permission.MANAGE_ACTIVITY_TASKS")
        }
    }

    override fun getStartedJobs(): MutableList<JobInfo?>? {
        enforceSystem("getStartedJobs")
        return synchronized(lock) { jobs.values.filter { it.running != null }.map { it.job }.toMutableList<JobInfo?>() }
    }

    override fun getAllJobSnapshots(): ParceledListSlice<*>? {
        enforceSystem("getAllJobSnapshots")
        val now = SystemClock.elapsedRealtime()
        return synchronized(lock) {
            ParceledListSlice(jobs.values.map { JobSnapshot(it.job, 0, it.pendingReason(now) == JobScheduler.PENDING_JOB_REASON_UNDEFINED) })
        }
    }

    override fun registerUserVisibleJobObserver(observer: IUserVisibleJobObserver?) {
        enforceSystem("registerUserVisibleJobObserver")
        synchronized(lock) { observers.add(observer ?: return) }
    }

    override fun unregisterUserVisibleJobObserver(observer: IUserVisibleJobObserver?) {
        enforceSystem("unregisterUserVisibleJobObserver")
        synchronized(lock) { observers.removeAll { it.asBinder() == observer?.asBinder() } }
    }

    override fun notePendingUserRequestedAppStop(packageName: String?, userId: Int, debugReason: String?) {
        enforceSystem("notePendingUserRequestedAppStop")
    }

    /** Scheduled (pending or running) jobs of a package, for dumps and the system test. */
    fun jobCountForPackage(packageName: String): Int = synchronized(lock) { jobs.values.count { it.packageName == packageName } }

    // ------------------------------------------------------------ completion (onJobCompletedLocked)

    private fun onJobCompleted(status: JobStatus, run: RunningJob, reschedule: Boolean, failed: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (jobs[status.key] !== status || status.running !== run) return // cancelled or superseded
            jobs.remove(status.key)
            val next: JobStatus? = when {
                reschedule -> rescheduleForFailure(status, nowElapsed)
                status.job.isPeriodic -> rescheduleForPeriodic(status, nowElapsed)
                else -> null
            }
            if (next != null) {
                if (!failed) next.pendingWork.addAll(status.pendingWork)
                next.nextPendingWorkId = status.nextPendingWorkId
                jobs[next.key] = next
                scheduleTimerLocked(next, nowElapsed)
            }
        }
        Log.i(TAG, "finished: pkg=${status.packageName} id=${status.job.id} reschedule=$reschedule failed=$failed")
    }

    /** JobSchedulerService.getRescheduleJobForFailureLocked: the app's backoff policy, capped. */
    private fun rescheduleForFailure(status: JobStatus, nowElapsed: Long): JobStatus {
        val attempts = status.numFailures + 1
        val initial = status.job.initialBackoffMillis
        var delay = when (status.job.backoffPolicy) {
            JobInfo.BACKOFF_POLICY_LINEAR -> initial * attempts
            else -> initial * (1L shl Math.min(attempts - 1, 30))
        }
        delay = Math.min(delay, JobInfo.MAX_BACKOFF_DELAY_MILLIS)
        return JobStatus(status.job, status.namespace, status.uid, status.packageName, attempts, nowElapsed + delay, NO_LATEST_RUNTIME)
    }

    /** JobSchedulerService.getRescheduleJobForPeriodic: the next period's flex window. */
    private fun rescheduleForPeriodic(status: JobStatus, nowElapsed: Long): JobStatus {
        val period = status.job.intervalMillis
        val flex = status.job.flexMillis
        var newLatest = status.latestRunTimeElapsed + period
        if (newLatest < nowElapsed) newLatest = nowElapsed + period
        return JobStatus(status.job, status.namespace, status.uid, status.packageName, 0, newLatest - flex, newLatest)
    }

    // ------------------------------------------------------------ execution (JobServiceContext)

    private inner class RunningJob(val status: JobStatus) : IJobCallback.Stub(), ServiceConnection {
        private val params = JobParameters(
            this, status.namespace, status.job.id, status.job.extras, status.job.transientExtras,
            status.job.clipData, status.job.clipGrantFlags,
            /* overrideDeadlineExpired */ status.latestRunTimeElapsed != NO_LATEST_RUNTIME && SystemClock.elapsedRealtime() >= status.latestRunTimeElapsed,
            status.job.isExpedited, status.job.isUserInitiated, null, null, if (canAccessJobNetwork(status.uid, status.packageName)) networkFor(status.job) else null,
        )
        private var service: IJobService? = null
        private var pendingNetwork: Network? = null
        private var verb = VERB_BINDING
        private var timeout: ScheduledFuture<*>? = null
        private var bound = false

        fun bind(): Boolean {
            val intent = Intent().setComponent(status.job.service)
            var flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_APP_COMPONENT_USAGE
            flags = if (status.job.isUserInitiated) flags or Context.BIND_ALMOST_PERCEPTIBLE
            else flags or Context.BIND_NOT_FOREGROUND or Context.BIND_NOT_PERCEPTIBLE
            val acquired = try {
                context.bindService(intent, this, flags)
            } catch (e: SecurityException) {
                Log.w(TAG, "Job service ${status.job.service.shortClassName} cannot be executed: ${e.message}")
                false
            }
            if (!acquired) {
                Log.d(TAG, "${status.job.service.shortClassName} unavailable.")
                try { context.unbindService(this) } catch (_: IllegalArgumentException) { }
                return false
            }
            val release = synchronized(lock) {
                if (verb == VERB_FINISHED || status.running !== this) true
                else {
                    bound = true
                    // A local service can connect and finish before bindService returns.
                    // Do not replace its execution timeout or leak a completed binding.
                    if (verb == VERB_BINDING) armTimeout(OP_BIND_TIMEOUT_MILLIS, "bind") {
                        closeAndCleanup(reschedule = true, failed = true, "timed out while binding")
                    }
                    false
                }
            }
            if (release) context.unbindService(this)
            return true
        }

        private fun armTimeout(delay: Long, what: String, action: () -> Unit) {
            timeout?.cancel(false)
            timeout = executor.schedule({ synchronized(lock) { if (status.running === this) action() } }, delay, TimeUnit.MILLISECONDS)
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IJobService.Stub.asInterface(binder)
            synchronized(lock) {
                if (status.running !== this || verb != VERB_BINDING) return
                service = svc
                verb = VERB_STARTING
                // handleServiceBoundLocked: startJob, then wait for the start acknowledgement.
                armTimeout(OP_TIMEOUT_MILLIS, "start") { onStartTimeout() }
            }
            try {
                svc.startJob(params)
            } catch (e: RemoteException) {
                synchronized(lock) { closeAndCleanup(reschedule = true, failed = true, "startJob failed") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The app process died mid-job (killServicesLocked): reschedule with a failure.
            synchronized(lock) { closeAndCleanup(reschedule = true, failed = true, "service disconnected") }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(lock) { closeAndCleanup(reschedule = true, failed = true, "null binding") }
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)

        private fun onStartTimeout() {
            Log.w(TAG, "No response from client for onStartJob ${status.job.service.shortClassName}")
            closeAndCleanup(reschedule = true, failed = true, "no response to onStartJob")
        }

        /** JobServiceContext.doCallback after the start acknowledgement. */
        override fun acknowledgeStartMessage(jobId: Int, ongoing: Boolean) {
            synchronized(lock) {
                if (status.running !== this || verb != VERB_STARTING) return
                if (!ongoing) {
                    // onStartJob returned false: the job is done.
                    params.setStopReason(JobParameters.STOP_REASON_UNDEFINED, JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH, "onStartJob returned false")
                    closeAndCleanup(reschedule = false, failed = false, "finished start")
                    return
                }
                verb = VERB_EXECUTING
                armTimeout(EXECUTING_TIMESLICE_MILLIS, "execute") { onExecutionTimeout() }
                if (pendingNetwork != null) executor.execute {
                    val network = synchronized(lock) { pendingNetwork }
                    if (network != null) networkChanged(network)
                }
            }
        }

        private fun onExecutionTimeout() {
            // handleOpTimeoutLocked while executing: stop the job with STOP_REASON_TIMEOUT.
            Log.i(TAG, "Client timed out while executing (no jobFinished received), sending onStop: ${status.job.service.shortClassName}")
            stop(JobParameters.STOP_REASON_TIMEOUT, JobParameters.INTERNAL_STOP_REASON_TIMEOUT, "client timed out")
        }

        override fun acknowledgeStopMessage(jobId: Int, reschedule: Boolean) {
            synchronized(lock) {
                if (status.running !== this) return
                closeAndCleanup(reschedule, failed = false, "stop acknowledged")
            }
        }

        override fun jobFinished(jobId: Int, reschedule: Boolean) {
            synchronized(lock) {
                if (status.running !== this || verb == VERB_FINISHED) return
                params.setStopReason(JobParameters.STOP_REASON_UNDEFINED, JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH, "app called jobFinished")
                closeAndCleanup(reschedule, failed = false, "app called jobFinished")
            }
        }

        override fun dequeueWork(jobId: Int): JobWorkItem? {
            synchronized(lock) {
                if (status.running !== this || verb == VERB_STOPPING || verb == VERB_FINISHED) return null
                val work = status.dequeueWorkLocked()
                if (work == null && !status.executingWork.isNotEmpty()) {
                    params.setStopReason(JobParameters.STOP_REASON_UNDEFINED, JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH, "last work dequeued")
                    closeAndCleanup(reschedule = false, failed = false, "last work dequeued")
                }
                return work
            }
        }

        override fun completeWork(jobId: Int, workId: Int): Boolean =
            synchronized(lock) { status.running === this && status.completeWorkLocked(workId) }

        override fun handleAbandonedJob(jobId: Int) {
            Log.w(TAG, "JobParameters abandoned by the client: ${status.job.service.shortClassName} id=$jobId")
        }

        override fun updateEstimatedNetworkBytes(jobId: Int, item: JobWorkItem?, downloadBytes: Long, uploadBytes: Long) {}
        override fun updateTransferredNetworkBytes(jobId: Int, item: JobWorkItem?, transferredDownloadBytes: Long, transferredUploadBytes: Long) {}
        override fun acknowledgeGetTransferredDownloadBytesMessage(jobId: Int, workId: Int, transferredBytes: Long) {}
        override fun acknowledgeGetTransferredUploadBytesMessage(jobId: Int, workId: Int, transferredBytes: Long) {}

        /** JobService.setNotification: the job's notification lands in the session's notification table. */
        override fun setNotification(jobId: Int, notificationId: Int, notification: android.app.Notification?, jobEndNotificationPolicy: Int) {
            notification ?: return
            notificationService?.enqueueNotificationWithTag(status.packageName, status.packageName, null, notificationId, notification, 0)
        }

        /** Sends onStopJob with the given reason; the job completes on acknowledgeStopMessage. Requires no lock. */
        /** JobServiceContext.informOfNetworkChangeLocked defers delivery until start acknowledgement. */
        fun networkChanged(network: Network) {
            val svc: IJobService = synchronized(lock) {
                if (status.running !== this || !canAccessJobNetwork(status.uid, status.packageName)) return
                if (verb == VERB_BINDING || verb == VERB_STARTING) {
                    pendingNetwork = Network(network)
                    return
                }
                if (verb != VERB_EXECUTING || params.network == network) return
                params.network = Network(network)
                pendingNetwork = null
                service ?: return
            }
            try {
                svc.onNetworkChanged(params)
            } catch (e: RemoteException) {
                synchronized(lock) {
                    closeAndCleanup(reschedule = true, failed = true, "network update failed")
                }
            }
        }

        fun stop(stopReason: Int, internalStopReason: Int, debugReason: String) {
            val svc: IJobService = synchronized(lock) {
                if (verb == VERB_STOPPING || verb == VERB_FINISHED) return
                val bound = service
                if (bound == null || verb == VERB_BINDING) {
                    closeAndCleanup(reschedule = false, failed = false, debugReason)
                    return
                }
                verb = VERB_STOPPING
                params.setStopReason(stopReason, internalStopReason, debugReason)
                armTimeout(OP_TIMEOUT_MILLIS, "stop") {
                    Log.w(TAG, "No response from client for onStopJob ${status.job.service.shortClassName}")
                    closeAndCleanup(reschedule = false, failed = false, "no response to onStopJob")
                }
                bound
            }
            try {
                svc.stopJob(params)
            } catch (e: RemoteException) {
                synchronized(lock) { closeAndCleanup(reschedule = false, failed = true, "stopJob failed") }
            }
        }

        /** JobServiceContext.closeAndCleanupJobLocked. Requires `lock`. */
        private fun closeAndCleanup(reschedule: Boolean, failed: Boolean, reason: String) {
            if (verb == VERB_FINISHED) return
            verb = VERB_FINISHED
            pendingNetwork = null
            timeout?.cancel(false)
            // Keep the completed run attached until onJobCompleted removes or replaces it;
            // a simultaneous network callback must not restart this same record.
            if (bound) {
                bound = false
                try {
                    context.unbindService(this)
                } catch (e: IllegalArgumentException) {
                    // already unbound
                }
            }
            executor.execute { onJobCompleted(status, this, reschedule, failed) }
            Log.d(TAG, "job ${status.job.service.shortClassName} closed: $reason")
        }
    }

    private companion object {
        const val TAG = "HostedJobScheduler"
        const val NO_EARLIEST_RUNTIME = 0L
        const val NO_LATEST_RUNTIME = Long.MAX_VALUE
        /** JobSchedulerService.MAX_JOBS_PER_APP. */
        const val MAX_JOBS_PER_APP = 150
        /** JobServiceContext.OP_BIND_TIMEOUT_MILLIS / OP_TIMEOUT_MILLIS / DEFAULT_RUNTIME_MIN_GUARANTEE_MS. */
        const val OP_BIND_TIMEOUT_MILLIS = 18 * 1000L
        const val OP_TIMEOUT_MILLIS = 8 * 1000L
        const val EXECUTING_TIMESLICE_MILLIS = 10 * 60 * 1000L
        const val VERB_BINDING = 0
        const val VERB_STARTING = 1
        const val VERB_EXECUTING = 2
        const val VERB_STOPPING = 3
        const val VERB_FINISHED = 4
    }
}
