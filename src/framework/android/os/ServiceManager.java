/* Hosted adaptation of AOSP android.os.ServiceManager.
 *
 * Without a hosted Binder session this is the in-process registry (ATL pattern). With one
 * (AZK_BINDER_SOCKET set), names live in the broker so services registered by one process are
 * visible to every process in the session; getService returns a BinderProxy for remote owners. */
package android.os;
import java.util.concurrent.ConcurrentHashMap;
public final class ServiceManager {
    private static final ConcurrentHashMap<String, IBinder> sServices = new ConcurrentHashMap<>();
    private ServiceManager() {}
    private static boolean hosted() { return HostedBinderRuntime.isEnabled(); }
    /** Null when the session's broker cannot be reached: services are simply unavailable. */
    private static HostedBinderRuntime runtime() { return HostedBinderRuntime.getOrNull(); }
    public static IBinder getService(String name) {
        if (hosted()) {
            HostedBinderRuntime runtime = runtime();
            return runtime != null ? runtime.lookupService(name, false) : null;
        }
        return sServices.get(name);
    }
    public static IBinder getServiceOrThrow(String name) throws ServiceNotFoundException {
        IBinder b = getService(name);
        if (b == null) throw new ServiceNotFoundException(name);
        return b;
    }
    public static void addService(String name, IBinder service) { addService(name, service, false, 0); }
    public static void addService(String name, IBinder service, boolean allowIsolated) { addService(name, service, allowIsolated, 0); }
    public static void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) {
        if (hosted()) {
            HostedBinderRuntime runtime = runtime();
            if (runtime == null) throw new IllegalStateException("hosted Binder broker is unavailable");
            if (!runtime.registerService(name, service)) {
                throw new SecurityException("Service " + name + " is registered by another process");
            }
            return;
        }
        sServices.put(name, service);
    }
    public static IBinder checkService(String name) { return getService(name); }
    public static String[] listServices() {
        if (hosted()) {
            HostedBinderRuntime runtime = runtime();
            return runtime != null ? runtime.listServices() : new String[0];
        }
        return sServices.keySet().toArray(new String[0]);
    }
    public static IBinder waitForService(String name) {
        if (hosted()) {
            HostedBinderRuntime runtime = runtime();
            return runtime != null ? runtime.lookupService(name, true) : null;
        }
        return sServices.get(name);
    }
    /** AOSP waits only for declared services and returns null otherwise; nothing is declared here. */
    public static IBinder waitForDeclaredService(String name) { return checkService(name); }
    public static boolean isDeclared(String name) { return checkService(name) != null; }
    public static class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String name) { super("No service published for: " + name); }
    }
}
