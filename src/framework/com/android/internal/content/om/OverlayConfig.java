/* artzig stub: no RRO overlays on host. */
package com.android.internal.content.om;
public class OverlayConfig {
    private static final OverlayConfig sInstance = new OverlayConfig();
    public static OverlayConfig getZygoteInstance() { return sInstance; }
    public static OverlayConfig getSystemInstance() { return sInstance; }
    public String[] createImmutableFrameworkIdmapsInZygote() { return new String[0]; }
    public boolean isEnabled(String packageName) { return false; }
}
