/* artzig shim: host filesystem-backed storage statistics. */
package android.app.usage;
import java.io.IOException;
import java.util.UUID;
public class StorageStatsManager {
    public long getFreeBytes(UUID storageUuid) throws IOException {
        return android.os.Environment.getDataDirectory().getUsableSpace();
    }
    public long getTotalBytes(UUID storageUuid) throws IOException {
        return android.os.Environment.getDataDirectory().getTotalSpace();
    }
}
