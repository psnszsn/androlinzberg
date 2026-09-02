package dev.artzig.system;

/** Host bootstrap that AOSP's IActivityManager has no counterpart for: the
 * session's package manager is the only APK parser (installPackage), and a
 * process announces the package it hosts before attachApplication because no
 * zygote spawned it for that package (bindProcessPackage). */
interface IHostedInstaller {
    String installPackage(String apkPath);
    void bindProcessPackage(String packageName, String processName);
}
