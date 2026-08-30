/* artzig stub: no virtual devices on this host; nothing registers this in
 * LocalServices, so the reused services take their null (default-device)
 * paths. The class exists only so they compile. */
package com.android.server.companion.virtual;
public abstract class VirtualDeviceManagerInternal {
    public abstract android.util.ArraySet<Integer> getDeviceIdsForUid(int uid);
    public abstract int getDeviceOwnerUid(int deviceId);
    public abstract android.util.ArraySet<Integer> getDisplayIdsForDevice(int deviceId);
}
