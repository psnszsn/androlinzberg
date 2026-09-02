/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class PendingIntent implements android.os.Parcelable {
    private static final int KIND_NONE = 0;
    private static final int KIND_ACTIVITY = 1;
    private static final int KIND_SERVICE = 2;
    private static final int KIND_BROADCAST = 3;
    private final android.content.Context context;
    private final android.content.Intent intent;
    private final int requestCode;
    private final int kind;
    private final String creatorPackage;
    private boolean canceled;
    public PendingIntent() {
        context = null;
        intent = null;
        requestCode = 0;
        kind = KIND_NONE;
        creatorPackage = null;
    }
    public PendingIntent(android.os.IBinder target) { this(); }
    public PendingIntent(android.content.IIntentSender target) { this(); }
    public PendingIntent(android.content.IntentSender sender) { this(); }
    private PendingIntent(android.content.Context context, int requestCode,
            android.content.Intent intent, int kind) {
        this.context = context;
        this.requestCode = requestCode;
        this.intent = intent != null ? new android.content.Intent(intent) : null;
        this.kind = kind;
        this.creatorPackage = context != null ? context.getPackageName() : null;
    }
    private PendingIntent(android.os.Parcel source) {
        kind = source.readInt();
        requestCode = source.readInt();
        creatorPackage = source.readString();
        intent = source.readParcelable(PendingIntent.class.getClassLoader(),
                android.content.Intent.class);
        context = null;
        canceled = source.readBoolean();
    }
    public static final int FLAG_ONE_SHOT = 1 << 30, FLAG_NO_CREATE = 1 << 29,
            FLAG_CANCEL_CURRENT = 1 << 28, FLAG_UPDATE_CURRENT = 1 << 27,
            FLAG_IMMUTABLE = 1 << 26, FLAG_MUTABLE = 1 << 25;
    public static PendingIntent getActivity(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) {
        return new PendingIntent(context, requestCode, intent, KIND_ACTIVITY);
    }
    public static PendingIntent getService(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) {
        return new PendingIntent(context, requestCode, intent, KIND_SERVICE);
    }
    public static PendingIntent getBroadcast(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) {
        return new PendingIntent(context, requestCode, intent, KIND_BROADCAST);
    }
    public static PendingIntent getForegroundService(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) {
        return new PendingIntent(context, requestCode, intent, KIND_SERVICE);
    }
    public static PendingIntent getActivity(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags, android.os.Bundle options) {
        return getActivity(context, requestCode, intent, flags);
    }
    public static PendingIntent getActivities(android.content.Context context, int requestCode,
            android.content.Intent[] intents, int flags) {
        android.content.Intent intent = intents != null && intents.length > 0
                ? intents[intents.length - 1] : null;
        return getActivity(context, requestCode, intent, flags);
    }
    public static PendingIntent getActivities(android.content.Context context, int requestCode,
            android.content.Intent[] intents, int flags, android.os.Bundle options) {
        return getActivities(context, requestCode, intents, flags);
    }
    public boolean isImmutable() { return true; }
    public String getCreatorPackage() { return creatorPackage; }
    public android.content.Intent getIntent() {
        return intent != null ? new android.content.Intent(intent) : null;
    }
    public android.content.IntentSender getIntentSender() { return new android.content.IntentSender(); }
    public static class CanceledException extends android.util.AndroidException {
        public CanceledException() {}
        public CanceledException(String name) { super(name); }
        public CanceledException(Exception cause) { super(cause); }
    }
    public void send() throws CanceledException { send(context, 0, null); }
    public void send(android.os.Bundle options) throws CanceledException { send(); }
    public void send(android.content.Context sendContext, int code,
            android.content.Intent fillInIntent) throws CanceledException {
        if (canceled) throw new CanceledException("PendingIntent is canceled");
        android.content.Context targetContext = context != null ? context : sendContext;
        android.content.Intent targetIntent = intent != null ? new android.content.Intent(intent)
                : fillInIntent != null ? new android.content.Intent(fillInIntent) : null;
        if (targetContext == null || targetIntent == null) return;
        if (kind == KIND_BROADCAST) targetContext.sendBroadcast(targetIntent);
        else if (kind == KIND_SERVICE) targetContext.startService(targetIntent);
        else if (kind == KIND_ACTIVITY) targetContext.startActivity(targetIntent);
    }
    public boolean isActivity() { return kind == KIND_ACTIVITY; }
    public void cancel() { canceled = true; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PendingIntent)) return false;
        PendingIntent that = (PendingIntent) other;
        String thisPackage = getCreatorPackage();
        String thatPackage = that.getCreatorPackage();
        return kind == that.kind && requestCode == that.requestCode
                && java.util.Objects.equals(thisPackage, thatPackage)
                && (intent == null ? that.intent == null
                        : that.intent != null && intent.filterEquals(that.intent));
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(kind, requestCode, getCreatorPackage(),
                intent != null ? intent.filterHashCode() : 0);
    }
    public android.os.IBinder getTarget$stub() { return null; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel out, int flags) {
        out.writeInt(kind);
        out.writeInt(requestCode);
        out.writeString(creatorPackage);
        out.writeParcelable(intent, flags);
        out.writeBoolean(canceled);
    }
    public static final android.os.Parcelable.Creator<PendingIntent> CREATOR =
            new android.os.Parcelable.Creator<PendingIntent>() {
        @Override public PendingIntent createFromParcel(android.os.Parcel source) {
            return new PendingIntent(source);
        }
        @Override public PendingIntent[] newArray(int size) {
            return new PendingIntent[size];
        }
    };
}
