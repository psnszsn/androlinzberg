package android.app

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * This process's IApplicationThread given to the activity manager.
 *
 * Source note: follows `ActivityThread.ApplicationThread` — every schedule*
 * builds the AOSP data record, posts to the main looper and runs the matching
 * `ActivityThread.handle*` (AOSP: `H.CREATE_SERVICE` -> `handleCreateService`,
 * etc.), so lifecycle ordering matches Android's main-thread execution.
 * Activity transactions go to the hosted ActivityThread's TransactionExecutor.
 * Everything else the activity manager could schedule is not hosted.
 */
internal class HostedApplicationThread : UnsupportedApplicationThread() {
    private val handler = Handler(Looper.getMainLooper())
    private val thread: ActivityThread get() = ActivityThread.currentActivityThread()

    // Pinned ApplicationThread.bindApplication: retain the process data and
    // dispatch binding on the main looper before subsequent transactions.
    override fun bindApplication(
        packageName: String?, info: android.content.pm.ApplicationInfo?,
        sdkSandboxClientAppVolumeUuid: String?, sdkSandboxClientAppPackage: String?,
        isSdkInSandbox: Boolean, providerList: android.content.pm.ProviderInfoList?,
        testName: android.content.ComponentName?, profilerInfo: ProfilerInfo?,
        testArguments: android.os.Bundle?, testWatcher: IInstrumentationWatcher?,
        uiAutomationConnection: IUiAutomationConnection?, debugMode: Int,
        enableBinderTracking: Boolean, trackAllocation: Boolean,
        restrictedBackupMode: Boolean, persistent: Boolean,
        config: android.content.res.Configuration?, compatInfo: android.content.res.CompatibilityInfo?,
        services: MutableMap<*, *>?, coreSettings: android.os.Bundle?, buildSerial: String?,
        autofillOptions: android.content.AutofillOptions?, contentCaptureOptions: android.content.ContentCaptureOptions?,
        disabledCompatChanges: LongArray?, loggableCompatChanges: LongArray?,
        serializedSystemFontMap: android.os.SharedMemory?, applicationSharedMemoryFd: java.io.FileDescriptor?,
        startRequestedElapsedTime: Long, startRequestedUptime: Long,
    ) {
        if (isSdkInSandbox || testName != null || profilerInfo != null || testWatcher != null ||
            uiAutomationConnection != null || debugMode != 0 || enableBinderTracking ||
            trackAllocation || restrictedBackupMode || serializedSystemFontMap != null ||
            applicationSharedMemoryFd != null) {
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("ActivityThread", "bindApplication",
                "sandbox, instrumentation, profiling and shared boot descriptors are not hosted")
        }
        if (services != null) {
            @Suppress("UNCHECKED_CAST")
            android.os.ServiceManager.initServiceCache(services as Map<String, IBinder>)
        }
        val data = ActivityThread.AppBindData()
        data.appInfo = info ?: throw NullPointerException("info")
        data.processName = packageName ?: throw NullPointerException("processName")
        data.providers = (providerList ?: throw NullPointerException("providerList")).list
        data.isSdkInSandbox = isSdkInSandbox
        data.config = config ?: throw NullPointerException("config")
        data.compatInfo = compatInfo ?: throw NullPointerException("compatInfo")
        data.coreSettings = coreSettings ?: throw NullPointerException("coreSettings")
        data.disabledCompatChanges = disabledCompatChanges
        data.loggableCompatChanges = loggableCompatChanges
        data.autofillOptions = autofillOptions
        data.contentCaptureOptions = contentCaptureOptions
        data.startRequestedElapsedTime = startRequestedElapsedTime
        data.startRequestedUptime = startRequestedUptime
        handler.post {
            thread.handleBindApplication(data)
            ActivityManager.getService().finishAttachApplication(0, System.nanoTime())
        }
    }

