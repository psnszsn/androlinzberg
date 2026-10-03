package dev.artzig.system;

import android.content.res.Configuration;

/** Host bootstrap that AOSP's IActivityManager has no counterpart for: the
 * session's package manager is the only APK parser (installPackage), and a
 * process announces the package it hosts before attachApplication because no
 * zygote spawned it for that package (bindProcessPackage). */
interface IHostedInstaller {
    /** `pm install` of a host APK through a stock PackageInstaller session, for
     * the system, shell and host session clients (not installed apps).
     * nativeLibraryDir is no longer consulted: the session extracts libraries. */
    String installPackage(String apkPath, @nullable String nativeLibraryDir);
    /** `am start` of a package's launcher activity in a new task, for the same
     * callers: the system server starts it, as the session owner does. */
    void launchPackage(String packageName);
    /** Initial resource configuration of this process's hosted desktop window.
     * It is a process override, never a mutation of the device configuration. */
    void bindProcessPackage(String packageName, String processName,
            in Configuration launchConfiguration);
}
