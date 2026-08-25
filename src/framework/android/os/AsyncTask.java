/* artzig shim: android.os.AsyncTask — thread + main-handler port. */
package android.os;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
public abstract class AsyncTask<Params, Progress, Result> {
    public static final Executor THREAD_POOL_EXECUTOR =
            new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public static final Executor SERIAL_EXECUTOR = THREAD_POOL_EXECUTOR;
    private volatile boolean mCancelled;
    private volatile Status mStatus = Status.PENDING;
    public enum Status { PENDING, RUNNING, FINISHED }
    public final Status getStatus() { return mStatus; }
    public AsyncTask() {}
    public AsyncTask(android.os.Handler handler) {}
    public AsyncTask(android.os.Looper looper) {}
    protected void onPreExecute() {}
    protected abstract Result doInBackground(Params... params);
    protected void onPostExecute(Result result) {}
    protected void onCancelled() {}
    protected void onCancelled(Result result) { onCancelled(); }
    public final boolean isCancelled() { return mCancelled; }
    public final boolean cancel(boolean mayInterruptIfRunning) { mCancelled = true; return true; }
    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(THREAD_POOL_EXECUTOR, params);
    }
    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
        onPreExecute();
        Handler main = Looper.getMainLooper() != null ? new Handler(Looper.getMainLooper()) : null;
        exec.execute(() -> {
            Result r = doInBackground(params);
            Runnable post = () -> { if (mCancelled) onCancelled(r); else onPostExecute(r); };
            if (main != null) main.post(post); else post.run();
        });
        return this;
    }
    public static void execute(Runnable runnable) { THREAD_POOL_EXECUTOR.execute(runnable); }
}
