/* artzig shim: android.content.pm.ComponentInfo. */
package android.content.pm;
public class ComponentInfo extends PackageItemInfo {
    public ApplicationInfo applicationInfo;
    public String processName;
    public int descriptionRes;
    public boolean enabled = true;
    public boolean exported;
    public boolean directBootAware;
    public ComponentInfo() {}
    public ComponentInfo(ComponentInfo orig) {
        super(orig);
        applicationInfo = orig.applicationInfo; processName = orig.processName;
        enabled = orig.enabled; exported = orig.exported; directBootAware = orig.directBootAware;
    }
    public android.content.ComponentName getComponentName() {
        return new android.content.ComponentName(String.valueOf(packageName), String.valueOf(name));
    }
}
