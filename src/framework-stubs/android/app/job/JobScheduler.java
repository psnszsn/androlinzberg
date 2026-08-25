/* artzig stub: jobs are accepted and never run (no scheduler on host). */
package android.app.job;
import java.util.ArrayList;
import java.util.List;
public class JobScheduler {
    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    public JobScheduler() {}
    public int schedule(JobInfo job) { return RESULT_SUCCESS; }
    public int enqueue(JobInfo job, Object work) { return RESULT_SUCCESS; }
    public void cancel(int jobId) {}
    public void cancelAll() {}
    public List<JobInfo> getAllPendingJobs() { return new ArrayList<>(); }
    public JobInfo getPendingJob(int jobId) { return null; }
}
