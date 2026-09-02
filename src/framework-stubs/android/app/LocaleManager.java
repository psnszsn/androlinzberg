/* artzig stub of AOSP android.app.LocaleManager: the per-app locale service
 * (ILocaleManager) does not exist on this host; no locale override is ever set. */
package android.app;
public class LocaleManager {
    public LocaleManager() {}
    public LocaleConfig getOverrideLocaleConfig() { return null; }
    public void setOverrideLocaleConfig(LocaleConfig localeConfig) {}
    public android.os.LocaleList getApplicationLocales() { return android.os.LocaleList.getEmptyLocaleList(); }
    public android.os.LocaleList getApplicationLocales(String appPackageName) { return android.os.LocaleList.getEmptyLocaleList(); }
    public void setApplicationLocales(android.os.LocaleList locales) {}
    public android.os.LocaleList getSystemLocales() { return android.os.LocaleList.getDefault(); }
}
