/* artzig stub: the AOSP factory needs ZygoteInit/ClassLoaderFactory; only
 * SystemServiceManager.startServiceFromJar uses it, and no standalone system-server jars exist here. */
package com.android.internal.os;
import dalvik.system.PathClassLoader;
public final class SystemServerClassLoaderFactory {
    private SystemServerClassLoaderFactory() {}
    public static PathClassLoader getOrCreateClassLoader(String path, ClassLoader parent, boolean isTestOnly) {
        return new PathClassLoader(path, parent);
    }
}
