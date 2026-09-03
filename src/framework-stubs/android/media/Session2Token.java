/* artzig stub of AOSP android.media.Session2Token: the class lives in the Media module tree
 * (packages/modules/Media), which is not vendored; ISession2TokensListener parcels it. A
 * hosted session never creates one, so the parcel form carries only the type and package. */
package android.media;

import android.os.Parcel;
import android.os.Parcelable;

public final class Session2Token implements Parcelable {
    public static final int TYPE_SESSION = 0;
    public static final int TYPE_SESSION_SERVICE = 1;

    private final int mType;
    private final String mPackageName;

    Session2Token(int type, String packageName) {
        mType = type;
        mPackageName = packageName;
    }

    public int getType() { return mType; }
    public String getPackageName() { return mPackageName; }
    public int getUid() { return 0; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString(mPackageName);
    }
    public static final Creator<Session2Token> CREATOR = new Creator<Session2Token>() {
        @Override public Session2Token createFromParcel(Parcel in) { return new Session2Token(in.readInt(), in.readString()); }
        @Override public Session2Token[] newArray(int size) { return new Session2Token[size]; }
    };
}
