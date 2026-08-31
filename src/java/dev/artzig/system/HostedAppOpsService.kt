package dev.artzig.system

import android.app.AppOpsManager
import android.os.Binder
import android.util.Log
import com.android.internal.app.IAppOpsCallback
import com.android.internal.app.IAppOpsService

/**
 * The session's app-ops service over the real
 * `com.android.internal.app.IAppOpsService` surface, published as `"appops"`
 * (source note: `com.android.server.appop.AppOpsService`, reduced to a
 * per-(op, uid) mode table defaulting to MODE_ALLOWED — app-ops are not an
 * enforcement layer on this host, the uid/permission model is; the table
 * exists so the system can turn individual ops off and tests can observe it).
 * No persistence: modes are session-scoped.
 */
class HostedAppOpsService : IAppOpsService.Stub() {
    private val lock = Any()
    private val modes = HashMap<Long, Int>()
    private val watchers = ArrayList<Pair<Int, IAppOpsCallback>>()
    @Volatile var packageService: HostedPackageManagerService? = null

    private fun key(code: Int, uid: Int): Long = (code.toLong() shl 32) or (uid.toLong() and 0xffffffffL)

    private fun mode(code: Int, uid: Int): Int =
        synchronized(lock) { modes[key(code, uid)] } ?: AppOpsManager.MODE_ALLOWED

