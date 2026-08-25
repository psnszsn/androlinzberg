/* artzig stub. */
package android.net;

public class ProxyInfo {
    private final String host;
    private final int port;

    public ProxyInfo(String host, int port) { this.host = host; this.port = port; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getExclusionListAsString() { return ""; }
    public boolean isValid() { return host != null && !host.isEmpty() && port > 0; }
}
