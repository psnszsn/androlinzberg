package dev.artzig.system

import android.content.pm.ApplicationInfo

/**
 * Session-wide compatibility policy on the stock `IPlatformCompat` Binder surface.
 *
 * Source note: AOSP `com.android.server.compat.PlatformCompat` normally evaluates XML change
 * configuration, package target SDKs, and developer overrides. That system-server stack is not
 * hosted here, so the desktop runtime uses the modern behavior for every change while retaining
 * stock client caching, Binder identity, and permission enforcement. Reporting is observationally
 * irrelevant without statsd; override/configuration operations remain explicitly unsupported.
 */
class HostedPlatformCompatService : UnsupportedPlatformCompat() {
    override fun reportChange(changeId: Long, appInfo: ApplicationInfo?) {}

    override fun reportChangeByPackageName(changeId: Long, packageName: String?, userId: Int) {}

    override fun reportChangeByUid(changeId: Long, uid: Int) {}

    override fun isChangeEnabled(changeId: Long, appInfo: ApplicationInfo?): Boolean = true

    override fun isChangeEnabledByPackageName(changeId: Long, packageName: String?, userId: Int): Boolean = true

    override fun isChangeEnabledByUid(changeId: Long, uid: Int): Boolean = true
}
