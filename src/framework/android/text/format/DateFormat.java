/* artzig shim: android.text.format.DateFormat — subset over java.text/android.icu
 * (real one requires a newer icu4j getBestPattern than artzig's libcore ships). */
package android.text.format;
import android.content.Context;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
public class DateFormat {
    public static final char QUOTE = '\'', AM_PM = 'a', CAPITAL_AM_PM = 'A', DATE = 'd',
            DAY = 'E', HOUR = 'h', HOUR_OF_DAY = 'k', MINUTE = 'm', MONTH = 'M',
            STANDALONE_MONTH = 'L', SECONDS = 's', TIME_ZONE = 'z', YEAR = 'y';
    public static boolean is24HourFormat(Context context) { return true; }
    public static boolean is24HourFormat(Context context, int userHandle) { return true; }
    public static String getBestDateTimePattern(Locale locale, String skeleton) {
        return android.icu.text.DateTimePatternGenerator.getInstance(
                android.icu.util.ULocale.forLocale(locale)).getBestPattern(skeleton);
    }
    public static java.text.DateFormat getTimeFormat(Context context) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
    }
    public static java.text.DateFormat getDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
    }
    public static java.text.DateFormat getLongDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG);
    }
    public static java.text.DateFormat getMediumDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
    }
    public static CharSequence format(CharSequence inFormat, long inTimeInMillis) {
        return format(inFormat, new Date(inTimeInMillis));
    }
    public static CharSequence format(CharSequence inFormat, Date inDate) {
        return new java.text.SimpleDateFormat(inFormat.toString()).format(inDate);
    }
    public static CharSequence format(CharSequence inFormat, Calendar inDate) {
        return format(inFormat, inDate.getTime());
    }
    public static android.icu.text.DateFormatSymbols getIcuDateFormatSymbols(Locale locale) {
        return new android.icu.text.DateFormatSymbols(locale);
    }
    public static String getTimeFormatString(Context context) { return "H:mm"; }
    public static boolean hasSeconds(CharSequence inFormat) {
        return inFormat != null && inFormat.toString().indexOf('s') >= 0;
    }
    public static boolean hasDesignator(CharSequence inFormat, char designator) {
        return inFormat != null && inFormat.toString().indexOf(designator) >= 0;
    }
    public static java.text.DateFormat getTimeInstance(int style, Locale locale) {
        return java.text.DateFormat.getTimeInstance(style, locale);
    }
    public static java.text.DateFormat getDateInstance(int style, Locale locale) {
        return java.text.DateFormat.getDateInstance(style, locale);
    }
}
