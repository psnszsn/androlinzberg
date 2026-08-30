/* artzig shim: android.content.pm.PackageItemInfo — base of the pm info hierarchy. */
package android.content.pm;
public class PackageItemInfo {
    public String name;
    public String packageName;
    public int labelRes;
    public CharSequence nonLocalizedLabel;
    public int icon;
    public int logo;
    public int banner;
    public android.os.Bundle metaData;
    public int showUserIcon = -10000;
    public PackageItemInfo() {}
    public PackageItemInfo(PackageItemInfo orig) {
        name = orig.name; packageName = orig.packageName; labelRes = orig.labelRes;
        nonLocalizedLabel = orig.nonLocalizedLabel; icon = orig.icon; logo = orig.logo;
        banner = orig.banner; metaData = orig.metaData;
    }
    /** AOSP PackageItemInfo.writeToParcel field order for the fields this shim has. */
    protected void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        dest.writeString8(name);
        dest.writeString8(packageName);
        dest.writeInt(labelRes);
        dest.writeCharSequence(nonLocalizedLabel);
        dest.writeInt(icon);
        dest.writeInt(logo);
        dest.writeBundle(metaData);
        dest.writeInt(banner);
        dest.writeInt(showUserIcon);
    }
    protected PackageItemInfo(android.os.Parcel source) {
        name = source.readString8();
        packageName = source.readString8();
        labelRes = source.readInt();
        nonLocalizedLabel = source.readCharSequence();
        icon = source.readInt();
        logo = source.readInt();
        metaData = source.readBundle();
        banner = source.readInt();
        showUserIcon = source.readInt();
    }
    public CharSequence loadLabel(PackageManager pm) {
        return nonLocalizedLabel != null ? nonLocalizedLabel : String.valueOf(name);
    }
    public android.graphics.drawable.Drawable loadIcon(PackageManager pm) { return null; }
    public android.content.res.XmlResourceParser loadXmlMetaData(PackageManager pm, String name) { return null; }
}
