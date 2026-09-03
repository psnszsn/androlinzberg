package dev.artzig.systemtest

import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * JobServices of the system-test fixtures. The hosted job scheduler binds
 * them from the system process (JobServiceContext) and drives the real
 * IJobService/IJobCallback protocol: onStartJob, dequeueWork/completeWork,
 * jobFinished, onStopJob, then unbind (onDestroy).
 */
open class TestJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val state = stateFor(this)
        state.lastParams = params
        state.started.countDown()
        thread(name = "test-job-${params.jobId}") {
            // Work items arrive through the scheduler: drain them as JobService.dequeueWork documents.
            while (true) {
                val work = params.dequeueWork() ?: break
                state.workExtras.add(work.intent.getIntExtra("n", -1))
                params.completeWork(work)
            }
            Thread.sleep(100)
            state.finishedCount.incrementAndGet()
            jobFinished(params, false)
            state.finished.countDown()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stateFor(this).stopReason = params.stopReason
        stateFor(this).stopped.countDown()
        return false
    }

    override fun onDestroy() {
        stateFor(this).destroyed.countDown()
        super.onDestroy()
    }

    class State {
        @JvmField val started = CountDownLatch(1)
        @JvmField val finished = CountDownLatch(1)
        @JvmField val stopped = CountDownLatch(1)
        @JvmField val destroyed = CountDownLatch(1)
        @JvmField val finishedCount = AtomicInteger()
        @JvmField val workExtras = java.util.concurrent.CopyOnWriteArrayList<Int>()
        @Volatile @JvmField var lastParams: JobParameters? = null
        @Volatile @JvmField var stopReason = -1
    }

    companion object {
        @JvmField val a = State()
        @JvmField val b = State()
        private fun stateFor(service: TestJobService): State = if (service is BJobService) b else a
    }
}

/** dev.artzig.test.a's job service (android:permission BIND_JOB_SERVICE). */
class AJobService : TestJobService()

/** dev.artzig.test.b's job service (android:permission BIND_JOB_SERVICE). */
class BJobService : TestJobService()

/** Declared without BIND_JOB_SERVICE: the scheduler must refuse it (enforceValidJobRequest). */
class ANoPermissionJobService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
