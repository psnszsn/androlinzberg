/* artzig stub of AOSP android.net.Network: the Connectivity module tree is not vendored.
 * A network handle is an id (JobParameters parcels it); the host has one network. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

public class Network implements Parcelable {
    public final int netId;

    public Network(int netId) { this.netId = netId; }
    public Network(Network that) { this(that.netId); }

    public long getNetworkHandle() { return ((long) netId << 32) | 0xcafeL; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(netId); }
    public static final Creator<Network> CREATOR = new Creator<Network>() {
        @Override public Network createFromParcel(Parcel in) { return new Network(in.readInt()); }
        @Override public Network[] newArray(int size) { return new Network[size]; }
    };
    @Override public boolean equals(Object obj) { return obj instanceof Network && ((Network) obj).netId == netId; }
    @Override public int hashCode() { return netId * 11; }
    @Override public String toString() { return Integer.toString(netId); }
}
