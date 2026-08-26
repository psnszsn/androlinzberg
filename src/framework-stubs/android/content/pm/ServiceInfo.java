/* artzig stub. */
package android.content.pm;
public class ServiceInfo extends ComponentInfo {
    public String permission;
    public int flags;
    public int foregroundServiceType;
    public ServiceInfo() {}
    public ServiceInfo(ServiceInfo orig) {
        super(orig);
        permission = orig.permission;
        flags = orig.flags;
        foregroundServiceType = orig.foregroundServiceType;
    }
    public void dump(android.util.Printer pw, String prefix) {}
}
