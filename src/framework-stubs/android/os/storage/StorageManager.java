/* artzig shim: host filesystem-backed storage accounting. */
package android.os.storage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
public class StorageManager {
    /** No credential-encrypted storage on this host. */
    public static boolean isEncrypted() { return false; }
    public static boolean isFileEncrypted() { return false; }

    public static final UUID UUID_DEFAULT = new UUID(0L, 0L);
    public static final int FLAG_FOR_WRITE = 1 << 8;
    public static final int FLAG_REAL_STATE = 1 << 9;
    public static final int FLAG_INCLUDE_INVISIBLE = 1 << 10;
    /**
     * AOSP StorageManager.getVolumeList (via the mount service): this host has
     * one primary emulated volume, rooted at $EXTERNAL_STORAGE (the launcher
     * places it under the session data root), always mounted, owned by the
     * requesting user. Environment's UserEnvironment reads it lazily.
     */
    public static StorageVolume[] getVolumeList(int userId, int flags) {
        return new StorageVolume[] { primaryVolume(userId) };
    }
    /** AOSP StorageManager.getStorageVolume(File, int): the primary volume when the path lies under it. */
    public static StorageVolume getStorageVolume(File path, int userId) {
        StorageVolume volume = primaryVolume(userId);
        if (path == null) return null;
        try {
            String root = volume.getPathFile().getCanonicalPath();
            String candidate = path.getCanonicalPath();
            if (candidate.equals(root) || candidate.startsWith(root + File.separator)) return volume;
        } catch (IOException ignored) {
        }
        return null;
    }
    public StorageVolume getPrimaryStorageVolume() {
        return primaryVolume(android.os.UserHandle.myUserId());
    }
    public java.util.List<StorageVolume> getStorageVolumes() {
        return java.util.Collections.singletonList(getPrimaryStorageVolume());
    }
    private static StorageVolume primaryVolume(int userId) {
        String configured = System.getenv("EXTERNAL_STORAGE");
        File root = configured != null && !configured.isEmpty()
                ? new File(configured)
                : new File(android.os.Environment.getDataDirectory(), "external");
        root.mkdirs();
        return new StorageVolume.Builder("emulated;" + userId, root, "Internal shared storage",
                android.os.UserHandle.of(userId), android.os.Environment.MEDIA_MOUNTED)
                .setPrimary(true)
                .setRemovable(false)
                .setEmulated(true)
                .build();
    }
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
    /** AOSP: disabled since sdcardfs replaced FUSE; identity. */
    public static File maybeTranslateEmulatedPathToInternal(File path) { return path; }
    /** No storage sandbox on the host: paths are not translated. */
    public File translateAppToSystem(File file, int pid, int uid) { return file; }
    public File translateSystemToApp(File file, int pid, int uid) { return file; }
    public static java.util.UUID convert(String volumeUuid) {
        return volumeUuid == null ? UUID_DEFAULT : java.util.UUID.fromString(volumeUuid);
    }
    /** AOSP maps the default-storage sentinel UUID to null (internal). */
    public static String convert(java.util.UUID storageUuid) {
        return UUID_DEFAULT.equals(storageUuid) ? null : storageUuid.toString();
    }
    /** AOSP StorageManager.mkdirs/fixupAppDir go through vold; the host creates plain directories. */
    public void mkdirs(java.io.File file) { file.mkdirs(); }
    public void fixupAppDir(java.io.File file) {}
}
