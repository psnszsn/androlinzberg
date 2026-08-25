/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class SynchronousResultReceiver extends ResultReceiver {
    public SynchronousResultReceiver() { super((android.os.Handler) null); }
    public SynchronousResultReceiver(String name) { super((android.os.Handler) null); }
    public Result awaitResult(long timeoutMillis) throws java.util.concurrent.TimeoutException { return new Result(); }
    public static class Result {
        public int resultCode;
        public android.os.Bundle bundle = new android.os.Bundle();
    }
}
