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
    public CharSequence loadLabel(PackageManager pm) {
        return nonLocalizedLabel != null ? nonLocalizedLabel : String.valueOf(name);
    }
    public android.graphics.drawable.Drawable loadIcon(PackageManager pm) { return null; }
    public android.content.res.XmlResourceParser loadXmlMetaData(PackageManager pm, String name) { return null; }
}
