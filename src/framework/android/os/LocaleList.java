/* artzig shim: android.os.LocaleList. */
package android.os;
import java.util.Locale;
public final class LocaleList implements Parcelable {
    private Locale[] mList;
    private static LocaleList sDefault;
    public LocaleList(Locale... list) { mList = list.clone(); }
    public Locale get(int index) { return index >= 0 && index < mList.length ? mList[index] : null; }
    public boolean isEmpty() { return mList.length == 0; }
    public int size() { return mList.length; }
    public int indexOf(Locale locale) {
        for (int i = 0; i < mList.length; i++) if (mList[i].equals(locale)) return i;
        return -1;
    }
    public static LocaleList getEmptyLocaleList() { return new LocaleList(); }
    public static LocaleList getDefault() {
        synchronized (LocaleList.class) {
            if (sDefault == null) sDefault = new LocaleList(Locale.getDefault());
            return sDefault;
        }
    }
    public static LocaleList getAdjustedDefault() { return getDefault(); }
    public static void setDefault(LocaleList l) { synchronized (LocaleList.class) { sDefault = l; if (!l.isEmpty()) Locale.setDefault(l.get(0)); } }
    public static boolean isPseudoLocalesOnly(String[] supportedLocales) { return false; }
    public LocaleList(java.util.Locale topLocale, LocaleList otherLocales) {
        int n = 1 + (otherLocales != null ? otherLocales.size() : 0);
        java.util.ArrayList<java.util.Locale> l = new java.util.ArrayList<>(n);
        l.add(topLocale);
        if (otherLocales != null) {
            for (int i = 0; i < otherLocales.size(); i++) {
                if (!otherLocales.get(i).equals(topLocale)) l.add(otherLocales.get(i));
            }
        }
        mList = l.toArray(new java.util.Locale[0]);
    }
    public java.util.Locale getFirstMatch(String[] supportedLocales) { return isEmpty() ? null : get(0); }
    public java.util.Locale[] getIntersection(LocaleList other) { return mList.clone(); }
    public java.util.Locale getFirstMatchWithEnglishSupported(String[] supportedLocales) { return getFirstMatch(supportedLocales); }
    public static LocaleList forLanguageTags(String list) {
        if (list == null || list.isEmpty()) return getEmptyLocaleList();
        String[] tags = list.split(",");
        Locale[] locales = new Locale[tags.length];
        for (int i = 0; i < tags.length; i++) locales[i] = Locale.forLanguageTag(tags[i]);
        return new LocaleList(locales);
    }
    public String toLanguageTags() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mList.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(mList[i].toLanguageTag());
        }
        return sb.toString();
    }
    @Override public boolean equals(Object o) {
        return o instanceof LocaleList && java.util.Arrays.equals(mList, ((LocaleList) o).mList);
    }
    @Override public int hashCode() { return java.util.Arrays.hashCode(mList); }
    @Override public String toString() { return toLanguageTags(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(toLanguageTags()); }
    public static final Creator<LocaleList> CREATOR = new Creator<LocaleList>() {
        public LocaleList createFromParcel(Parcel in) { return forLanguageTags(in.readString()); }
        public LocaleList[] newArray(int size) { return new LocaleList[size]; }
    };
}
