/* artzig stub: no APEX modules on this host (SystemServiceManager.isJarInTestApex). */
package com.android.server.pm;
public abstract class ApexManager {
    private static final ApexManager sInstance = new ApexManager() {};
    public static ApexManager getInstance() { return sInstance; }
    public String getActivePackageNameForApexModuleName(String apexModuleName) { return null; }
}
