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
    /** AOSP ComponentInfo.writeToParcel field order for the fields this shim has. */
    protected void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        if (applicationInfo != null) {
            dest.writeInt(1);
            applicationInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString8(processName);
        dest.writeString8(null); // splitName
        dest.writeString8(null); // attributionTags placeholder
        dest.writeInt(descriptionRes);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(exported ? 1 : 0);
        dest.writeInt(directBootAware ? 1 : 0);
    }
    protected ComponentInfo(android.os.Parcel source) {
        super(source);
        if (source.readInt() != 0) applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);
        processName = source.readString8();
        source.readString8();
        source.readString8();
        descriptionRes = source.readInt();
        enabled = source.readInt() != 0;
        exported = source.readInt() != 0;
        directBootAware = source.readInt() != 0;
    }
}
