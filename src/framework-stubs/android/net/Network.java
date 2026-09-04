/* artzig transitional host replacement of AOSP android.net.Network. Stage B compiles stock
 * NetworkUtils before stock Network can replace this class, so the handle members below follow
 * pinned AOSP Network exactly; the host still exposes only one physical network. */
package android.net;

import android.os.Parcel;
import android.os.Parcelable;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
public class Network implements Parcelable {
    // AOSP Network handle encoding, shared with frameworks/base/native/android/net.c.
    private static final long HANDLE_MAGIC = 0xcafed00dL;
    private static final int HANDLE_MAGIC_SIZE = 32;
    private static final int USE_LOCAL_NAMESERVERS_FLAG = 0x80000000;

    public final int netId;
    private final transient boolean mPrivateDnsBypass;

    public Network(int netId) { this(netId, false); }
    public Network(int netId, boolean privateDnsBypass) {
        this.netId = netId;
        this.mPrivateDnsBypass = privateDnsBypass;
    }
    public Network(Network that) { this(that.netId, that.mPrivateDnsBypass); }

    public int getNetId() { return netId; }
    public int getNetIdForResolv() {
        return mPrivateDnsBypass ? USE_LOCAL_NAMESERVERS_FLAG | netId : netId;
    }
    public long getNetworkHandle() {
        if (netId == ConnectivityManager.NETID_UNSET) return 0L;
        return ((long) getNetIdForResolv() << HANDLE_MAGIC_SIZE) | HANDLE_MAGIC;
    }
    public static Network fromNetworkHandle(long networkHandle) {
        if (networkHandle == 0) {
            throw new IllegalArgumentException(
                    "Network.fromNetworkHandle refusing to instantiate NETID_UNSET Network.");
        }
        if ((networkHandle & 0xffffffffL) != HANDLE_MAGIC) {
            throw new IllegalArgumentException(
                    "Value passed to fromNetworkHandle() is not a network handle.");
        }
        final int netIdForResolv = (int) (networkHandle >>> HANDLE_MAGIC_SIZE);
        return new Network(netIdForResolv & ~USE_LOCAL_NAMESERVERS_FLAG,
                (netIdForResolv & USE_LOCAL_NAMESERVERS_FLAG) != 0);
    }

    /**
     * Pinned AOSP Network.bindSocket(FileDescriptor). Stage B's stock DnsUtils needs this before
     * the complete stock Network class replaces the transitional host class.
     */
    public void bindSocket(FileDescriptor fd) throws IOException {
        try {
            final SocketAddress peer = Os.getpeername(fd);
            final InetAddress inetPeer = ((InetSocketAddress) peer).getAddress();
            if (!inetPeer.isAnyLocalAddress()) {
                throw new SocketException("Socket is connected");
            }
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.ENOTCONN) {
                throw e.rethrowAsSocketException();
            }
        } catch (ClassCastException e) {
            throw new SocketException("Only AF_INET/AF_INET6 sockets supported");
        }

        final int err = NetworkUtils.bindSocketToNetwork(fd, netId);
        if (err != 0) {
            throw new ErrnoException("Binding socket to network " + netId, -err)
                    .rethrowAsSocketException();
        }
    }

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
