/* artzig stub: jobs are accepted and never run (no scheduler on host). */
package android.app.job;
import java.util.ArrayList;
import java.util.List;
public class JobScheduler {
    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    private final String namespace;

    public JobScheduler() { this(null); }

    private JobScheduler(String namespace) { this.namespace = namespace; }

    /* AOSP JobSchedulerImpl API 34 behavior, without the unavailable binder backend. */
    public JobScheduler forNamespace(String namespace) {
        namespace = sanitizeNamespace(namespace);
        if (namespace == null) {
            throw new NullPointerException("namespace cannot be null");
        }
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace cannot be empty");
        }
        return new JobScheduler(namespace);
    }

    public String getNamespace() { return namespace; }

    public static String sanitizeNamespace(String namespace) {
        if (namespace == null) return null;
        return namespace.trim().intern();
    }

    public int schedule(JobInfo job) { return RESULT_SUCCESS; }
    public int enqueue(JobInfo job, Object work) { return RESULT_SUCCESS; }
    public void cancel(int jobId) {}
    public void cancelAll() {}
    public List<JobInfo> getAllPendingJobs() { return new ArrayList<>(); }
    public JobInfo getPendingJob(int jobId) { return null; }
}
