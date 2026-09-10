package dev.artzig

import java.net.URL
import java.util.Enumeration

/**
 * Public platform view of the hosted framework's implementation loader.
 *
 * ART installs this as the system loader's parent before framework startup.
 * Original ApplicationLoaders and ClassLoaderFactory then create stock APK
 * loaders. Framework classes retain their defining loader and its private
 * dependencies; APKs obtain implementation libraries from their own code or
 * explicitly declared shared libraries.
 */
internal class HostedPlatformClassLoader(
    // Native ClassLoaderContext follows this field to validate the real framework
    // archive; the APK compiler receives the matching public API projection.
    private val implementation: ClassLoader,
) : ClassLoader(implementation.parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val type = if (exposes(name)) implementation.loadClass(name) else parent.loadClass(name)
        if (resolve) resolveClass(type)
        return type
    }

    override fun getResource(name: String): URL? =
        if (exposes(name.replace('/', '.'))) implementation.getResource(name) else parent.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (exposes(name.replace('/', '.'))) implementation.getResources(name) else parent.getResources(name)

    companion object {
        // tools/platform-api-jar.py applies the same namespace boundary without
        // changing class bytes. KotlinClassLoaderSmoke checks the produced view.
        private val prefixes = arrayOf(
            "java.", "javax.", "jdk.", "sun.", "android.", "com.android.",
            "com.google.android.collect.", "com.google.android.gles_jni.", "com.google.android.mms.",
            "org.ccil.cowan.tagsoup.",
            "dalvik.", "libcore.", "org.apache.harmony.", "org.apache.http.", "org.w3c.", "org.xml.",
            "org.json.", "dev.artzig.",
        )

        internal fun exposes(name: String): Boolean = prefixes.any(name::startsWith)
    }
}
