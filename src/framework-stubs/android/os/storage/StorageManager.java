/* artzig shim: host filesystem-backed storage accounting. */
package android.os.storage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
public class StorageManager {
    public static final UUID UUID_DEFAULT = new UUID(0L, 0L);
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
