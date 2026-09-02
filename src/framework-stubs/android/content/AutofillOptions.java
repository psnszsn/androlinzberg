/* artzig stub. */
package android.content;
public final class AutofillOptions implements android.os.Parcelable {
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeInt(loggingLevel); dest.writeBoolean(compatModeEnabled); dest.writeBoolean(augmentedAutofillEnabled);
    }
    public static final Creator<AutofillOptions> CREATOR = new Creator<AutofillOptions>() {
        @Override public AutofillOptions createFromParcel(android.os.Parcel in) {
            AutofillOptions o = new AutofillOptions(); o.loggingLevel = in.readInt(); o.compatModeEnabled = in.readBoolean(); o.augmentedAutofillEnabled = in.readBoolean(); return o;
        }
        @Override public AutofillOptions[] newArray(int size) { return new AutofillOptions[size]; }
    };
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
