/* artzig shim: android.os.ServiceManager — local registry, null for unknown (ATL pattern). */
package android.os;
import java.util.concurrent.ConcurrentHashMap;
public final class ServiceManager {
    private static final ConcurrentHashMap<String, IBinder> sServices = new ConcurrentHashMap<>();
    private ServiceManager() {}
    public static IBinder getService(String name) { return sServices.get(name); }
    public static IBinder getServiceOrThrow(String name) throws ServiceNotFoundException {
        IBinder b = sServices.get(name);
        if (b == null) throw new ServiceNotFoundException(name);
        return b;
    }
    public static void addService(String name, IBinder service) { sServices.put(name, service); }
    public static IBinder checkService(String name) { return sServices.get(name); }
    public static String[] listServices() { return sServices.keySet().toArray(new String[0]); }
    public static IBinder waitForService(String name) { return sServices.get(name); }
    public static IBinder waitForDeclaredService(String name) { return sServices.get(name); }
    public static boolean isDeclared(String name) { return sServices.containsKey(name); }
    public static class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String name) { super("No service published for: " + name); }
    }
}
