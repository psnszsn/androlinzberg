/* artzig stub: a single internal private volume. */
package android.os.storage;
public class VolumeInfo {
    public static final String ID_PRIVATE_INTERNAL = "private";
    public static final int TYPE_PUBLIC = 0, TYPE_PRIVATE = 1, TYPE_EMULATED = 2;
    public final String id;
    public final int type;
    public String fsUuid;
    public VolumeInfo(String id, int type) { this.id = id; this.type = type; }
    public String getId() { return id; }
    public int getType() { return type; }
    public boolean isMountedWritable() { return ID_PRIVATE_INTERNAL.equals(id); }
    public boolean isPrimaryPhysical() { return false; }
}
