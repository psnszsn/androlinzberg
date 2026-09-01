/* artzig stub: native library extraction is the host launcher's job
 * (ApkHost.extractNativeLibraries); the vendored AndroidPackageUtils
 * signature only needs the Handle type. */
package com.android.internal.content;

import java.io.IOException;

public final class NativeLibraryHelper {
    private NativeLibraryHelper() {}
    public static final class Handle implements AutoCloseable {
        private Handle() {}
        public static Handle create(java.util.List<String> codePaths, boolean multiArch,
                boolean extractNativeLibs, boolean debuggable) throws IOException {
            throw new IOException("native library extraction is not hosted");
        }
        /** android 16 adds pageSizeAppCompatFlags. */
        public static Handle create(java.util.List<String> codePaths, boolean multiArch,
                boolean extractNativeLibs, boolean debuggable, boolean pageSizeAppCompat)
                throws IOException {
            throw new IOException("native library extraction is not hosted");
        }
        @Override public void close() {}
    }
}