    private fun setModeInternal(code: Int, uid: Int, packageName: String?, mode: Int) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != android.os.Process.SYSTEM_UID && callingUid != android.os.Process.ROOT_UID) {
            throw SecurityException("uid " + callingUid + " may not set app-op modes")
        }
        val toNotify: List<IAppOpsCallback>
        synchronized(lock) {
            if (mode == AppOpsManager.MODE_ALLOWED) modes.remove(key(code, uid)) else modes[key(code, uid)] = mode
            toNotify = watchers.filter { it.first == code || it.first == AppOpsManager.OP_NONE }.map { it.second }
        }
        for (callback in toNotify) {
            try {
                callback.opChanged(code, uid, packageName, null)
            } catch (e: android.os.RemoteException) {
                synchronized(lock) { watchers.removeAll { it.second.asBinder() == callback.asBinder() } }
            }
        }
    }

    private fun addWatcher(op: Int, callback: IAppOpsCallback?) {
        if (callback == null) return
        synchronized(lock) { watchers.add(op to callback) }
    }

    override fun checkOperation(code: Int, uid: Int, packageName: String?): Int = mode(code, uid)
    override fun noteOperation(code: Int, uid: Int, packageName: String?, attributionTag: String?, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean): android.app.SyncNotedAppOp? =
        android.app.SyncNotedAppOp(mode(code, uid), code, attributionTag, packageName)
    override fun startOperation(clientId: android.os.IBinder?, code: Int, uid: Int, packageName: String?, attributionTag: String?, startIfModeDefault: Boolean, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, attributionFlags: Int, attributionChainId: Int): android.app.SyncNotedAppOp? =
        android.app.SyncNotedAppOp(mode(code, uid), code, attributionTag, packageName)
    override fun finishOperation(clientId: android.os.IBinder?, code: Int, uid: Int, packageName: String?, attributionTag: String?) {}
    override fun startWatchingMode(op: Int, packageName: String?, callback: com.android.internal.app.IAppOpsCallback?) =
        addWatcher(op, callback)
    override fun stopWatchingMode(callback: com.android.internal.app.IAppOpsCallback?) {
        if (callback == null) return
        synchronized(lock) { watchers.removeAll { it.second.asBinder() == callback.asBinder() } }
    }
    override fun permissionToOpCode(permission: String?): Int = 0
    override fun checkAudioOperation(code: Int, usage: Int, uid: Int, packageName: String?): Int = 0
    override fun shouldCollectNotes(opCode: Int): Boolean = false
    override fun setCameraAudioRestriction(mode: Int) {}
    override fun startWatchingModeWithFlags(op: Int, packageName: String?, flags: Int, callback: com.android.internal.app.IAppOpsCallback?) =
        addWatcher(op, callback)
    override fun noteProxyOperation(code: Int, attributionSource: android.content.AttributionSource?, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, skipProxyOperation: Boolean): android.app.SyncNotedAppOp? = null
    override fun startProxyOperation(clientId: android.os.IBinder?, code: Int, attributionSource: android.content.AttributionSource?, startIfModeDefault: Boolean, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, skipProxyOperation: Boolean, proxyAttributionFlags: Int, proxiedAttributionFlags: Int, attributionChainId: Int): android.app.SyncNotedAppOp? = null
    override fun finishProxyOperation(clientId: android.os.IBinder?, code: Int, attributionSource: android.content.AttributionSource?, skipProxyOperation: Boolean) {}
    override fun checkPackage(uid: Int, packageName: String?): Int {
        // Same rule as PackageManagerInternal.isSameApp: an unregistered uid
        // cannot be verified; only a contradicted claim is rejected.
        if (uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.ROOT_UID) return android.app.AppOpsManager.MODE_ALLOWED
        val packages = packageService?.getPackagesForUid(uid) ?: return android.app.AppOpsManager.MODE_ALLOWED
        return if (packages.isEmpty() || packages.contains(packageName)) {
            android.app.AppOpsManager.MODE_ALLOWED
        } else {
            android.app.AppOpsManager.MODE_ERRORED
        }
    }
    override fun collectRuntimeAppOpAccessMessage(): android.app.RuntimeAppOpAccessMessage? = null
    override fun reportRuntimeAppOpAccessMessageAndGetConfig(packageName: String?, appOp: android.app.SyncNotedAppOp?, message: String?): com.android.internal.app.MessageSamplingConfig? = null
    override fun getPackagesForOps(ops: IntArray?): MutableList<android.app.AppOpsManager.PackageOps?>? = null
    override fun getOpsForPackage(uid: Int, packageName: String?, ops: IntArray?): MutableList<android.app.AppOpsManager.PackageOps?>? = null
    override fun getHistoricalOps(uid: Int, packageName: String?, attributionTag: String?, ops: MutableList<String?>?, historyFlags: Int, filter: Int, beginTimeMillis: Long, endTimeMillis: Long, flags: Int, callback: android.os.RemoteCallback?) {}
    override fun getHistoricalOpsFromDiskRaw(uid: Int, packageName: String?, attributionTag: String?, ops: MutableList<String?>?, historyFlags: Int, filter: Int, beginTimeMillis: Long, endTimeMillis: Long, flags: Int, callback: android.os.RemoteCallback?) {}
    override fun offsetHistory(duration: Long) {}
    override fun setHistoryParameters(mode: Int, baseSnapshotInterval: Long, compressionStep: Int) {}
    override fun addHistoricalOps(ops: android.app.AppOpsManager.HistoricalOps?) {}
    override fun resetHistoryParameters() {}
    override fun resetPackageOpsNoHistory(packageName: String?) {}
    override fun clearHistory() {}
    override fun rebootHistory(offlineDurationMillis: Long) {}
    override fun getUidOps(uid: Int, ops: IntArray?): MutableList<android.app.AppOpsManager.PackageOps?>? = null
    override fun setUidMode(code: Int, uid: Int, mode: Int) = setModeInternal(code, uid, null, mode)
    override fun setMode(code: Int, uid: Int, packageName: String?, mode: Int) = setModeInternal(code, uid, packageName, mode)
    override fun resetAllModes(reqUserId: Int, reqPackageName: String?) {}
    override fun setAudioRestriction(code: Int, usage: Int, uid: Int, mode: Int, exceptionPackages: Array<String?>?) {}
    override fun setUserRestrictions(restrictions: android.os.Bundle?, token: android.os.IBinder?, userHandle: Int) {}
    override fun setUserRestriction(code: Int, restricted: Boolean, token: android.os.IBinder?, userHandle: Int, excludedPackageTags: android.os.PackageTagsList?) {}
    override fun removeUser(userHandle: Int) {}
    override fun startWatchingActive(ops: IntArray?, callback: com.android.internal.app.IAppOpsActiveCallback?) {}
    override fun stopWatchingActive(callback: com.android.internal.app.IAppOpsActiveCallback?) {}
    override fun isOperationActive(code: Int, uid: Int, packageName: String?): Boolean = false
    override fun isProxying(op: Int, proxyPackageName: String?, proxyAttributionTag: String?, proxiedUid: Int, proxiedPackageName: String?): Boolean = false
    override fun startWatchingStarted(ops: IntArray?, callback: com.android.internal.app.IAppOpsStartedCallback?) {}
    override fun stopWatchingStarted(callback: com.android.internal.app.IAppOpsStartedCallback?) {}
    override fun startWatchingNoted(ops: IntArray?, callback: com.android.internal.app.IAppOpsNotedCallback?) {}
    override fun stopWatchingNoted(callback: com.android.internal.app.IAppOpsNotedCallback?) {}
    override fun startWatchingAsyncNoted(packageName: String?, callback: com.android.internal.app.IAppOpsAsyncNotedCallback?) {}
    override fun stopWatchingAsyncNoted(packageName: String?, callback: com.android.internal.app.IAppOpsAsyncNotedCallback?) {}
    override fun extractAsyncOps(packageName: String?): MutableList<android.app.AsyncNotedAppOp?>? = null
    override fun checkOperationRaw(code: Int, uid: Int, packageName: String?, attributionTag: String?): Int = mode(code, uid)
    override fun reloadNonHistoricalState() {}
    override fun collectNoteOpCallsForValidation(stackTrace: String?, op: Int, packageName: String?, version: Long) {}
    override fun noteProxyOperationWithState(code: Int, attributionSourceStateState: android.content.AttributionSourceState?, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, skipProxyOperation: Boolean): android.app.SyncNotedAppOp? = null
    override fun startProxyOperationWithState(clientId: android.os.IBinder?, code: Int, attributionSourceStateState: android.content.AttributionSourceState?, startIfModeDefault: Boolean, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, skipProxyOperation: Boolean, proxyAttributionFlags: Int, proxiedAttributionFlags: Int, attributionChainId: Int): android.app.SyncNotedAppOp? = null
    override fun finishProxyOperationWithState(clientId: android.os.IBinder?, code: Int, attributionSourceStateState: android.content.AttributionSourceState?, skipProxyOperation: Boolean) {}
    override fun checkOperationRawForDevice(code: Int, uid: Int, packageName: String?, attributionTag: String?, virtualDeviceId: Int): Int = mode(code, uid)
    override fun checkOperationForDevice(code: Int, uid: Int, packageName: String?, attributionTag: String?, virtualDeviceId: Int): Int = mode(code, uid)
    override fun noteOperationForDevice(code: Int, uid: Int, packageName: String?, attributionTag: String?, virtualDeviceId: Int, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean): android.app.SyncNotedAppOp? =
        android.app.SyncNotedAppOp(mode(code, uid), code, attributionTag, packageName)
    override fun startOperationForDevice(clientId: android.os.IBinder?, code: Int, uid: Int, packageName: String?, attributionTag: String?, virtualDeviceId: Int, startIfModeDefault: Boolean, shouldCollectAsyncNotedOp: Boolean, message: String?, shouldCollectMessage: Boolean, attributionFlags: Int, attributionChainId: Int): android.app.SyncNotedAppOp? =
        android.app.SyncNotedAppOp(mode(code, uid), code, attributionTag, packageName)
    override fun finishOperationForDevice(clientId: android.os.IBinder?, code: Int, uid: Int, packageName: String?, attributionTag: String?, virtualDeviceId: Int) {}
    override fun getPackagesForOpsForDevice(ops: IntArray?, persistentDeviceId: String?): MutableList<android.app.AppOpsManager.PackageOps?>? = null
}
