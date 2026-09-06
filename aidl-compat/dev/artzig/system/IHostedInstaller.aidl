package dev.artzig.system;

import android.content.res.Configuration;

/** Host bootstrap that AOSP's IActivityManager has no counterpart for: the
 * session's package manager is the only APK parser (installPackage), and a
 * process announces the package it hosts before attachApplication because no
 * zygote spawned it for that package (bindProcessPackage). */
interface IHostedInstaller {
    /** nativeLibraryDir: where the installing process extracted the APK's
     * lib/x86_64 (NativeLibraryHelper role; the launcher picks the directory
     * bionic sees), or null for a resources-only package. */
    String installPackage(String apkPath, @nullable String nativeLibraryDir);
    /** Initial resource configuration of this process's hosted desktop window.
     * It is a process override, never a mutation of the device configuration. */
    void bindProcessPackage(String packageName, String processName,
            in Configuration launchConfiguration);
}
