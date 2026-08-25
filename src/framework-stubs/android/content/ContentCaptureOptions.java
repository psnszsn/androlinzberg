/* artzig stub. */
package android.content;
public final class ContentCaptureOptions  implements android.os.Parcelable {
    public final int loggingLevel = 0;
    public final boolean lite = true;
    public final boolean enableReceiver = false;
    public final int maxBufferSize = 0;
    public final int idleFlushingFrequencyMs = 0, textChangeFlushingFrequencyMs = 0, logHistorySize = 0;
    public final android.util.ArraySet<ComponentName> whitelistedComponents = null;
    public final ContentProtectionOptions contentProtectionOptions = new ContentProtectionOptions();
    public static class ContentProtectionOptions {
        public final boolean enableReceiver = false;
        public final int bufferSize = 0;
        public final java.util.List<java.util.List<String>> requiredGroups = new java.util.ArrayList<>();
        public final java.util.List<java.util.List<String>> optionalGroups = new java.util.ArrayList<>();
        public final int optionalGroupsThreshold = 0;
    }
    public final boolean disableFlushForViewTreeAppearing = false;
    public boolean isWhitelisted(Context context) { return false; }
    public void dumpShort(java.io.PrintWriter pw) { pw.print("content-capture-options-stub"); }
    public static ContentCaptureOptions forWhitelistingItself() { return new ContentCaptureOptions(); }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<ContentCaptureOptions> CREATOR =
            new android.os.Parcelable.Creator<ContentCaptureOptions>() {
        public ContentCaptureOptions createFromParcel(android.os.Parcel in) { return new ContentCaptureOptions(); }
        public ContentCaptureOptions[] newArray(int size) { return new ContentCaptureOptions[size]; }
    };
}
