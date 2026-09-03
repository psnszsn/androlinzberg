/* artzig stub of AOSP android.content.om.OverlayManager: the overlay manager
 * service (IOverlayManager) does not exist on this host. Kept for linkage only
 * (android.content.res.loader.ResourcesProvider imports it); no repo code calls it. */
package android.content.om;
public class OverlayManager {
    public OverlayInfo getOverlayInfo(String packageName, android.os.UserHandle user) { return null; }
    /** No overlays are installed on this host. */
    public java.util.List<OverlayInfo> getOverlayInfosForTarget(String targetPackageName) { return new java.util.ArrayList<>(); }
    /** AOSP commit sends the transaction to the overlay manager service; nothing applies here. */
    public void commit(OverlayManagerTransaction transaction) {}
}
