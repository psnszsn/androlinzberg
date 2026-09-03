/* artzig stub: android.text.style.SuggestionSpan — inert marker (real with input stack). */
package android.text.style;
import android.os.Parcel;
import android.text.ParcelableSpan;
public class SuggestionSpan extends CharacterStyle implements ParcelableSpan {
    public static final int FLAG_EASY_CORRECT = 0x0001, FLAG_MISSPELLED = 0x0002,
            FLAG_AUTO_CORRECTION = 0x0004, FLAG_GRAMMAR_ERROR = 0x0008;
    public static final int SUGGESTIONS_MAX_SIZE = 5;
    private final String[] mSuggestions;
    private final int mFlags;
    public SuggestionSpan(android.content.Context context, String[] suggestions, int flags) {
        mSuggestions = suggestions; mFlags = flags;
    }
    public SuggestionSpan(java.util.Locale locale, String[] suggestions, int flags) {
        mSuggestions = suggestions; mFlags = flags;
    }
    public SuggestionSpan(android.content.Context context, java.util.Locale locale, String[] suggestions,
            int flags, Class<?> notificationTargetClass) {
        mSuggestions = suggestions; mFlags = flags;
    }
    /** AOSP underline getters (RemoteViewsSerializers); the inert span draws nothing. */
    public int getEasyCorrectUnderlineColor() { return 0; }
    public float getEasyCorrectUnderlineThickness() { return 0f; }
    public int getMisspelledUnderlineColor() { return 0; }
    public float getMisspelledUnderlineThickness() { return 0f; }
    public int getAutoCorrectionUnderlineColor() { return 0; }
    public float getAutoCorrectionUnderlineThickness() { return 0f; }
    public int getGrammarErrorUnderlineColor() { return 0; }
    public float getGrammarErrorUnderlineThickness() { return 0f; }

    /** AOSP @hide constructor used by RemoteViewsSerializers; the inert span keeps only suggestions and flags. */
    public SuggestionSpan(String[] suggestions, int flags, String localeStringForCompatibility,
            String languageTag, int hashCode, int easyCorrectUnderlineColor,
            float easyCorrectUnderlineThickness, int misspelledUnderlineColor,
            float misspelledUnderlineThickness, int autoCorrectionUnderlineColor,
            float autoCorrectionUnderlineThickness, int grammarErrorUnderlineColor,
            float grammarErrorUnderlineThickness) {
        this((java.util.Locale) null, suggestions, flags);
    }

    public SuggestionSpan(android.os.Parcel src) {
        mSuggestions = src.createStringArray();
        mFlags = src.readInt();
    }
    public static final android.os.Parcelable.Creator<SuggestionSpan> CREATOR =
            new android.os.Parcelable.Creator<SuggestionSpan>() {
        public SuggestionSpan createFromParcel(android.os.Parcel in) { return new SuggestionSpan(in); }
        public SuggestionSpan[] newArray(int size) { return new SuggestionSpan[size]; }
    };
    public int getUnderlineColor() { return 0; }
    public void setFlags(int flags) {}
    public java.util.Locale getLocaleObject() { return null; }
    public String getLocale() { return ""; }
    public String[] getSuggestions() { return mSuggestions; }
    public int getFlags() { return mFlags; }
    @Override public void updateDrawState(android.text.TextPaint tp) {}
    @Override public int getSpanTypeId() { return 19; }
    public int getSpanTypeIdInternal() { return 19; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) {}
    public void writeToParcelInternal(Parcel dest, int flags) {}
}
