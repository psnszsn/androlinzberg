/* artzig shim: android.os.StrictMode — no-op. */
package android.os;
public final class StrictMode {
    /** Direct-boot violations cannot occur: the single hosted user is always unlocked. */
    public static boolean vmImplicitDirectBootEnabled() { return false; }
    public static void onImplicitDirectBoot() {}
    private StrictMode() {}
    public static void noteSlowCall(String name) {}
    public static void noteResourceMismatch(Object tag) {}
    public static void noteUntaggedSocket() {}
    public static void assertConfigurationContext(android.content.Context context, String methodName) {}
    public static void assertUiContext(android.content.Context context, String methodName) {}
    public static Span enterCriticalSpan(String name) { return new Span(); }
    public static boolean vmFileUriExposureEnabled() { return false; }
    public static boolean vmSqliteObjectLeaksEnabled() { return false; }
    public static boolean vmContentUriWithoutPermissionEnabled() { return false; }
    public static void onSqliteObjectLeaked(String message, Throwable originStack) {}
    public static void onWebViewMethodCalledOnWrongThread(Throwable originStack) {}
    public static void onFileUriExposed(android.net.Uri uri, String location) {}
    public static class Span {
        public void finish() {}
    }
    public static ThreadPolicy allowThreadDiskReads() { return new ThreadPolicy(); }
    public static ThreadPolicy allowThreadDiskWrites() { return new ThreadPolicy(); }
    public static void setThreadPolicy(ThreadPolicy p) {}
    public static ThreadPolicy getThreadPolicy() { return new ThreadPolicy(); }
    public static VmPolicy getVmPolicy() { return new VmPolicy(); }
    public static void setVmPolicy(VmPolicy p) {}
    public static class ThreadPolicy {
        public static final ThreadPolicy LAX = new ThreadPolicy();
        public static class Builder {
            public Builder() {}
            public Builder(ThreadPolicy p) {}
            public Builder permitAll() { return this; }
            public Builder detectAll() { return this; }
            public Builder permitDiskReads() { return this; }
            public Builder permitDiskWrites() { return this; }
            public Builder permitNetwork() { return this; }
            public Builder permitUnbufferedIo() { return this; }
            public Builder detectNetwork() { return this; }
            public Builder detectResourceMismatches() { return this; }
            public Builder detectUnbufferedIo() { return this; }
            public Builder penaltyLog() { return this; }
            public Builder penaltyDeath() { return this; }
            public ThreadPolicy build() { return new ThreadPolicy(); }
        }
    }
    public static class VmPolicy {
        public static final VmPolicy LAX = new VmPolicy();
        public static class Builder {
            public Builder() {}
            public Builder(VmPolicy p) {}
            public Builder permitAll() { return this; }
            public Builder detectAll() { return this; }
            public Builder penaltyLog() { return this; }
            public VmPolicy build() { return new VmPolicy(); }
        }
    }
    // Binder violation gathering (real Binder/Parcel call these around each
    // transaction); no policy is ever gathered on this host, so they are no-ops.
    public static void clearGatheredViolations() {}
    public static boolean hasGatheredViolations() { return false; }
    public static void writeGatheredViolationsToParcel(Parcel p) {}
    public static void readAndHandleBinderCallViolations(Parcel p) {}
}
