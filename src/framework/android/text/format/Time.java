/* artzig stub: android.text.format.Time — deprecated API; calendar-backed subset. */
package android.text.format;
import java.util.Calendar;
import java.util.TimeZone;
public class Time {
    public static final String TIMEZONE_UTC = "UTC";
    public int second, minute, hour, monthDay, month, year, weekDay, yearDay, isDst;
    public long gmtoff;
    public String timezone;
    public Time() { this(TimeZone.getDefault().getID()); }
    public Time(String timezoneId) { timezone = timezoneId; }
    public void set(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        c.setTimeInMillis(millis);
        second = c.get(Calendar.SECOND); minute = c.get(Calendar.MINUTE);
        hour = c.get(Calendar.HOUR_OF_DAY); monthDay = c.get(Calendar.DAY_OF_MONTH);
        month = c.get(Calendar.MONTH); year = c.get(Calendar.YEAR);
        weekDay = c.get(Calendar.DAY_OF_WEEK) - 1; yearDay = c.get(Calendar.DAY_OF_YEAR) - 1;
        gmtoff = c.getTimeZone().getOffset(millis) / 1000;
    }
    public void set(int day, int month, int year) {
        this.monthDay = day; this.month = month; this.year = year;
        this.hour = this.minute = this.second = 0;
    }
    public long toMillis(boolean ignoreDst) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        c.clear();
        c.set(year, month, monthDay, hour, minute, second);
        return c.getTimeInMillis();
    }
    public long setJulianDay(int julianDay) {
        long millis = (julianDay - 2440588L) * 86400000L;
        set(millis);
        return toMillis(false);
    }
    public static int getJulianDay(long millis, long gmtoff) {
        return (int) ((millis / 1000 + gmtoff) / 86400 + 2440588);
    }
    public void setToNow() { set(System.currentTimeMillis()); }
}
