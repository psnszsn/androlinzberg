/* artzig stub of AOSP android.service.voice.VisibleActivityInfo: the value
 * type of IVoiceInteractionSession; no voice interaction session exists on
 * this host (VoiceInteractionSession is not vendored). */
package android.service.voice;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
public final class VisibleActivityInfo implements Parcelable {
    private final int mActivityId;
    private final IBinder mAssistToken;
    public VisibleActivityInfo(int activityId, IBinder assistToken) { mActivityId = activityId; mAssistToken = assistToken; }
    public int getActivityId() { return mActivityId; }
    public IBinder getAssistToken() { return mAssistToken; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(mActivityId); dest.writeStrongBinder(mAssistToken); }
    public static final Creator<VisibleActivityInfo> CREATOR = new Creator<VisibleActivityInfo>() {
        @Override public VisibleActivityInfo createFromParcel(Parcel in) { return new VisibleActivityInfo(in.readInt(), in.readStrongBinder()); }
        @Override public VisibleActivityInfo[] newArray(int size) { return new VisibleActivityInfo[size]; }
    };
}
