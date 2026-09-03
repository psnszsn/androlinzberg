/* artzig stub of AOSP android.net.NetworkStats: the Connectivity module tree is not
 * vendored. ITelephony.getVtDataUsage parcels it; this host has no radio, so a stats
 * object is always empty (zero buckets), matching a device without VT usage. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

public final class NetworkStats implements Parcelable {
    public static final int METERED_ALL = -1;
    public static final int METERED_NO = 0;
    public static final int METERED_YES = 1;

    private final long mElapsedRealtime;

    public NetworkStats(long elapsedRealtime, int initialSize) {
        mElapsedRealtime = elapsedRealtime;
    }

    public long getElapsedRealtime() { return mElapsedRealtime; }
    public int size() { return 0; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeLong(mElapsedRealtime); }

    public static final Creator<NetworkStats> CREATOR = new Creator<NetworkStats>() {
        @Override public NetworkStats createFromParcel(Parcel in) { return new NetworkStats(in.readLong(), 0); }
        @Override public NetworkStats[] newArray(int size) { return new NetworkStats[size]; }
    };
}
