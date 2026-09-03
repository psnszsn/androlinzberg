/* artzig stub: no device policy service in a hosted session. */
package android.app.admin;
public class DevicePolicyManager {
    private final DevicePolicyResourcesManager mResources = new DevicePolicyResourcesManager();
    public DevicePolicyResourcesManager getResources() { return mResources; }
    /** AOSP forwards a managed-profile contact lookup to the device policy service; no managed profile exists here. */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId, boolean isContactIdIgnored,
            long directoryId, android.content.Intent originalIntent) {}
}