    // Pinned ActivityThread.ApplicationThread.scheduleRegisteredReceiver. The
    // stock dispatcher owns main-thread posting and PendingResult completion.
    override fun scheduleRegisteredReceiver(
        receiver: android.content.IIntentReceiver?, intent: Intent?, resultCode: Int,
        dataStr: String?, extras: android.os.Bundle?, ordered: Boolean, sticky: Boolean,
        assumeDelivered: Boolean, sendingUser: Int, processState: Int,
        sendingUid: Int, sendingPackage: String?,
    ) {
        thread.updateProcessState(processState, false)
        val target = receiver ?: throw NullPointerException("receiver")
        if (target is LoadedApk.ReceiverDispatcher.InnerReceiver) {
            target.performReceive(intent, resultCode, dataStr, extras, ordered, sticky,
                assumeDelivered, sendingUser, sendingUid, sendingPackage)
        } else {
            if (!assumeDelivered) android.util.Log.wtf(ActivityThread.TAG,
                "scheduleRegisteredReceiver() called for $target and $intent without mechanism to finish delivery")
            if (sendingUid != android.os.Process.INVALID_UID || sendingPackage != null) {
                android.util.Log.wtf(ActivityThread.TAG,
                    "scheduleRegisteredReceiver() called for $target and $intent from $sendingPackage (UID: $sendingUid) without mechanism to propagate the sender's identity")
            }
            target.performReceive(intent, resultCode, dataStr, extras, ordered, sticky, sendingUser)
        }
    }

    override fun scheduleCreateService(
        token: IBinder?,
        info: android.content.pm.ServiceInfo?,
        compatInfo: android.content.res.CompatibilityInfo?,
        processState: Int,
    ) {
        val data = ActivityThread.CreateServiceData()
        data.token = checkNotNull(token)
        data.info = checkNotNull(info)
        data.compatInfo = compatInfo
        handler.post { thread.handleCreateService(data) }
    }

    override fun scheduleBindService(token: IBinder?, intent: Intent?, rebind: Boolean, processState: Int, bindSeq: Long) {
        val data = ActivityThread.BindServiceData()
        data.token = checkNotNull(token)
        data.intent = checkNotNull(intent)
        data.rebind = rebind
        data.bindSeq = bindSeq
        handler.post { thread.handleBindService(data) }
    }

    override fun scheduleUnbindService(token: IBinder?, intent: Intent?) {
        val data = ActivityThread.BindServiceData()
        data.token = checkNotNull(token)
        data.intent = checkNotNull(intent)
        handler.post { thread.handleUnbindService(data) }
    }

    override fun scheduleServiceArgs(token: IBinder?, args: android.content.pm.ParceledListSlice<*>?) {
        val list = args?.list ?: return
        for (item in list) {
            val start = item as ServiceStartArgs
            val data = ActivityThread.ServiceArgsData()
            data.token = checkNotNull(token)
            data.taskRemoved = start.taskRemoved
            data.startId = start.startId
            data.flags = start.flags
            data.args = start.args
            handler.post { thread.handleServiceArgs(data) }
        }
    }

    override fun scheduleStopService(token: IBinder?) {
        val t = checkNotNull(token)
        handler.post { thread.handleStopService(t) }
    }

    override fun scheduleExit() {
        handler.post {
            thread.`shutdownServices$artzig`()
            Runtime.getRuntime().exit(0)
        }
    }

    override fun scheduleTransaction(transaction: android.app.servertransaction.ClientTransaction?) {
        ActivityThread.currentActivityThread().scheduleTransaction(checkNotNull(transaction))
    }

    override fun schedulePing(pong: android.os.RemoteCallback?) { pong?.sendResult(null) }
    override fun scheduleLowMemory() {}
    override fun scheduleTrimMemory(level: Int) {}
    override fun processInBackground() {}
    override fun updateTimeZone() {}
    override fun setProcessState(state: Int) {}
    override fun scheduleTimeoutService(token: IBinder?, startId: Int) {}
    override fun scheduleTimeoutServiceForType(token: IBinder?, startId: Int, fgsType: Int) {}
}
