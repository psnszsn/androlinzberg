/* artzig stub. */
package android.content;
public final class AutofillOptions {
    public int loggingLevel;
    public boolean compatModeEnabled;
    public android.util.ArrayMap<String, Long> disabledActivities;
    public boolean isAutofillDisabledLocked(android.content.ComponentName componentName) { return false; }
    public void dumpShort(java.io.PrintWriter pw) { pw.print("autofill-options-stub"); }
    public boolean augmentedAutofillEnabled;
    public long appDisabledExpiration;
    public android.util.ArraySet<String> whitelistedActivitiesForAugmentedAutofill;
    public boolean isAugmentedAutofillEnabled(Context context) { return false; }
    public static AutofillOptions forWhitelistingItself() { return new AutofillOptions(); }
}
