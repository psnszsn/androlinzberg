/* artzig stub. */
package android.app;
import android.os.Parcel;
import android.os.Parcelable;
public final class RemoteAction implements Parcelable {
    public CharSequence getTitle() { return ""; }
    public CharSequence getContentDescription() { return ""; }
    public PendingIntent getActionIntent() { return null; }
    public android.graphics.drawable.Icon getIcon() { return null; }
    public boolean isEnabled() { return true; }
    public boolean shouldShowIcon() { return false; }
    public RemoteAction clone$stub() { return this; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Creator<RemoteAction> CREATOR = new Creator<RemoteAction>() {
        public RemoteAction createFromParcel(Parcel in) { return new RemoteAction(); }
        public RemoteAction[] newArray(int size) { return new RemoteAction[size]; }
    };
}
