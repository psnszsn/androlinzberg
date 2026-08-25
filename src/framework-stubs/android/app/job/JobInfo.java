/* artzig stub: job descriptions are accepted and ignored on host. */
package android.app.job;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.PersistableBundle;

public class JobInfo {
    public static final int NETWORK_TYPE_NONE = 0;
    public static final int NETWORK_TYPE_ANY = 1;
    public static final int NETWORK_TYPE_UNMETERED = 2;
    public static final int BACKOFF_POLICY_LINEAR = 0;
    public static final int BACKOFF_POLICY_EXPONENTIAL = 1;

    private final int mJobId;
    private final ComponentName mService;
    private PersistableBundle mExtras = new PersistableBundle();
    private Bundle mTransientExtras = new Bundle();

    JobInfo(int jobId, ComponentName service) { mJobId = jobId; mService = service; }
    public int getId() { return mJobId; }
    public ComponentName getService() { return mService; }
    public PersistableBundle getExtras() { return mExtras; }
    public Bundle getTransientExtras() { return mTransientExtras; }

    public static final class Builder {
        private final JobInfo j;
        public Builder(int jobId, ComponentName jobService) { j = new JobInfo(jobId, jobService); }
        public Builder setExtras(PersistableBundle extras) { j.mExtras = extras; return this; }
        public Builder setTransientExtras(Bundle extras) { j.mTransientExtras = extras; return this; }
        public Builder setRequiredNetworkType(int networkType) { return this; }
        public Builder setRequiresCharging(boolean requiresCharging) { return this; }
        public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) { return this; }
        public Builder setRequiresBatteryNotLow(boolean batteryNotLow) { return this; }
        public Builder setRequiresStorageNotLow(boolean storageNotLow) { return this; }
        public Builder setPeriodic(long intervalMillis) { return this; }
        public Builder setPeriodic(long intervalMillis, long flexMillis) { return this; }
        public Builder setMinimumLatency(long minLatencyMillis) { return this; }
        public Builder setOverrideDeadline(long maxExecutionDelayMillis) { return this; }
        public Builder setPersisted(boolean isPersisted) { return this; }
        public Builder setBackoffCriteria(long initialBackoffMillis, int backoffPolicy) { return this; }
        public JobInfo build() { return j; }
    }
}
