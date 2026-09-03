@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IJobScheduler method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedJobScheduler : android.app.job.IJobScheduler.Stub() {
    override fun schedule(namespace: String?, job: android.app.job.JobInfo?): Int = throw UnsupportedOperationException("IJobScheduler.schedule is not hosted")
    override fun enqueue(namespace: String?, job: android.app.job.JobInfo?, work: android.app.job.JobWorkItem?): Int = throw UnsupportedOperationException("IJobScheduler.enqueue is not hosted")
    override fun scheduleAsPackage(namespace: String?, job: android.app.job.JobInfo?, packageName: String?, userId: Int, tag: String?): Int = throw UnsupportedOperationException("IJobScheduler.scheduleAsPackage is not hosted")
    override fun cancel(namespace: String?, jobId: Int) { throw UnsupportedOperationException("IJobScheduler.cancel is not hosted") }
    override fun cancelAll() { throw UnsupportedOperationException("IJobScheduler.cancelAll is not hosted") }
    override fun cancelAllInNamespace(namespace: String?) { throw UnsupportedOperationException("IJobScheduler.cancelAllInNamespace is not hosted") }
    override fun getAllPendingJobs(): MutableMap<String?, android.content.pm.ParceledListSlice<android.app.job.JobInfo>?>? = throw UnsupportedOperationException("IJobScheduler.getAllPendingJobs is not hosted")
    override fun getAllPendingJobsInNamespace(namespace: String?): android.content.pm.ParceledListSlice<android.app.job.JobInfo>? = throw UnsupportedOperationException("IJobScheduler.getAllPendingJobsInNamespace is not hosted")
    override fun getPendingJob(namespace: String?, jobId: Int): android.app.job.JobInfo? = throw UnsupportedOperationException("IJobScheduler.getPendingJob is not hosted")
    override fun getPendingJobReason(namespace: String?, jobId: Int): Int = throw UnsupportedOperationException("IJobScheduler.getPendingJobReason is not hosted")
    override fun getPendingJobReasons(namespace: String?, jobId: Int): IntArray? = throw UnsupportedOperationException("IJobScheduler.getPendingJobReasons is not hosted")
    override fun getPendingJobReasonsHistory(namespace: String?, jobId: Int): MutableList<android.app.job.PendingJobReasonsInfo?>? = throw UnsupportedOperationException("IJobScheduler.getPendingJobReasonsHistory is not hosted")
    override fun canRunUserInitiatedJobs(packageName: String?): Boolean = throw UnsupportedOperationException("IJobScheduler.canRunUserInitiatedJobs is not hosted")
    override fun hasRunUserInitiatedJobsPermission(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IJobScheduler.hasRunUserInitiatedJobsPermission is not hosted")
    override fun getStartedJobs(): MutableList<android.app.job.JobInfo?>? = throw UnsupportedOperationException("IJobScheduler.getStartedJobs is not hosted")
    override fun getAllJobSnapshots(): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IJobScheduler.getAllJobSnapshots is not hosted")
    override fun registerUserVisibleJobObserver(observer: android.app.job.IUserVisibleJobObserver?) { throw UnsupportedOperationException("IJobScheduler.registerUserVisibleJobObserver is not hosted") }
    override fun unregisterUserVisibleJobObserver(observer: android.app.job.IUserVisibleJobObserver?) { throw UnsupportedOperationException("IJobScheduler.unregisterUserVisibleJobObserver is not hosted") }
    override fun notePendingUserRequestedAppStop(packageName: String?, userId: Int, debugReason: String?) { throw UnsupportedOperationException("IJobScheduler.notePendingUserRequestedAppStop is not hosted") }
}
