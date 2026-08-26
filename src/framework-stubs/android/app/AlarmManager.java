package android.app;

import android.app.PendingIntent;
import android.os.Handler;

/** In-process AlarmManager facade for hosted applications. */
public class AlarmManager {
    public static final int RTC_WAKEUP = 0;
    public static final int RTC = 1;
    public static final int ELAPSED_REALTIME_WAKEUP = 2;
    public static final int ELAPSED_REALTIME = 3;

    public interface OnAlarmListener { void onAlarm(); }

    private static final class ScheduledAlarm {
        final Handler handler;
        final Runnable runnable;
        ScheduledAlarm(Handler handler, Runnable runnable) {
            this.handler = handler;
            this.runnable = runnable;
        }
    }

    private static final java.util.HashMap<Object, ScheduledAlarm> sScheduled =
            new java.util.HashMap<>();

    public void set(int type, long triggerAtMillis, PendingIntent operation) {
        schedule(type, triggerAtMillis, 0, operation);
    }
    public void setExact(int type, long triggerAtMillis, PendingIntent operation) {
        schedule(type, triggerAtMillis, 0, operation);
    }
    public void setAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {
        schedule(type, triggerAtMillis, 0, operation);
    }
    public void setExactAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {
        schedule(type, triggerAtMillis, 0, operation);
    }
    public void setRepeating(int type, long triggerAtMillis, long intervalMillis,
            PendingIntent operation) {
        schedule(type, triggerAtMillis, Math.max(1, intervalMillis), operation);
    }
    public void setWindow(int type, long windowStartMillis, long windowLengthMillis,
            PendingIntent operation) {
        schedule(type, windowStartMillis, 0, operation);
    }
    public void cancel(PendingIntent operation) { cancelScheduled(operation); }
    public void cancel(OnAlarmListener listener) { cancelScheduled(listener); }
    public boolean canScheduleExactAlarms() { return true; }
    public void set(int type, long triggerAtMillis, String tag, OnAlarmListener listener,
            Handler targetHandler) { schedule(type, triggerAtMillis, listener, targetHandler); }
    public void setExact(int type, long triggerAtMillis, String tag, OnAlarmListener listener,
            Handler targetHandler) { schedule(type, triggerAtMillis, listener, targetHandler); }

    private static void schedule(int type, long triggerAtMillis, long repeatMillis,
            PendingIntent operation) {
        if (operation == null) return;
        Handler handler = new Handler(android.os.Looper.getMainLooper());
        Runnable[] callback = new Runnable[1];
        callback[0] = () -> {
            if (repeatMillis == 0) removeScheduled(operation, callback[0]);
            try {
                operation.send();
            } catch (PendingIntent.CanceledException ignored) {
                cancelScheduled(operation);
                return;
            }
            if (repeatMillis > 0 && isScheduled(operation, callback[0])) {
                handler.postDelayed(callback[0], repeatMillis);
            }
        };
        replaceScheduled(operation, new ScheduledAlarm(handler, callback[0]));
        handler.postDelayed(callback[0], delayMillis(type, triggerAtMillis));
    }

    private static void schedule(int type, long triggerAtMillis, OnAlarmListener listener,
            Handler targetHandler) {
        if (listener == null) return;
        Handler handler = targetHandler != null ? targetHandler
                : new Handler(android.os.Looper.getMainLooper());
        Runnable[] callback = new Runnable[1];
        callback[0] = () -> {
            removeScheduled(listener, callback[0]);
            listener.onAlarm();
        };
        replaceScheduled(listener, new ScheduledAlarm(handler, callback[0]));
        handler.postDelayed(callback[0], delayMillis(type, triggerAtMillis));
    }

    private static long delayMillis(int type, long triggerAtMillis) {
        long now = type == RTC || type == RTC_WAKEUP
                ? System.currentTimeMillis() : android.os.SystemClock.elapsedRealtime();
        return Math.max(0, triggerAtMillis - now);
    }

    private static void replaceScheduled(Object key, ScheduledAlarm alarm) {
        synchronized (sScheduled) {
            ScheduledAlarm old = sScheduled.put(key, alarm);
            if (old != null) old.handler.removeCallbacks(old.runnable);
        }
    }

    private static void cancelScheduled(Object key) {
        if (key == null) return;
        synchronized (sScheduled) {
            ScheduledAlarm alarm = sScheduled.remove(key);
            if (alarm != null) alarm.handler.removeCallbacks(alarm.runnable);
        }
    }

    private static void removeScheduled(Object key, Runnable runnable) {
        synchronized (sScheduled) {
            ScheduledAlarm alarm = sScheduled.get(key);
            if (alarm != null && alarm.runnable == runnable) sScheduled.remove(key);
        }
    }

    private static boolean isScheduled(Object key, Runnable runnable) {
        synchronized (sScheduled) {
            ScheduledAlarm alarm = sScheduled.get(key);
            return alarm != null && alarm.runnable == runnable;
        }
    }
}
