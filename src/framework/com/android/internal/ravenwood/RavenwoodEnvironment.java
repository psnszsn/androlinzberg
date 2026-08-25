/* artzig stub: ravenwood test environment — never active. */
package com.android.internal.ravenwood;
public final class RavenwoodEnvironment {
    private static final RavenwoodEnvironment sInstance = new RavenwoodEnvironment();
    public static RavenwoodEnvironment getInstance() { return sInstance; }
    public static boolean isRunningOnRavenwood() { return false; }
    public static void ensureRavenwoodInitialized() {}
    public static RavenwoodEnvironment workaround() { return sInstance; }
    public boolean isRunningOnRavenwood$ravenwood() { return false; }
    public <T> T fromAddress(long address) { throw new UnsupportedOperationException("ravenwood-only"); }
    public String getRavenwoodRuntimePath() { return "/"; }
}
