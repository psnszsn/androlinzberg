package dev.artzig

/** Probes DNS + TLS from inside the artzig runtime. */
public final class NetProbe {
    companion object {
        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            try {
                val a: java.net.InetAddress = java.net.InetAddress.getByName("www.youtube.com")
                System.out.println("dns ok: " + a.getHostAddress())
            } catch (t: Throwable) {
                System.out.println("dns FAIL: " + t)
            }
            try {
                val c: java.net.HttpURLConnection =
                    java.net.URL("https://www.youtube.com/robots.txt").openConnection()
                        as java.net.HttpURLConnection
                c.setConnectTimeout(5000)
                c.setReadTimeout(5000)
                System.out.println("https ok: " + c.getResponseCode())
            } catch (t: Throwable) {
                System.out.println("https FAIL: " + t)
            }
        }
    }
}
