/* artzig stub. */
package android.content;
import android.os.Parcel;
import android.os.Parcelable;
public class IntentSender implements Parcelable {
    public static class SendIntentException extends android.util.AndroidException {
        public SendIntentException() {}
        public SendIntentException(String name) { super(name); }
        public SendIntentException(Exception cause) { super(cause); }
    }
    public void sendIntent(Context context, int code, Intent intent, Object onFinished, android.os.Handler handler)
            throws SendIntentException {}
    public IIntentSender getTarget() { return null; }
    public boolean isImmutable() { return false; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {}
    public static final Creator<IntentSender> CREATOR = new Creator<IntentSender>() {
        public IntentSender createFromParcel(Parcel in) { return new IntentSender(); }
        public IntentSender[] newArray(int size) { return new IntentSender[size]; }
    };
    /** AOSP IntentSender.getWhitelistToken: no temp-allowlist token on this host. */
    public android.os.IBinder getWhitelistToken() { return null; }
}
