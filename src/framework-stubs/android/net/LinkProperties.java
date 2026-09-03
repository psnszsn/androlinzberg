/* artzig stub of AOSP android.net.LinkProperties: the Connectivity module tree is not
 * vendored; the host network has no Android-specific link configuration (no proxy,
 * no interface name, no addresses or routes). Parcels (empty) for telephony's
 * PreciseDataConnectionState and ITelephonyRegistry. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

public class LinkProperties implements Parcelable {
    public LinkProperties() {}
    public LinkProperties(LinkProperties source) {}

    public ProxyInfo getHttpProxy() { return null; }
    public String getInterfaceName() { return null; }
    public List<InetAddress> getDnsServers() { return Collections.emptyList(); }
    public List<LinkAddress> getLinkAddresses() { return Collections.emptyList(); }
    public List<Object> getRoutes() { return Collections.emptyList(); }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}

    public static final Creator<LinkProperties> CREATOR = new Creator<LinkProperties>() {
        @Override public LinkProperties createFromParcel(Parcel in) { return new LinkProperties(); }
        @Override public LinkProperties[] newArray(int size) { return new LinkProperties[size]; }
    };
}
