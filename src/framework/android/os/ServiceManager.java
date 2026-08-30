/* Hosted adaptation of AOSP android.os.ServiceManager.
 *
 * Names live in the session's Binder broker, so a service registered by one process is visible
 * to every process in the session; getService returns a BinderProxy for remote owners and the
 * local Binder for objects this process owns. Every hosted process runs inside a session; an
 * unreachable broker makes services unavailable (null), matching AOSP's contract. */
package android.os;
import java.util.concurrent.ConcurrentHashMap;
public final class ServiceManager {
    /** Names that are inherently per-process on this host (display, input, later window):
     * addService is session-global and rejects a second owner, while every process has its
     * own Wayland window and input devices. Consulted before the broker. */
    private static final ConcurrentHashMap<String, IBinder> sProcessLocal = new ConcurrentHashMap<>();
    private ServiceManager() {}
    public static void addProcessLocalService(String name, IBinder service) { sProcessLocal.put(name, service); }
    /** Null when the session's broker cannot be reached: services are simply unavailable. */
    private static HostedBinderRuntime runtime() { return HostedBinderRuntime.getOrNull(); }
    public static IBinder getService(String name) {
        IBinder local = sProcessLocal.get(name);
        if (local != null) return local;
        HostedBinderRuntime runtime = runtime();
        return runtime != null ? runtime.lookupService(name, false) : null;
    }
    public static IBinder getServiceOrThrow(String name) throws ServiceNotFoundException {
        IBinder b = getService(name);
        if (b == null) throw new ServiceNotFoundException(name);
        return b;
    }
    public static void addService(String name, IBinder service) { addService(name, service, false, 0); }
    public static void addService(String name, IBinder service, boolean allowIsolated) { addService(name, service, allowIsolated, 0); }
    public static void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) {
        HostedBinderRuntime runtime = runtime();
        if (runtime == null) throw new IllegalStateException("hosted Binder broker is unavailable");
        if (!runtime.registerService(name, service)) {
            throw new SecurityException("Service " + name + " is registered by another process");
        }
    }
    public static IBinder checkService(String name) { return getService(name); }
    public static String[] listServices() {
        HostedBinderRuntime runtime = runtime();
        return runtime != null ? runtime.listServices() : new String[0];
    }
    public static IBinder waitForService(String name) {
        IBinder local = sProcessLocal.get(name);
        if (local != null) return local;
        HostedBinderRuntime runtime = runtime();
        return runtime != null ? runtime.lookupService(name, true) : null;
    }
    /** AOSP waits only for declared services and returns null otherwise; nothing is declared here. */
    public static IBinder waitForDeclaredService(String name) { return checkService(name); }
    public static boolean isDeclared(String name) { return checkService(name) != null; }
    public static class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String name) { super("No service published for: " + name); }
    }
}
