/* artzig stub: app-ops default to allowed on this host — access control is
 * the uid/permission model. Checks consult the session's HostedAppOpsService
 * ("appops") when published, so the system can turn individual ops off. */
package android.app;
public class AppOpsManager {
    public static final String OPSTR_MEDIA_ROUTING_CONTROL = "android:media_routing_control";
    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;
    public static final int MODE_DEFAULT = 3;
    public static final int MODE_FOREGROUND = 4;
    // AOSP op names the telephony permission helpers consult (no op table here: the
    // string ops are always MODE_ALLOWED, as the int ops are without the service).
    public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";
    public static final String OPSTR_FINE_LOCATION = "android:fine_location";
    public static final String OPSTR_READ_CALL_LOG = "android:read_call_log";
    public static final String OPSTR_READ_SMS = "android:read_sms";
    public static final String OPSTR_RECEIVE_SMS = "android:receive_sms";
    public static final String OPSTR_RECEIVE_WAP_PUSH = "android:receive_wap_push";
    public static final String OPSTR_SEND_SMS = "android:send_sms";
    public static final String OPSTR_WRITE_SMS = "android:write_sms";
    public static final String OPSTR_READ_PHONE_STATE = "android:read_phone_state";
    public static final String OPSTR_READ_CELL_BROADCASTS = "android:read_cell_broadcasts";
    public static final String OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER = "android:use_icc_auth_with_device_identifier";
    public static final int OP_NONE = -1;
    public static final int OP_GET_USAGE_STATS = 43;
    public static final String OPSTR_GET_USAGE_STATS = "android:get_usage_stats";
    public static final int OP_LEGACY_STORAGE = 87;
    public static final int OP_MANAGE_EXTERNAL_STORAGE = 92;
    public static final int OP_NO_ISOLATED_STORAGE = 99;
    public static final int OP_READ_EXTERNAL_STORAGE = 59;
    public static final int OP_READ_MEDIA_IMAGES = 85;
    public static final int _NUM_OP = 148;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface SamplingStrategy {}
    /** AOSP maps runtime permissions to app-op codes through its op table;
     * app-ops are not an enforcement layer on this host, so no permission has
     * an op: providers and PermissionChecker fall through to the permission check. */
    public static int permissionToOpCode(String permission) { return OP_NONE; }
    public static String permissionToOp(String permission) { return null; }
    public static String opToPermission(int op) { return null; }
    /** AOSP public-name mapping used by the stock stats service. The full op table remains pending. */
    public static int strOpToOp(String op) {
        if ("android:get_usage_stats".equals(op)) return OP_GET_USAGE_STATS;
        throw new IllegalArgumentException("Unknown operation string: " + op);
    }
    /** AOSP noteOp(String, int, String, String, String) delegates through the op table. */
    public int noteOp(String op, int uid, String packageName, String attributionTag, String message) {
        return noteOp(strOpToOp(op), uid, packageName, attributionTag, message);
    }
    /** AOSP maps op codes to public op names; this host has no table. */
    public static String opToPublicName(int op) { return "op:" + op; }
    public static String opToName(int op) { return op == OP_NONE ? "NONE" : "op:" + op; }
    public static String modeToName(int mode) {
        switch (mode) {
            case MODE_ALLOWED: return "allow";
            case MODE_IGNORED: return "ignore";
            case MODE_ERRORED: return "deny";
            case MODE_DEFAULT: return "default";
            case MODE_FOREGROUND: return "foreground";
            default: return "mode=" + mode;
        }
    }
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
    public int unsafeCheckOp(String op, int uid, String packageName) { return MODE_ALLOWED; }
    public int noteOpNoThrow(String op, int uid, String packageName, String attributionTag, String message) { return MODE_ALLOWED; }
    /** AOSP: MANAGE_APP_OPS_MODES writes the uid's op mode; string ops have no mode table here. */
    public void setUidMode(String appOp, int uid, int mode) {}
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
    public int noteOpNoThrow(int op, int uid, String packageName) { return noteOpNoThrow(op, uid, packageName, null, null); }
    public int noteOpNoThrow(int op, int uid, String packageName, String attributionTag, String message) {
        return checkWithService(op, uid, packageName);
    }
    // Noted-app-ops collection hooks the real Binder/Parcel call around each
    // transaction. No collection ever runs on this host: nothing is prefixed to
    // a reply and the "noted appops" reply header therefore never appears.
    public static final class PausedNotedAppOpsCollection {}
    public static void prefixParcelWithAppOpsIfNeeded(android.os.Parcel p) {}
    public static void startNotedAppOpsCollection(int callingUid) {}
    public static void finishNotedAppOpsCollection() {}
    public static PausedNotedAppOpsCollection pauseNotedAppOpsCollection() { return null; }
    public static void resumeNotedAppOpsCollection(PausedNotedAppOpsCollection c) {}
    public static boolean isListeningForOpNoted() { return false; }
    public static void readAndLogNotedAppops(android.os.Parcel p) {}
    /** AOSP AppOpsManager.toReceiverId: the receiver's identity for the activity manager. */
    public static String toReceiverId(Object obj) {
        return obj == null ? null : obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
    }
}
