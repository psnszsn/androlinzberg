/* artzig stub of AOSP android.net.NetworkRequest: the Connectivity module tree is not
 * vendored. A request records its capabilities and transports (JobInfo parcels and
 * inspects them); nothing is ever matched against a network. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

public class NetworkRequest implements Parcelable {
    private final long mCapabilities;
    private final long mTransports;

    NetworkRequest(long capabilities, long transports) {
        mCapabilities = capabilities;
        mTransports = transports;
    }

    public boolean hasCapability(int capability) { return (mCapabilities & (1L << capability)) != 0; }
    public boolean hasTransport(int transport) { return (mTransports & (1L << transport)) != 0; }
    public NetworkSpecifier getNetworkSpecifier() { return null; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mCapabilities);
        dest.writeLong(mTransports);
    }
    public static final Creator<NetworkRequest> CREATOR = new Creator<NetworkRequest>() {
        @Override public NetworkRequest createFromParcel(Parcel in) { return new NetworkRequest(in.readLong(), in.readLong()); }
        @Override public NetworkRequest[] newArray(int size) { return new NetworkRequest[size]; }
    };
    @Override public boolean equals(Object o) {
        return o instanceof NetworkRequest && ((NetworkRequest) o).mCapabilities == mCapabilities
                && ((NetworkRequest) o).mTransports == mTransports;
    }
    @Override public int hashCode() { return Long.hashCode(mCapabilities * 31 + mTransports); }

    public static class Builder {
        private long mCapabilities;
        private long mTransports;
        public Builder() {}
        public Builder addCapability(int capability) { mCapabilities |= 1L << capability; return this; }
        public Builder removeCapability(int capability) { mCapabilities &= ~(1L << capability); return this; }
        public Builder addTransportType(int transport) { mTransports |= 1L << transport; return this; }
        public Builder removeTransportType(int transport) { mTransports &= ~(1L << transport); return this; }
        public Builder setNetworkSpecifier(NetworkSpecifier specifier) { return this; }
        public NetworkRequest build() { return new NetworkRequest(mCapabilities, mTransports); }
    }
}
