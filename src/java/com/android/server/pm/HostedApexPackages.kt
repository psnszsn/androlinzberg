package com.android.server.pm

/** PackageManagerService's APEX scan completion for the host's empty APEX inventory. */
object HostedApexPackages {
    fun initialize() {
        val manager = ApexManager.getInstance()
        check(manager.allApexInfos.isEmpty())
        check(manager.activeApexInfos.isEmpty())
        check(manager.sessions.size() == 0)
        check(manager.getStagedSessionInfo(0) == null)
        manager.notifyScanResult(emptyList())
    }
}
