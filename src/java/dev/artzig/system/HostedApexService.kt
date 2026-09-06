package dev.artzig.system

import android.apex.ApexInfo
import android.apex.ApexSessionInfo
import android.apex.IApexService
import android.os.Binder
import android.os.IBinder
import android.os.Process

/**
 * ApexService::getActivePackages/getAllPackages/getSessions/getStagedSessionInfo
 * from system/apex/apexd/apexservice.cpp, for a host with no APEX mounts or sessions.
 * apexd's mounting and installation daemon is absent; those operations remain
 * structured unsupported calls. The stock ApexManager owns scanning and caching.
 */
class HostedApexService : IApexService.Stub(), IApexService by unsupported<IApexService>() {
    override fun asBinder(): IBinder = this

    private fun enforceCaller(operation: String, allowKeystore: Boolean = false) {
        val uid = Binder.getCallingUid()
        if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID &&
            !(allowKeystore && uid == Process.KEYSTORE_UID)) {
            throw SecurityException(if (allowKeystore)
                "Only root, keystore, and system_server are allowed to call $operation"
                else "Only root and system_server are allowed to call $operation")
        }
    }

    override fun getActivePackages(): Array<ApexInfo> {
        enforceCaller("getActivePackages", true)
        return emptyArray()
    }

    override fun getAllPackages(): Array<ApexInfo> {
        enforceCaller("getAllPackages")
        return emptyArray()
    }

    override fun getSessions(): Array<ApexSessionInfo> {
        enforceCaller("getSessions")
        return emptyArray()
    }

    override fun getStagedSessionInfo(sessionId: Int): ApexSessionInfo {
        enforceCaller("getStagedSessionInfo")
        return ApexSessionInfo().apply { isUnknown = true }
    }
}
