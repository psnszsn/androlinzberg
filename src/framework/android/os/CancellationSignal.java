/* artzig shim: android.os.CancellationSignal. */
package android.os;
public final class CancellationSignal {
    public interface OnCancelListener { void onCancel(); }
    private boolean mCancelled;
    private OnCancelListener mListener;
    public boolean isCanceled() { synchronized (this) { return mCancelled; } }
    public void throwIfCanceled() {
        if (isCanceled()) throw new OperationCanceledException();
    }
    public void cancel() {
        OnCancelListener l;
        synchronized (this) {
            if (mCancelled) return;
            mCancelled = true;
            l = mListener;
        }
        if (l != null) l.onCancel();
    }
    private ICancellationSignal mRemote;
    public static ICancellationSignal createTransport() { return null; }
    public static CancellationSignal fromTransport(ICancellationSignal transport) { return new CancellationSignal(); }
    public void setRemote(ICancellationSignal remote) { mRemote = remote; }

    public void setOnCancelListener(OnCancelListener listener) {
        boolean fire;
        synchronized (this) { mListener = listener; fire = mCancelled; }
        if (fire && listener != null) listener.onCancel();
    }
}
