/* artzig stub: job services are never started (no scheduler on host). */
package android.app.job;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public abstract class JobService extends Service {
    public abstract boolean onStartJob(JobParameters params);
    public abstract boolean onStopJob(JobParameters params);
    public final void jobFinished(JobParameters params, boolean wantsReschedule) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
