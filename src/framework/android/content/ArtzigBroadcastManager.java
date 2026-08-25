/* artzig: process-local dynamic broadcast delivery. Android normally routes
 * this through ActivityManager; the host has one application process, so the
 * useful equivalent is a registry which preserves Handler delivery semantics. */
package android.content;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;

final class ArtzigBroadcastManager {
    private static final class Registration {
        final Context context;
        final BroadcastReceiver receiver;
        final IntentFilter filter;
        final Handler scheduler;

        Registration(Context context, BroadcastReceiver receiver, IntentFilter filter,
                Handler scheduler) {
            this.context = context;
            this.receiver = receiver;
            this.filter = filter;
            this.scheduler = scheduler;
        }
    }

    private static final ArrayList<Registration> sRegistrations = new ArrayList<>();
    private static Handler sMainHandler;

    private ArtzigBroadcastManager() {}

    static Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter,
            Handler scheduler) {
        // A null receiver is Android's sticky-broadcast query form. Artzig does
        // not retain sticky broadcasts, so there is nothing to return.
        if (receiver == null) return null;
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
        synchronized (sRegistrations) {
            sRegistrations.add(new Registration(context, receiver, filter, scheduler));
        }
        return null;
    }

    static void unregister(BroadcastReceiver receiver) {
        if (receiver == null) return;
        synchronized (sRegistrations) {
            for (int i = sRegistrations.size() - 1; i >= 0; --i) {
                if (sRegistrations.get(i).receiver == receiver) sRegistrations.remove(i);
            }
        }
    }

    static void send(Context sender, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final ArrayList<Registration> matches = new ArrayList<>();
        synchronized (sRegistrations) {
            for (Registration registration : sRegistrations) {
                String targetPackage = intent.getPackage();
                if (targetPackage != null
                        && !targetPackage.equals(registration.context.getPackageName())) {
                    continue;
                }
                if (registration.filter.matchAction(intent.getAction())) {
                    matches.add(registration);
                }
            }
        }
        for (Registration registration : matches) {
            Handler handler = registration.scheduler != null
                    ? registration.scheduler : mainHandler();
            handler.post(() -> registration.receiver.onReceive(registration.context, intent));
        }
    }

    private static Handler mainHandler() {
        synchronized (sRegistrations) {
            if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
            return sMainHandler;
        }
    }
}
