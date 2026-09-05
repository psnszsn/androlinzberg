/* artzig stub: the stock server class reaches VirtualDevice/IVirtualDevice and
 * the fenced display, input, Bluetooth, and media stacks. The host registers
 * a default-device-only implementation of this narrow LocalServices contract
 * so reused services share one explicit empty virtual-device state. */
package com.android.server.companion.virtual;
public abstract class VirtualDeviceManagerInternal {
    public abstract android.util.ArraySet<Integer> getDeviceIdsForUid(int uid);
    public abstract int getDeviceOwnerUid(int deviceId);
    public abstract android.util.ArraySet<Integer> getDisplayIdsForDevice(int deviceId);
}
