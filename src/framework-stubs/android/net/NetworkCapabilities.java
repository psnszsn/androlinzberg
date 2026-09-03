/* artzig stub of AOSP android.net.NetworkCapabilities: the Connectivity module tree is
 * not vendored. Describes the host's single default network (an unmetered, validated
 * wifi/ethernet-class link); carries the capability/transport ids telephony and the
 * job scheduler reason about, and parcels (empty) for ITelephonyRegistry. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

public class NetworkCapabilities implements Parcelable {
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_BLUETOOTH = 2;
    public static final int TRANSPORT_ETHERNET = 3;
    public static final int TRANSPORT_VPN = 4;
    public static final int TRANSPORT_WIFI_AWARE = 5;
    public static final int TRANSPORT_LOWPAN = 6;
    public static final int TRANSPORT_TEST = 7;
    public static final int TRANSPORT_USB = 8;
    public static final int TRANSPORT_THREAD = 9;
    public static final int TRANSPORT_SATELLITE = 10;

    public static final int NET_CAPABILITY_MMS = 0;
    public static final int NET_CAPABILITY_SUPL = 1;
    public static final int NET_CAPABILITY_DUN = 2;
    public static final int NET_CAPABILITY_FOTA = 3;
    public static final int NET_CAPABILITY_IMS = 4;
    public static final int NET_CAPABILITY_CBS = 5;
    public static final int NET_CAPABILITY_WIFI_P2P = 6;
    public static final int NET_CAPABILITY_IA = 7;
    public static final int NET_CAPABILITY_RCS = 8;
    public static final int NET_CAPABILITY_XCAP = 9;
    public static final int NET_CAPABILITY_EIMS = 10;
    public static final int NET_CAPABILITY_NOT_METERED = 11;
    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int NET_CAPABILITY_NOT_RESTRICTED = 13;
    public static final int NET_CAPABILITY_TRUSTED = 14;
    public static final int NET_CAPABILITY_NOT_VPN = 15;
    public static final int NET_CAPABILITY_VALIDATED = 16;
    public static final int NET_CAPABILITY_CAPTIVE_PORTAL = 17;
    public static final int NET_CAPABILITY_NOT_ROAMING = 18;
    public static final int NET_CAPABILITY_FOREGROUND = 19;
    public static final int NET_CAPABILITY_NOT_CONGESTED = 20;
    public static final int NET_CAPABILITY_NOT_SUSPENDED = 21;
    public static final int NET_CAPABILITY_OEM_PAID = 22;
    public static final int NET_CAPABILITY_MCX = 23;
    public static final int NET_CAPABILITY_PARTIAL_CONNECTIVITY = 24;
    public static final int NET_CAPABILITY_TEMPORARILY_NOT_METERED = 25;
    public static final int NET_CAPABILITY_OEM_PRIVATE = 26;
    public static final int NET_CAPABILITY_VEHICLE_INTERNAL = 27;
    public static final int NET_CAPABILITY_NOT_VCN_MANAGED = 28;
    public static final int NET_CAPABILITY_ENTERPRISE = 29;
    public static final int NET_CAPABILITY_VSIM = 30;
    public static final int NET_CAPABILITY_BIP = 31;
    public static final int NET_CAPABILITY_HEAD_UNIT = 32;
    public static final int NET_CAPABILITY_MMTEL = 33;
    public static final int NET_CAPABILITY_PRIORITIZE_LATENCY = 34;
    public static final int NET_CAPABILITY_PRIORITIZE_BANDWIDTH = 35;
    public static final int NET_CAPABILITY_LOCAL_NETWORK = 36;
    public static final int NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37;

    public static final int NET_ENTERPRISE_ID_1 = 1;
    public static final int NET_ENTERPRISE_ID_2 = 2;
    public static final int NET_ENTERPRISE_ID_3 = 3;
    public static final int NET_ENTERPRISE_ID_4 = 4;
    public static final int NET_ENTERPRISE_ID_5 = 5;

    public NetworkCapabilities() {}
    public NetworkCapabilities(NetworkCapabilities nc) {}

    public boolean hasTransport(int transportType) { return transportType == TRANSPORT_WIFI || transportType == TRANSPORT_ETHERNET; }
    public boolean hasCapability(int capability) { return true; }
    public int[] getCapabilities() {
        return new int[] { NET_CAPABILITY_INTERNET, NET_CAPABILITY_NOT_METERED, NET_CAPABILITY_NOT_RESTRICTED,
                NET_CAPABILITY_TRUSTED, NET_CAPABILITY_NOT_VPN, NET_CAPABILITY_VALIDATED, NET_CAPABILITY_NOT_ROAMING,
                NET_CAPABILITY_NOT_CONGESTED, NET_CAPABILITY_NOT_SUSPENDED };
    }
    public int[] getTransportTypes() { return new int[] { TRANSPORT_WIFI, TRANSPORT_ETHERNET }; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}

    public static final Creator<NetworkCapabilities> CREATOR = new Creator<NetworkCapabilities>() {
        @Override public NetworkCapabilities createFromParcel(Parcel in) { return new NetworkCapabilities(); }
        @Override public NetworkCapabilities[] newArray(int size) { return new NetworkCapabilities[size]; }
    };
}
