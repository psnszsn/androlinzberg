/* Hosted adaptation of the app-side broadcast dispatch: registration and
 * delivery go through the session's activity manager. Source note: follows
 * android.app.LoadedApk.ReceiverDispatcher (InnerReceiver, Args extends
 * BroadcastReceiver.PendingResult, Handler delivery, finish-on-return) with
 * one dispatcher per BroadcastReceiver, and ContextImpl.sendBroadcast /
 * registerReceiverInternal for the send and register paths. */
package android.content;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.IdentityHashMap;

final class ArtzigBroadcastManager {
    private static final IdentityHashMap<BroadcastReceiver, ReceiverDispatcher> sReceivers =
            new IdentityHashMap<>();
    private static Handler sMainHandler;

    private ArtzigBroadcastManager() {}

    static final class ReceiverDispatcher {
        final class InnerReceiver extends IIntentReceiver.Stub {
            @Override
            public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                    boolean ordered, boolean sticky, int sendingUser) {
                ReceiverDispatcher.this.performReceive(
                        intent, resultCode, data, extras, ordered, sticky);
            }
        }

        final InnerReceiver mIIntentReceiver = new InnerReceiver();
        final BroadcastReceiver mReceiver;
        final Context mContext;
        final Handler mHandler;
        boolean mForgotten;

        final class Args extends BroadcastReceiver.PendingResult {
            private Intent mCurIntent;

            Args(Intent intent, int resultCode, String resultData, Bundle resultExtras,
                    boolean ordered, boolean sticky) {
                super(resultCode, resultData, resultExtras,
                        mForgotten ? TYPE_UNREGISTERED : TYPE_REGISTERED, ordered, sticky,
                        mIIntentReceiver);
                mCurIntent = intent;
            }

            Runnable getRunnable() {
                return () -> {
                    final BroadcastReceiver receiver = mReceiver;
                    final Intent intent = mCurIntent;
                    mCurIntent = null;
                    if (receiver == null || intent == null || mForgotten) {
                        // Delivered after unregister: acknowledge so an ordered
                        // sequence can continue (LoadedApk.InnerReceiver).
                        finish();
                        return;
                    }
                    try {
                        ClassLoader cl = receiver.getClass().getClassLoader();
                        intent.setExtrasClassLoader(cl);
                        receiver.setPendingResult(this);
                        receiver.onReceive(mContext, intent);
                    } catch (Exception e) {
                        finish();
                        throw new RuntimeException(
                                "Error receiving broadcast " + intent + " in " + receiver, e);
                    }
                    if (receiver.getPendingResult() != null) {
                        receiver.setPendingResult(null);
                        finish();
                    }
                };
            }
        }

        ReceiverDispatcher(BroadcastReceiver receiver, Context context, Handler handler) {
            mReceiver = receiver;
            mContext = context;
            mHandler = handler;
        }

        void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky) {
            Args args = new Args(intent, resultCode, data, extras, ordered, sticky);
            if (!mHandler.post(args.getRunnable()) && ordered) {
                args.sendFinished();
            }
        }
    }

    static Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter,
            Handler scheduler, String broadcastPermission, int flags) {
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
        if (receiver == null) {
            // Android's sticky-broadcast query form: nothing is registered.
            return android.app.HostedServiceClient.registerReceiver(null, filter, null, 0);
        }
        ReceiverDispatcher dispatcher;
        synchronized (sReceivers) {
            dispatcher = sReceivers.get(receiver);
            if (dispatcher == null) {
                dispatcher = new ReceiverDispatcher(receiver, context,
                        scheduler != null ? scheduler : mainHandler());
                sReceivers.put(receiver, dispatcher);
            }
        }
        return android.app.HostedServiceClient.registerReceiver(
                dispatcher.mIIntentReceiver, filter, broadcastPermission, flags);
    }

    static void unregister(BroadcastReceiver receiver) {
        if (receiver == null) return;
        ReceiverDispatcher dispatcher;
        synchronized (sReceivers) {
            dispatcher = sReceivers.remove(receiver);
        }
        if (dispatcher == null) return;
        dispatcher.mForgotten = true;
        android.app.HostedServiceClient.unregisterReceiver(dispatcher.mIIntentReceiver);
    }

    static void send(Context sender, Intent intent, String receiverPermission) {
        if (intent == null || intent.getAction() == null) return;
        android.app.HostedServiceClient.broadcastIntent(
                intent, receiverPermission, false, false);
    }

    static void sendOrdered(Context sender, Intent intent, String receiverPermission) {
        if (intent == null || intent.getAction() == null) return;
        android.app.HostedServiceClient.broadcastIntent(
                intent, receiverPermission, true, false);
    }

    static void sendSticky(Context sender, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        android.app.HostedServiceClient.broadcastIntent(intent, null, false, true);
    }

    static void removeSticky(Context sender, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        android.app.HostedServiceClient.removeStickyBroadcast(intent);
    }

    private static Handler mainHandler() {
        synchronized (sReceivers) {
            if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
            return sMainHandler;
        }
    }
}
