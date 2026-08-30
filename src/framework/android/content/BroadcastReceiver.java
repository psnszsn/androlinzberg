/* Hosted adaptation of AOSP android.content.BroadcastReceiver: the fields and
 * methods the hosted broadcast path uses, with the same PendingResult state
 * machine. finish() reports to the session's activity manager keyed by the
 * registered IIntentReceiver (AOSP keys by the application thread binder). */
package android.content;

import android.os.Bundle;

public abstract class BroadcastReceiver {
    private PendingResult mPendingResult;

    public abstract void onReceive(Context context, Intent intent);

    public static class PendingResult {
        public static final int TYPE_COMPONENT = 0;
        public static final int TYPE_REGISTERED = 1;
        public static final int TYPE_UNREGISTERED = 2;

        final int mType;
        final boolean mOrderedHint;
        final boolean mInitialStickyHint;
        final IIntentReceiver mWho;
        int mResultCode;
        String mResultData;
        Bundle mResultExtras;
        boolean mAbortBroadcast;
        boolean mFinished;

        public PendingResult(int resultCode, String resultData, Bundle resultExtras, int type,
                boolean ordered, boolean sticky, IIntentReceiver who) {
            mResultCode = resultCode;
            mResultData = resultData;
            mResultExtras = resultExtras;
            mType = type;
            mOrderedHint = ordered;
            mInitialStickyHint = sticky;
            mWho = who;
        }

        public final void setResultCode(int code) { mResultCode = code; }
        public final int getResultCode() { return mResultCode; }
        public final void setResultData(String data) { mResultData = data; }
        public final String getResultData() { return mResultData; }
        public final void setResultExtras(Bundle extras) { mResultExtras = extras; }
        public final Bundle getResultExtras(boolean makeMap) {
            Bundle e = mResultExtras;
            if (!makeMap) return e;
            if (e == null) mResultExtras = e = new Bundle();
            return e;
        }
        public final void setResult(int code, String data, Bundle extras) {
            mResultCode = code;
            mResultData = data;
            mResultExtras = extras;
        }
        public final boolean getAbortBroadcast() { return mAbortBroadcast; }
        public final void abortBroadcast() { mAbortBroadcast = true; }
        public final void clearAbortBroadcast() { mAbortBroadcast = false; }

        /** The current result is sent and the next ordered receiver proceeds. */
        public final void finish() {
            if (mFinished) return;
            mFinished = true;
            if (mOrderedHint) sendFinished();
        }

        void sendFinished() {
            android.app.HostedServiceClient.finishReceiver(
                    mWho, mResultCode, mResultData, mResultExtras, mAbortBroadcast);
        }
    }

    public final void setPendingResult(PendingResult result) { mPendingResult = result; }
    public final PendingResult getPendingResult() { return mPendingResult; }

    /** Keeps the broadcast alive past onReceive; the caller must finish() the returned result. */
    public final PendingResult goAsync() {
        PendingResult res = mPendingResult;
        mPendingResult = null;
        return res;
    }

    public final void setResultCode(int code) { if (mPendingResult != null) mPendingResult.setResultCode(code); }
    public final int getResultCode() { return mPendingResult != null ? mPendingResult.getResultCode() : 0; }
    public final void setResultData(String data) { if (mPendingResult != null) mPendingResult.setResultData(data); }
    public final String getResultData() { return mPendingResult != null ? mPendingResult.getResultData() : null; }
    public final void setResultExtras(Bundle extras) { if (mPendingResult != null) mPendingResult.setResultExtras(extras); }
    public final Bundle getResultExtras(boolean makeMap) {
        return mPendingResult != null ? mPendingResult.getResultExtras(makeMap) : (makeMap ? new Bundle() : null);
    }
    public final void setResult(int code, String data, Bundle extras) {
        if (mPendingResult != null) mPendingResult.setResult(code, data, extras);
    }
    public final boolean getAbortBroadcast() { return mPendingResult != null && mPendingResult.getAbortBroadcast(); }
    public final void abortBroadcast() { if (mPendingResult != null) mPendingResult.abortBroadcast(); }
    public final void clearAbortBroadcast() { if (mPendingResult != null) mPendingResult.clearAbortBroadcast(); }
    public final boolean isOrderedBroadcast() { return mPendingResult != null && mPendingResult.mOrderedHint; }
    public final boolean isInitialStickyBroadcast() { return mPendingResult != null && mPendingResult.mInitialStickyHint; }
}
