/* artzig shim: host filesystem-backed storage accounting. */
package android.os.storage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
public class StorageManager {
    public static final UUID UUID_DEFAULT = new UUID(0L, 0L);
    /** AOSP: null marks internal storage; the magic string a primary SD card. */
    public static final String UUID_PRIVATE_INTERNAL = null;
    public static final String UUID_PRIMARY_PHYSICAL = "primary_physical";
    private static final VolumeInfo INTERNAL =
            new VolumeInfo(VolumeInfo.ID_PRIVATE_INTERNAL, VolumeInfo.TYPE_PRIVATE);
    public VolumeInfo findVolumeById(String id) {
        return VolumeInfo.ID_PRIVATE_INTERNAL.equals(id) ? INTERNAL : null;
    }
    public VolumeInfo findVolumeByUuid(String fsUuid) { return null; }
    public VolumeInfo findVolumeByQualifiedUuid(String volumeUuid) {
        return volumeUuid == null ? INTERNAL : null;
    }
    public java.util.List<VolumeInfo> getVolumes() {
        return java.util.Collections.singletonList(INTERNAL);
    }
    public String getPrimaryStorageUuid() { return UUID_PRIVATE_INTERNAL; }
    public UUID getUuidForPath(File path) throws IOException {
        if (path == null) throw new IOException("null path");
        return UUID_DEFAULT;
    }
    public long getAllocatableBytes(UUID storageUuid) throws IOException {
        return android.os.Environment.getDataDirectory().getUsableSpace();
    }
    public long getAllocatableBytes(UUID storageUuid, int flags) throws IOException {
        return getAllocatableBytes(storageUuid);
    }
    public static java.util.UUID convert(String volumeUuid) {
        return volumeUuid == null ? UUID_DEFAULT : java.util.UUID.fromString(volumeUuid);
    }
    /** AOSP maps the default-storage sentinel UUID to null (internal). */
    public static String convert(java.util.UUID storageUuid) {
        return UUID_DEFAULT.equals(storageUuid) ? null : storageUuid.toString();
    }
}
