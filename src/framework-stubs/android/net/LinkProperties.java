/* artzig stub: the host network has no Android-specific proxy configuration. */
package android.net;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

public class LinkProperties {
    public ProxyInfo getHttpProxy() { return null; }
    public String getInterfaceName() { return null; }
    public List<InetAddress> getDnsServers() { return Collections.emptyList(); }
    public List<Object> getLinkAddresses() { return Collections.emptyList(); }
    public List<Object> getRoutes() { return Collections.emptyList(); }
}
