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

    public void set(int type, long triggerAtMillis, PendingIntent operation) {}
    public void setExact(int type, long triggerAtMillis, PendingIntent operation) {}
    public void setAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {}
    public void setExactAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {}
    public void setRepeating(int type, long triggerAtMillis, long intervalMillis,
            PendingIntent operation) {}
    public void setWindow(int type, long windowStartMillis, long windowLengthMillis,
            PendingIntent operation) {}
    public void cancel(PendingIntent operation) {}
    public void cancel(OnAlarmListener listener) {}
    public boolean canScheduleExactAlarms() { return true; }
    public void set(int type, long triggerAtMillis, String tag, OnAlarmListener listener,
            Handler targetHandler) { schedule(listener, targetHandler); }
    public void setExact(int type, long triggerAtMillis, String tag, OnAlarmListener listener,
            Handler targetHandler) { schedule(listener, targetHandler); }

    private static void schedule(OnAlarmListener listener, Handler handler) {
        if (listener == null) return;
        if (handler != null) handler.post(listener::onAlarm);
        else listener.onAlarm();
    }
}
