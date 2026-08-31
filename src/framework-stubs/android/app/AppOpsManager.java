/* artzig stub: app-ops default to allowed on this host — access control is
 * the uid/permission model. Checks consult the session's HostedAppOpsService
 * ("appops") when published, so the system can turn individual ops off. */
package android.app;
public class AppOpsManager {
    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;
    public static final int MODE_DEFAULT = 3;
    public static final int OP_NONE = -1;
    public static final int _NUM_OP = 148;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface SamplingStrategy {}
    /** AOSP maps op codes to public op names; this host has no table. */
    public static String opToPublicName(int op) { return "op:" + op; }
    public static final int OP_SYSTEM_ALERT_WINDOW = 24;
    public static final int OP_WRITE_SETTINGS = 23;
    public static final int OP_READ_CLIPBOARD = 29;
    public static final int OP_WRITE_CLIPBOARD = 30;
    /** Nested types of the IAppOpsService surface. */
    public static class HistoricalOps implements android.os.Parcelable {
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<HistoricalOps> CREATOR = new Creator<HistoricalOps>() {
            @Override public HistoricalOps createFromParcel(android.os.Parcel source) { return new HistoricalOps(); }
            @Override public HistoricalOps[] newArray(int size) { return new HistoricalOps[size]; }
        };
    }
    /** Nested result type of the IAppOpsService query surface. */
    public static class PackageOps implements android.os.Parcelable {
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<PackageOps> CREATOR = new Creator<PackageOps>() {
            @Override public PackageOps createFromParcel(android.os.Parcel source) { return new PackageOps(); }
            @Override public PackageOps[] newArray(int size) { return new PackageOps[size]; }
        };
    }
    public void checkPackage(int uid, String packageName) {}
    public int noteOp(int op, int uid, String packageName, String attributionTag, String message) {
        return checkWithService(op, uid, packageName);
    }
    public int checkOp(int op, int uid, String packageName) { return checkWithService(op, uid, packageName); }
    public int unsafeCheckOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
    /** The session's app-ops service, when published; MODE_ALLOWED before.
     * Cached once resolved: the registry lookup is a broker round trip. */
    private static volatile com.android.internal.app.IAppOpsService sService;
    private static com.android.internal.app.IAppOpsService service() {
        com.android.internal.app.IAppOpsService cached = sService;
        if (cached != null) return cached;
        android.os.IBinder binder = android.os.ServiceManager.getService("appops");
        if (binder == null) return null;
        cached = com.android.internal.app.IAppOpsService.Stub.asInterface(binder);
        sService = cached;
        return cached;
    }
    private static int checkWithService(int op, int uid, String packageName) {
        com.android.internal.app.IAppOpsService service = service();
        if (service == null) return MODE_ALLOWED;
        try { return service.checkOperation(op, uid, packageName); }
        catch (android.os.RemoteException e) { return MODE_ALLOWED; }
    }
    public int checkOpNoThrow(int op, int uid, String packageName) { return checkWithService(op, uid, packageName); }
    public int noteOpNoThrow(int op, int uid, String packageName, String attributionTag, String message) {
        return checkWithService(op, uid, packageName);
    }
}
