package dev.artzig

import dalvik.system.PathClassLoader

/**
 * Keeps Android/framework classes parent-first while allowing an APK's bundled
 * Kotlin runtime to take precedence over the host's compiler-pinned stdlib.
 */
internal class HostedApkClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!name.startsWith(KOTLIN_PACKAGE_PREFIX)) {
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
        const val KOTLIN_PACKAGE_PREFIX = "kotlin."
    }
}
