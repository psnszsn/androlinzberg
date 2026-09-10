package fixture

/** Resolves a dex type reference, not only a reflective ClassLoader request. */
class PlatformReference {
    companion object {
        @JvmStatic fun uriClass(): Class<*> = android.net.Uri::class.java
    }
}
