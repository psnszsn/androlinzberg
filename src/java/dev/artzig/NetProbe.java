package dev.artzig;

/** Probes DNS + TLS from inside the artzig runtime. */
public final class NetProbe {
    public static void main(String[] args) throws Exception {
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName("www.youtube.com");
            System.out.println("dns ok: " + a.getHostAddress());
        } catch (Throwable t) {
            System.out.println("dns FAIL: " + t);
        }
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://www.youtube.com/robots.txt").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            System.out.println("https ok: " + c.getResponseCode());
        } catch (Throwable t) {
            System.out.println("https FAIL: " + t);
        }
    }
}
