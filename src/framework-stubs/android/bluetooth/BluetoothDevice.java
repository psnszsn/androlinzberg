/* artzig stub of AOSP android.bluetooth.BluetoothDevice: the Bluetooth module tree is
 * not vendored and there is no bluetooth stack here. Value shape only (an address),
 * Parcelable because telecom's CallAudioState carries the active device. */
package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

public class BluetoothDevice implements Parcelable {
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";

    private final String mAddress;

    public BluetoothDevice() { this("00:00:00:00:00:00"); }
    public BluetoothDevice(String address) { mAddress = address; }

    /** AOSP attaches the receiving process's attribution; no bluetooth stack here. */
    public void prepareToEnterProcess(android.content.AttributionSource attributionSource) {}
    public String getAddress() { return mAddress; }
    public String getName() { return "none"; }

    @Override public boolean equals(Object o) { return o instanceof BluetoothDevice && mAddress.equals(((BluetoothDevice) o).mAddress); }
    @Override public int hashCode() { return mAddress.hashCode(); }
    @Override public String toString() { return mAddress; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(mAddress); }

    public static final Creator<BluetoothDevice> CREATOR = new Creator<BluetoothDevice>() {
        @Override public BluetoothDevice createFromParcel(Parcel in) { return new BluetoothDevice(in.readString()); }
        @Override public BluetoothDevice[] newArray(int size) { return new BluetoothDevice[size]; }
    };
}
