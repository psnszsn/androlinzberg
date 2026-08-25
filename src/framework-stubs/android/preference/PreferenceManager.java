/* artzig shim: legacy android.preference.PreferenceManager statics. */
package android.preference;
import android.content.Context;
import android.content.SharedPreferences;
public class PreferenceManager {
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return context.getSharedPreferences(getDefaultSharedPreferencesName(context), Context.MODE_PRIVATE);
    }
    public static String getDefaultSharedPreferencesName(Context context) {
        return context.getPackageName() + "_preferences";
    }
    public static void setDefaultValues(Context context, int resId, boolean readAgain) {}
}
