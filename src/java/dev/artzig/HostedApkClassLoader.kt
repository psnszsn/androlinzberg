package dev.artzig

import dalvik.system.PathClassLoader

/**
 * Source: LoadedApk's separation between boot/framework classes and APK code.
 * The host framework jar also contains implementation libraries (Kotlin, Cronet,
 * protobuf), which are not public Android framework classes. An APK's own copies
 * of those libraries must link together, with fallback for absent classes.
 */
internal class HostedApkClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (FRAMEWORK_PREFIXES.any(name::startsWith)) {
            return super.loadClass(name, resolve)
        }

        synchronized(this) {
            var loaded = findLoadedClass(name)
            if (loaded == null) {
                loaded = try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, false)
                }
            }
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private companion object {
        val FRAMEWORK_PREFIXES = arrayOf(
            "java.", "javax.", "jdk.", "sun.", "android.", "com.android.",
            "dalvik.", "libcore.", "org.apache.harmony.", "org.apache.http.", "org.w3c.", "org.xml.",
            "org.json.", "dev.artzig.",
        )
    }
}
