/* artzig stub of AOSP android.net.LinkAddress: the Connectivity module tree is not
 * vendored. Value shape only (address + prefix length, Parcelable); telephony's
 * DataCallResponse and QosBearerFilter carry lists of these. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public class LinkAddress implements Parcelable {
    private final InetAddress address;
    private final int prefixLength;

    public LinkAddress(InetAddress address, int prefixLength) {
        if (address == null) throw new IllegalArgumentException("address is null");
        if (prefixLength < 0 || prefixLength > (address.getAddress().length * 8)) {
            throw new IllegalArgumentException("invalid prefix length " + prefixLength);
        }
        this.address = address;
        this.prefixLength = prefixLength;
    }

    /** Parses "address/prefix"; a missing prefix means the full address length. */
    public LinkAddress(String address) {
        int slash = address.indexOf('/');
        String host = slash < 0 ? address : address.substring(0, slash);
        InetAddress parsed = InetAddresses.parseNumericAddress(host);
        this.address = parsed;
        this.prefixLength = slash < 0 ? parsed.getAddress().length * 8 : Integer.parseInt(address.substring(slash + 1));
    }

    public InetAddress getAddress() { return address; }
    public int getPrefixLength() { return prefixLength; }
    /** @deprecated use {@link #getPrefixLength()} */
    @Deprecated public int getNetworkPrefixLength() { return prefixLength; }
    public boolean isSameAddressAs(LinkAddress other) {
        return other != null && address.equals(other.address) && prefixLength == other.prefixLength;
    }

    @Override public boolean equals(Object o) {
        return o instanceof LinkAddress && isSameAddressAs((LinkAddress) o);
    }
    @Override public int hashCode() { return Objects.hash(address, prefixLength); }
    @Override public String toString() { return address.getHostAddress() + "/" + prefixLength; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(address.getAddress());
        dest.writeInt(prefixLength);
    }

    public static final Creator<LinkAddress> CREATOR = new Creator<LinkAddress>() {
        @Override public LinkAddress createFromParcel(Parcel in) {
            try {
                return new LinkAddress(InetAddress.getByAddress(in.createByteArray()), in.readInt());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        }
        @Override public LinkAddress[] newArray(int size) { return new LinkAddress[size]; }
    };
}
