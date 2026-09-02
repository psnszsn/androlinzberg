package dev.artzig.system

/** Every IActivityManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedActivityManager : android.app.IActivityManager.Stub() {
    override fun openContentUri(uriString: String?): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IActivityManager.openContentUri is not hosted")
    override fun registerUidObserver(observer: android.app.IUidObserver?, which: Int, cutpoint: Int, callingPackage: String?) { throw UnsupportedOperationException("IActivityManager.registerUidObserver is not hosted") }
    override fun unregisterUidObserver(observer: android.app.IUidObserver?) { throw UnsupportedOperationException("IActivityManager.unregisterUidObserver is not hosted") }
    override fun registerUidObserverForUids(observer: android.app.IUidObserver?, which: Int, cutpoint: Int, callingPackage: String?, uids: IntArray?): android.os.IBinder? = throw UnsupportedOperationException("IActivityManager.registerUidObserverForUids is not hosted")
    override fun addUidToObserver(observerToken: android.os.IBinder?, callingPackage: String?, uid: Int) { throw UnsupportedOperationException("IActivityManager.addUidToObserver is not hosted") }
    override fun removeUidFromObserver(observerToken: android.os.IBinder?, callingPackage: String?, uid: Int) { throw UnsupportedOperationException("IActivityManager.removeUidFromObserver is not hosted") }
    override fun isUidActive(uid: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("IActivityManager.isUidActive is not hosted")
    override fun getUidProcessState(uid: Int, callingPackage: String?): Int = throw UnsupportedOperationException("IActivityManager.getUidProcessState is not hosted")
    override fun checkPermission(permission: String?, pid: Int, uid: Int): Int = throw UnsupportedOperationException("IActivityManager.checkPermission is not hosted")
    override fun logFgsApiBegin(apiType: Int, appUid: Int, appPid: Int) { throw UnsupportedOperationException("IActivityManager.logFgsApiBegin is not hosted") }
    override fun logFgsApiEnd(apiType: Int, appUid: Int, appPid: Int) { throw UnsupportedOperationException("IActivityManager.logFgsApiEnd is not hosted") }
    override fun logFgsApiStateChanged(apiType: Int, state: Int, appUid: Int, appPid: Int) { throw UnsupportedOperationException("IActivityManager.logFgsApiStateChanged is not hosted") }
    override fun handleApplicationCrash(app: android.os.IBinder?, crashInfo: android.app.ApplicationErrorReport.ParcelableCrashInfo?) { throw UnsupportedOperationException("IActivityManager.handleApplicationCrash is not hosted") }
    override fun startActivity(caller: android.app.IApplicationThread?, callingPackage: String?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.os.IBinder?, resultWho: String?, requestCode: Int, flags: Int, profilerInfo: android.app.ProfilerInfo?, options: android.os.Bundle?): Int = throw UnsupportedOperationException("IActivityManager.startActivity is not hosted")
    override fun startActivityWithFeature(caller: android.app.IApplicationThread?, callingPackage: String?, callingFeatureId: String?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.os.IBinder?, resultWho: String?, requestCode: Int, flags: Int, profilerInfo: android.app.ProfilerInfo?, options: android.os.Bundle?): Int = throw UnsupportedOperationException("IActivityManager.startActivityWithFeature is not hosted")
    override fun unhandledBack() { throw UnsupportedOperationException("IActivityManager.unhandledBack is not hosted") }
    override fun finishActivity(token: android.os.IBinder?, code: Int, data: android.content.Intent?, finishTask: Int): Boolean = throw UnsupportedOperationException("IActivityManager.finishActivity is not hosted")
    override fun registerReceiver(caller: android.app.IApplicationThread?, callerPackage: String?, receiver: android.content.IIntentReceiver?, filter: android.content.IntentFilter?, requiredPermission: String?, userId: Int, flags: Int): android.content.Intent? = throw UnsupportedOperationException("IActivityManager.registerReceiver is not hosted")
    override fun registerReceiverWithFeature(caller: android.app.IApplicationThread?, callerPackage: String?, callingFeatureId: String?, receiverId: String?, receiver: android.content.IIntentReceiver?, filter: android.content.IntentFilter?, requiredPermission: String?, userId: Int, flags: Int): android.content.Intent? = throw UnsupportedOperationException("IActivityManager.registerReceiverWithFeature is not hosted")
    override fun unregisterReceiver(receiver: android.content.IIntentReceiver?) { throw UnsupportedOperationException("IActivityManager.unregisterReceiver is not hosted") }
    override fun getRegisteredIntentFilters(receiver: android.content.IIntentReceiver?): MutableList<android.content.IntentFilter?>? = throw UnsupportedOperationException("IActivityManager.getRegisteredIntentFilters is not hosted")
    override fun broadcastIntent(caller: android.app.IApplicationThread?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.content.IIntentReceiver?, resultCode: Int, resultData: String?, map: android.os.Bundle?, requiredPermissions: Array<String?>?, appOp: Int, options: android.os.Bundle?, serialized: Boolean, sticky: Boolean, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.broadcastIntent is not hosted")
    override fun broadcastIntentWithFeature(caller: android.app.IApplicationThread?, callingFeatureId: String?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.content.IIntentReceiver?, resultCode: Int, resultData: String?, map: android.os.Bundle?, requiredPermissions: Array<String?>?, excludePermissions: Array<String?>?, excludePackages: Array<String?>?, appOp: Int, options: android.os.Bundle?, serialized: Boolean, sticky: Boolean, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.broadcastIntentWithFeature is not hosted")
    override fun unbroadcastIntent(caller: android.app.IApplicationThread?, intent: android.content.Intent?, userId: Int) { throw UnsupportedOperationException("IActivityManager.unbroadcastIntent is not hosted") }
    override fun finishReceiver(who: android.os.IBinder?, resultCode: Int, resultData: String?, map: android.os.Bundle?, abortBroadcast: Boolean, flags: Int) { throw UnsupportedOperationException("IActivityManager.finishReceiver is not hosted") }
    override fun attachApplication(app: android.app.IApplicationThread?, startSeq: Long) { throw UnsupportedOperationException("IActivityManager.attachApplication is not hosted") }
    override fun finishAttachApplication(startSeq: Long, timestampApplicationOnCreateNs: Long) { throw UnsupportedOperationException("IActivityManager.finishAttachApplication is not hosted") }
    override fun getTasks(maxNum: Int): MutableList<android.app.ActivityManager.RunningTaskInfo?>? = throw UnsupportedOperationException("IActivityManager.getTasks is not hosted")
    override fun moveTaskToFront(caller: android.app.IApplicationThread?, callingPackage: String?, task: Int, flags: Int, options: android.os.Bundle?) { throw UnsupportedOperationException("IActivityManager.moveTaskToFront is not hosted") }
    override fun getTaskForActivity(token: android.os.IBinder?, onlyRoot: Boolean): Int = throw UnsupportedOperationException("IActivityManager.getTaskForActivity is not hosted")
    override fun getContentProvider(caller: android.app.IApplicationThread?, callingPackage: String?, name: String?, userId: Int, stable: Boolean): android.app.ContentProviderHolder? = throw UnsupportedOperationException("IActivityManager.getContentProvider is not hosted")
    override fun publishContentProviders(caller: android.app.IApplicationThread?, providers: MutableList<android.app.ContentProviderHolder?>?) { throw UnsupportedOperationException("IActivityManager.publishContentProviders is not hosted") }
    override fun refContentProvider(connection: android.os.IBinder?, stableDelta: Int, unstableDelta: Int): Boolean = throw UnsupportedOperationException("IActivityManager.refContentProvider is not hosted")
    override fun getRunningServiceControlPanel(service: android.content.ComponentName?): android.app.PendingIntent? = throw UnsupportedOperationException("IActivityManager.getRunningServiceControlPanel is not hosted")
    override fun startService(caller: android.app.IApplicationThread?, service: android.content.Intent?, resolvedType: String?, requireForeground: Boolean, callingPackage: String?, callingFeatureId: String?, userId: Int): android.content.ComponentName? = throw UnsupportedOperationException("IActivityManager.startService is not hosted")
    override fun stopService(caller: android.app.IApplicationThread?, service: android.content.Intent?, resolvedType: String?, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.stopService is not hosted")
    override fun bindService(caller: android.app.IApplicationThread?, token: android.os.IBinder?, service: android.content.Intent?, resolvedType: String?, connection: android.app.IServiceConnection?, flags: Long, callingPackage: String?, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.bindService is not hosted")
    override fun bindServiceInstance(caller: android.app.IApplicationThread?, token: android.os.IBinder?, service: android.content.Intent?, resolvedType: String?, connection: android.app.IServiceConnection?, flags: Long, instanceName: String?, callingPackage: String?, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.bindServiceInstance is not hosted")
    override fun updateServiceGroup(connection: android.app.IServiceConnection?, group: Int, importance: Int) { throw UnsupportedOperationException("IActivityManager.updateServiceGroup is not hosted") }
    override fun unbindService(connection: android.app.IServiceConnection?): Boolean = throw UnsupportedOperationException("IActivityManager.unbindService is not hosted")
    override fun publishService(token: android.os.IBinder?, intent: android.content.Intent?, service: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.publishService is not hosted") }
    override fun setDebugApp(packageName: String?, waitForDebugger: Boolean, persistent: Boolean) { throw UnsupportedOperationException("IActivityManager.setDebugApp is not hosted") }
    override fun setAgentApp(packageName: String?, agent: String?) { throw UnsupportedOperationException("IActivityManager.setAgentApp is not hosted") }
    override fun setAlwaysFinish(enabled: Boolean) { throw UnsupportedOperationException("IActivityManager.setAlwaysFinish is not hosted") }
    override fun startInstrumentation(className: android.content.ComponentName?, profileFile: String?, flags: Int, arguments: android.os.Bundle?, watcher: android.app.IInstrumentationWatcher?, connection: android.app.IUiAutomationConnection?, userId: Int, abiOverride: String?): Boolean = throw UnsupportedOperationException("IActivityManager.startInstrumentation is not hosted")
    override fun addInstrumentationResults(target: android.app.IApplicationThread?, results: android.os.Bundle?) { throw UnsupportedOperationException("IActivityManager.addInstrumentationResults is not hosted") }
    override fun finishInstrumentation(target: android.app.IApplicationThread?, resultCode: Int, results: android.os.Bundle?) { throw UnsupportedOperationException("IActivityManager.finishInstrumentation is not hosted") }
    override fun getConfiguration(): android.content.res.Configuration? = throw UnsupportedOperationException("IActivityManager.getConfiguration is not hosted")
    override fun updateConfiguration(values: android.content.res.Configuration?): Boolean = throw UnsupportedOperationException("IActivityManager.updateConfiguration is not hosted")
    override fun updateMccMncConfiguration(mcc: String?, mnc: String?): Boolean = throw UnsupportedOperationException("IActivityManager.updateMccMncConfiguration is not hosted")
    override fun stopServiceToken(className: android.content.ComponentName?, token: android.os.IBinder?, startId: Int): Boolean = throw UnsupportedOperationException("IActivityManager.stopServiceToken is not hosted")
    override fun setProcessLimit(max: Int) { throw UnsupportedOperationException("IActivityManager.setProcessLimit is not hosted") }
    override fun getProcessLimit(): Int = throw UnsupportedOperationException("IActivityManager.getProcessLimit is not hosted")
    override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: android.os.IBinder?): Int = throw UnsupportedOperationException("IActivityManager.checkUriPermission is not hosted")
    override fun checkContentUriPermissionFull(uri: android.net.Uri?, pid: Int, uid: Int, mode: Int, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.checkContentUriPermissionFull is not hosted")
    override fun checkUriPermissions(uris: MutableList<android.net.Uri?>?, pid: Int, uid: Int, mode: Int, userId: Int, callerToken: android.os.IBinder?): IntArray? = throw UnsupportedOperationException("IActivityManager.checkUriPermissions is not hosted")
    override fun grantUriPermission(caller: android.app.IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) { throw UnsupportedOperationException("IActivityManager.grantUriPermission is not hosted") }
    override fun revokeUriPermission(caller: android.app.IApplicationThread?, targetPkg: String?, uri: android.net.Uri?, mode: Int, userId: Int) { throw UnsupportedOperationException("IActivityManager.revokeUriPermission is not hosted") }
    override fun setActivityController(watcher: android.app.IActivityController?, imAMonkey: Boolean) { throw UnsupportedOperationException("IActivityManager.setActivityController is not hosted") }
    override fun showWaitingForDebugger(who: android.app.IApplicationThread?, waiting: Boolean) { throw UnsupportedOperationException("IActivityManager.showWaitingForDebugger is not hosted") }
    override fun signalPersistentProcesses(signal: Int) { throw UnsupportedOperationException("IActivityManager.signalPersistentProcesses is not hosted") }
    override fun getRecentTasks(maxNum: Int, flags: Int, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IActivityManager.getRecentTasks is not hosted")
    override fun serviceDoneExecuting(token: android.os.IBinder?, type: Int, startId: Int, res: Int, intent: android.content.Intent?) { throw UnsupportedOperationException("IActivityManager.serviceDoneExecuting is not hosted") }
    override fun getIntentSender(type: Int, packageName: String?, token: android.os.IBinder?, resultWho: String?, requestCode: Int, intents: Array<android.content.Intent?>?, resolvedTypes: Array<String?>?, flags: Int, options: android.os.Bundle?, userId: Int): android.content.IIntentSender? = throw UnsupportedOperationException("IActivityManager.getIntentSender is not hosted")
    override fun getIntentSenderWithFeature(type: Int, packageName: String?, featureId: String?, token: android.os.IBinder?, resultWho: String?, requestCode: Int, intents: Array<android.content.Intent?>?, resolvedTypes: Array<String?>?, flags: Int, options: android.os.Bundle?, userId: Int): android.content.IIntentSender? = throw UnsupportedOperationException("IActivityManager.getIntentSenderWithFeature is not hosted")
    override fun cancelIntentSender(sender: android.content.IIntentSender?) { throw UnsupportedOperationException("IActivityManager.cancelIntentSender is not hosted") }
    override fun getInfoForIntentSender(sender: android.content.IIntentSender?): android.app.ActivityManager.PendingIntentInfo? = throw UnsupportedOperationException("IActivityManager.getInfoForIntentSender is not hosted")
    override fun registerIntentSenderCancelListenerEx(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?): Boolean = throw UnsupportedOperationException("IActivityManager.registerIntentSenderCancelListenerEx is not hosted")
    override fun unregisterIntentSenderCancelListener(sender: android.content.IIntentSender?, receiver: com.android.internal.os.IResultReceiver?) { throw UnsupportedOperationException("IActivityManager.unregisterIntentSenderCancelListener is not hosted") }
    override fun enterSafeMode() { throw UnsupportedOperationException("IActivityManager.enterSafeMode is not hosted") }
    override fun noteWakeupAlarm(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, sourcePkg: String?, tag: String?) { throw UnsupportedOperationException("IActivityManager.noteWakeupAlarm is not hosted") }
    override fun removeContentProvider(connection: android.os.IBinder?, stable: Boolean) { throw UnsupportedOperationException("IActivityManager.removeContentProvider is not hosted") }
    override fun setRequestedOrientation(token: android.os.IBinder?, requestedOrientation: Int) { throw UnsupportedOperationException("IActivityManager.setRequestedOrientation is not hosted") }
    override fun unbindFinished(token: android.os.IBinder?, service: android.content.Intent?) { throw UnsupportedOperationException("IActivityManager.unbindFinished is not hosted") }
    override fun setProcessImportant(token: android.os.IBinder?, pid: Int, isForeground: Boolean, reason: String?) { throw UnsupportedOperationException("IActivityManager.setProcessImportant is not hosted") }
    override fun setServiceForeground(className: android.content.ComponentName?, token: android.os.IBinder?, id: Int, notification: android.app.Notification?, flags: Int, foregroundServiceType: Int) { throw UnsupportedOperationException("IActivityManager.setServiceForeground is not hosted") }
    override fun getForegroundServiceType(className: android.content.ComponentName?, token: android.os.IBinder?): Int = throw UnsupportedOperationException("IActivityManager.getForegroundServiceType is not hosted")
    override fun moveActivityTaskToBack(token: android.os.IBinder?, nonRoot: Boolean): Boolean = throw UnsupportedOperationException("IActivityManager.moveActivityTaskToBack is not hosted")
    override fun getMemoryInfo(outInfo: android.app.ActivityManager.MemoryInfo?) { throw UnsupportedOperationException("IActivityManager.getMemoryInfo is not hosted") }
    override fun getProcessesInErrorState(): MutableList<android.app.ActivityManager.ProcessErrorStateInfo?>? = throw UnsupportedOperationException("IActivityManager.getProcessesInErrorState is not hosted")
    override fun clearApplicationUserData(packageName: String?, keepState: Boolean, observer: android.content.pm.IPackageDataObserver?, userId: Int): Boolean = throw UnsupportedOperationException("IActivityManager.clearApplicationUserData is not hosted")
    override fun stopAppForUser(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.stopAppForUser is not hosted") }
    override fun registerForegroundServiceObserver(callback: android.app.IForegroundServiceObserver?): Boolean = throw UnsupportedOperationException("IActivityManager.registerForegroundServiceObserver is not hosted")
    override fun forceStopPackage(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.forceStopPackage is not hosted") }
    override fun forceStopPackageEvenWhenStopping(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.forceStopPackageEvenWhenStopping is not hosted") }
    override fun killPids(pids: IntArray?, reason: String?, secure: Boolean): Boolean = throw UnsupportedOperationException("IActivityManager.killPids is not hosted")
    override fun getServices(maxNum: Int, flags: Int): MutableList<android.app.ActivityManager.RunningServiceInfo?>? = throw UnsupportedOperationException("IActivityManager.getServices is not hosted")
    override fun getRunningAppProcesses(): MutableList<android.app.ActivityManager.RunningAppProcessInfo?>? = throw UnsupportedOperationException("IActivityManager.getRunningAppProcesses is not hosted")
    override fun peekService(service: android.content.Intent?, resolvedType: String?, callingPackage: String?): android.os.IBinder? = throw UnsupportedOperationException("IActivityManager.peekService is not hosted")
    override fun profileControl(process: String?, userId: Int, start: Boolean, profilerInfo: android.app.ProfilerInfo?, profileType: Int): Boolean = throw UnsupportedOperationException("IActivityManager.profileControl is not hosted")
    override fun shutdown(timeout: Int): Boolean = throw UnsupportedOperationException("IActivityManager.shutdown is not hosted")
    override fun stopAppSwitches() { throw UnsupportedOperationException("IActivityManager.stopAppSwitches is not hosted") }
    override fun resumeAppSwitches() { throw UnsupportedOperationException("IActivityManager.resumeAppSwitches is not hosted") }
    override fun bindBackupAgent(packageName: String?, backupRestoreMode: Int, targetUserId: Int, backupDestination: Int, useRestrictedMode: Boolean): Boolean = throw UnsupportedOperationException("IActivityManager.bindBackupAgent is not hosted")
    override fun backupAgentCreated(packageName: String?, agent: android.os.IBinder?, userId: Int) { throw UnsupportedOperationException("IActivityManager.backupAgentCreated is not hosted") }
    override fun unbindBackupAgent(appInfo: android.content.pm.ApplicationInfo?) { throw UnsupportedOperationException("IActivityManager.unbindBackupAgent is not hosted") }
    override fun handleIncomingUser(callingPid: Int, callingUid: Int, userId: Int, allowAll: Boolean, requireFull: Boolean, name: String?, callerPackage: String?): Int = throw UnsupportedOperationException("IActivityManager.handleIncomingUser is not hosted")
    override fun addPackageDependency(packageName: String?) { throw UnsupportedOperationException("IActivityManager.addPackageDependency is not hosted") }
    override fun killApplication(pkg: String?, appId: Int, userId: Int, reason: String?, exitInfoReason: Int) { throw UnsupportedOperationException("IActivityManager.killApplication is not hosted") }
    override fun closeSystemDialogs(reason: String?) { throw UnsupportedOperationException("IActivityManager.closeSystemDialogs is not hosted") }
    override fun getProcessMemoryInfo(pids: IntArray?): Array<android.os.Debug.MemoryInfo?>? = throw UnsupportedOperationException("IActivityManager.getProcessMemoryInfo is not hosted")
    override fun killApplicationProcess(processName: String?, uid: Int) { throw UnsupportedOperationException("IActivityManager.killApplicationProcess is not hosted") }
    override fun handleApplicationWtf(app: android.os.IBinder?, tag: String?, system: Boolean, crashInfo: android.app.ApplicationErrorReport.ParcelableCrashInfo?, immediateCallerPid: Int): Boolean = throw UnsupportedOperationException("IActivityManager.handleApplicationWtf is not hosted")
    override fun killBackgroundProcesses(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.killBackgroundProcesses is not hosted") }
    override fun isUserAMonkey(): Boolean = throw UnsupportedOperationException("IActivityManager.isUserAMonkey is not hosted")
    override fun getRunningExternalApplications(): MutableList<android.content.pm.ApplicationInfo?>? = throw UnsupportedOperationException("IActivityManager.getRunningExternalApplications is not hosted")
    override fun finishHeavyWeightApp() { throw UnsupportedOperationException("IActivityManager.finishHeavyWeightApp is not hosted") }
    override fun handleApplicationStrictModeViolation(app: android.os.IBinder?, penaltyMask: Int, crashInfo: android.os.StrictMode.ViolationInfo?) { throw UnsupportedOperationException("IActivityManager.handleApplicationStrictModeViolation is not hosted") }
    override fun registerStrictModeCallback(binder: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.registerStrictModeCallback is not hosted") }
    override fun isTopActivityImmersive(): Boolean = throw UnsupportedOperationException("IActivityManager.isTopActivityImmersive is not hosted")
    override fun crashApplicationWithType(uid: Int, initialPid: Int, packageName: String?, userId: Int, message: String?, force: Boolean, exceptionTypeId: Int) { throw UnsupportedOperationException("IActivityManager.crashApplicationWithType is not hosted") }
    override fun crashApplicationWithTypeWithExtras(uid: Int, initialPid: Int, packageName: String?, userId: Int, message: String?, force: Boolean, exceptionTypeId: Int, extras: android.os.Bundle?) { throw UnsupportedOperationException("IActivityManager.crashApplicationWithTypeWithExtras is not hosted") }
    override fun getMimeTypeFilterAsync(uri: android.net.Uri?, userId: Int, resultCallback: android.os.RemoteCallback?) { throw UnsupportedOperationException("IActivityManager.getMimeTypeFilterAsync is not hosted") }
    override fun dumpHeap(process: String?, userId: Int, managed: Boolean, mallocInfo: Boolean, runGc: Boolean, dumpBitmaps: String?, path: String?, fd: android.os.ParcelFileDescriptor?, finishCallback: android.os.RemoteCallback?): Boolean = throw UnsupportedOperationException("IActivityManager.dumpHeap is not hosted")
    override fun isUserRunning(userid: Int, flags: Int): Boolean = throw UnsupportedOperationException("IActivityManager.isUserRunning is not hosted")
    override fun setPackageScreenCompatMode(packageName: String?, mode: Int) { throw UnsupportedOperationException("IActivityManager.setPackageScreenCompatMode is not hosted") }
    override fun switchUser(userid: Int): Boolean = throw UnsupportedOperationException("IActivityManager.switchUser is not hosted")
    override fun getSwitchingFromUserMessage(userId: Int): String? = throw UnsupportedOperationException("IActivityManager.getSwitchingFromUserMessage is not hosted")
    override fun getSwitchingToUserMessage(userId: Int): String? = throw UnsupportedOperationException("IActivityManager.getSwitchingToUserMessage is not hosted")
    override fun setStopUserOnSwitch(value: Int) { throw UnsupportedOperationException("IActivityManager.setStopUserOnSwitch is not hosted") }
    override fun removeTask(taskId: Int): Boolean = throw UnsupportedOperationException("IActivityManager.removeTask is not hosted")
    override fun registerProcessObserver(observer: android.app.IProcessObserver?) { throw UnsupportedOperationException("IActivityManager.registerProcessObserver is not hosted") }
    override fun unregisterProcessObserver(observer: android.app.IProcessObserver?) { throw UnsupportedOperationException("IActivityManager.unregisterProcessObserver is not hosted") }
    override fun isIntentSenderTargetedToPackage(sender: android.content.IIntentSender?): Boolean = throw UnsupportedOperationException("IActivityManager.isIntentSenderTargetedToPackage is not hosted")
    override fun updatePersistentConfiguration(values: android.content.res.Configuration?) { throw UnsupportedOperationException("IActivityManager.updatePersistentConfiguration is not hosted") }
    override fun updatePersistentConfigurationWithAttribution(values: android.content.res.Configuration?, callingPackageName: String?, callingAttributionTag: String?) { throw UnsupportedOperationException("IActivityManager.updatePersistentConfigurationWithAttribution is not hosted") }
    override fun getProcessPss(pids: IntArray?): LongArray? = throw UnsupportedOperationException("IActivityManager.getProcessPss is not hosted")
    override fun showBootMessage(msg: CharSequence?, always: Boolean) { throw UnsupportedOperationException("IActivityManager.showBootMessage is not hosted") }
    override fun killAllBackgroundProcesses() { throw UnsupportedOperationException("IActivityManager.killAllBackgroundProcesses is not hosted") }
    override fun getContentProviderExternal(name: String?, userId: Int, token: android.os.IBinder?, tag: String?): android.app.ContentProviderHolder? = throw UnsupportedOperationException("IActivityManager.getContentProviderExternal is not hosted")
    override fun removeContentProviderExternal(name: String?, token: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.removeContentProviderExternal is not hosted") }
    override fun removeContentProviderExternalAsUser(name: String?, token: android.os.IBinder?, userId: Int) { throw UnsupportedOperationException("IActivityManager.removeContentProviderExternalAsUser is not hosted") }
    override fun getMyMemoryState(outInfo: android.app.ActivityManager.RunningAppProcessInfo?) { throw UnsupportedOperationException("IActivityManager.getMyMemoryState is not hosted") }
    override fun killProcessesBelowForeground(reason: String?): Boolean = throw UnsupportedOperationException("IActivityManager.killProcessesBelowForeground is not hosted")
    override fun getCurrentUser(): android.content.pm.UserInfo? = throw UnsupportedOperationException("IActivityManager.getCurrentUser is not hosted")
    override fun getCurrentUserId(): Int = throw UnsupportedOperationException("IActivityManager.getCurrentUserId is not hosted")
    override fun getLaunchedFromUid(activityToken: android.os.IBinder?): Int = throw UnsupportedOperationException("IActivityManager.getLaunchedFromUid is not hosted")
    override fun unstableProviderDied(connection: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.unstableProviderDied is not hosted") }
    override fun isIntentSenderAnActivity(sender: android.content.IIntentSender?): Boolean = throw UnsupportedOperationException("IActivityManager.isIntentSenderAnActivity is not hosted")
    override fun startActivityAsUser(caller: android.app.IApplicationThread?, callingPackage: String?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.os.IBinder?, resultWho: String?, requestCode: Int, flags: Int, profilerInfo: android.app.ProfilerInfo?, options: android.os.Bundle?, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.startActivityAsUser is not hosted")
    override fun startActivityAsUserWithFeature(caller: android.app.IApplicationThread?, callingPackage: String?, callingFeatureId: String?, intent: android.content.Intent?, resolvedType: String?, resultTo: android.os.IBinder?, resultWho: String?, requestCode: Int, flags: Int, profilerInfo: android.app.ProfilerInfo?, options: android.os.Bundle?, userId: Int): Int = throw UnsupportedOperationException("IActivityManager.startActivityAsUserWithFeature is not hosted")
    override fun stopUser(userid: Int, stopProfileRegardlessOfParent: Boolean, callback: android.app.IStopUserCallback?): Int = throw UnsupportedOperationException("IActivityManager.stopUser is not hosted")
    override fun stopUserWithCallback(userid: Int, callback: android.app.IStopUserCallback?): Int = throw UnsupportedOperationException("IActivityManager.stopUserWithCallback is not hosted")
    override fun stopUserExceptCertainProfiles(userid: Int, stopProfileRegardlessOfParent: Boolean, callback: android.app.IStopUserCallback?): Int = throw UnsupportedOperationException("IActivityManager.stopUserExceptCertainProfiles is not hosted")
    override fun stopUserWithDelayedLocking(userid: Int, callback: android.app.IStopUserCallback?): Int = throw UnsupportedOperationException("IActivityManager.stopUserWithDelayedLocking is not hosted")
    override fun registerUserSwitchObserver(observer: android.app.IUserSwitchObserver?, name: String?) { throw UnsupportedOperationException("IActivityManager.registerUserSwitchObserver is not hosted") }
    override fun unregisterUserSwitchObserver(observer: android.app.IUserSwitchObserver?) { throw UnsupportedOperationException("IActivityManager.unregisterUserSwitchObserver is not hosted") }
    override fun getRunningUserIds(): IntArray? = throw UnsupportedOperationException("IActivityManager.getRunningUserIds is not hosted")
    override fun requestSystemServerHeapDump() { throw UnsupportedOperationException("IActivityManager.requestSystemServerHeapDump is not hosted") }
    override fun requestBugReport(bugreportType: Int) { throw UnsupportedOperationException("IActivityManager.requestBugReport is not hosted") }
    override fun requestBugReportWithDescription(shareTitle: String?, shareDescription: String?, bugreportType: Int) { throw UnsupportedOperationException("IActivityManager.requestBugReportWithDescription is not hosted") }
    override fun requestTelephonyBugReport(shareTitle: String?, shareDescription: String?) { throw UnsupportedOperationException("IActivityManager.requestTelephonyBugReport is not hosted") }
    override fun requestWifiBugReport(shareTitle: String?, shareDescription: String?) { throw UnsupportedOperationException("IActivityManager.requestWifiBugReport is not hosted") }
    override fun requestInteractiveBugReportWithDescription(shareTitle: String?, shareDescription: String?) { throw UnsupportedOperationException("IActivityManager.requestInteractiveBugReportWithDescription is not hosted") }
    override fun requestInteractiveBugReport() { throw UnsupportedOperationException("IActivityManager.requestInteractiveBugReport is not hosted") }
    override fun requestBugReportWithExtraAttachments(extraAttachment: MutableList<android.net.Uri?>?) { throw UnsupportedOperationException("IActivityManager.requestBugReportWithExtraAttachments is not hosted") }
    override fun requestFullBugReport() { throw UnsupportedOperationException("IActivityManager.requestFullBugReport is not hosted") }
    override fun requestRemoteBugReport(nonce: Long) { throw UnsupportedOperationException("IActivityManager.requestRemoteBugReport is not hosted") }
    override fun launchBugReportHandlerApp(): Boolean = throw UnsupportedOperationException("IActivityManager.launchBugReportHandlerApp is not hosted")
    override fun getBugreportWhitelistedPackages(): MutableList<String?>? = throw UnsupportedOperationException("IActivityManager.getBugreportWhitelistedPackages is not hosted")
    override fun getIntentForIntentSender(sender: android.content.IIntentSender?): android.content.Intent? = throw UnsupportedOperationException("IActivityManager.getIntentForIntentSender is not hosted")
    override fun getLaunchedFromPackage(activityToken: android.os.IBinder?): String? = throw UnsupportedOperationException("IActivityManager.getLaunchedFromPackage is not hosted")
    override fun killUid(appId: Int, userId: Int, reason: String?) { throw UnsupportedOperationException("IActivityManager.killUid is not hosted") }
    override fun setUserIsMonkey(monkey: Boolean) { throw UnsupportedOperationException("IActivityManager.setUserIsMonkey is not hosted") }
    override fun hang(who: android.os.IBinder?, allowRestart: Boolean) { throw UnsupportedOperationException("IActivityManager.hang is not hosted") }
    override fun getAllRootTaskInfos(): MutableList<android.app.ActivityTaskManager.RootTaskInfo?>? = throw UnsupportedOperationException("IActivityManager.getAllRootTaskInfos is not hosted")
    override fun moveTaskToRootTask(taskId: Int, rootTaskId: Int, toTop: Boolean) { throw UnsupportedOperationException("IActivityManager.moveTaskToRootTask is not hosted") }
    override fun setFocusedRootTask(taskId: Int) { throw UnsupportedOperationException("IActivityManager.setFocusedRootTask is not hosted") }
    override fun getFocusedRootTaskInfo(): android.app.ActivityTaskManager.RootTaskInfo? = throw UnsupportedOperationException("IActivityManager.getFocusedRootTaskInfo is not hosted")
    override fun restart() { throw UnsupportedOperationException("IActivityManager.restart is not hosted") }
    override fun performIdleMaintenance() { throw UnsupportedOperationException("IActivityManager.performIdleMaintenance is not hosted") }
    override fun appNotRespondingViaProvider(connection: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.appNotRespondingViaProvider is not hosted") }
    override fun getTaskBounds(taskId: Int): android.graphics.Rect? = throw UnsupportedOperationException("IActivityManager.getTaskBounds is not hosted")
    override fun setProcessMemoryTrimLevel(process: String?, userId: Int, level: Int): Boolean = throw UnsupportedOperationException("IActivityManager.setProcessMemoryTrimLevel is not hosted")
    override fun getTagForIntentSender(sender: android.content.IIntentSender?, prefix: String?): String? = throw UnsupportedOperationException("IActivityManager.getTagForIntentSender is not hosted")
    override fun startUserInBackground(userid: Int): Boolean = throw UnsupportedOperationException("IActivityManager.startUserInBackground is not hosted")
    override fun isInLockTaskMode(): Boolean = throw UnsupportedOperationException("IActivityManager.isInLockTaskMode is not hosted")
    override fun startActivityFromRecents(taskId: Int, options: android.os.Bundle?): Int = throw UnsupportedOperationException("IActivityManager.startActivityFromRecents is not hosted")
    override fun startSystemLockTaskMode(taskId: Int) { throw UnsupportedOperationException("IActivityManager.startSystemLockTaskMode is not hosted") }
    override fun isTopOfTask(token: android.os.IBinder?): Boolean = throw UnsupportedOperationException("IActivityManager.isTopOfTask is not hosted")
    override fun bootAnimationComplete() { throw UnsupportedOperationException("IActivityManager.bootAnimationComplete is not hosted") }
    override fun setThemeOverlayReady(userId: Int) { throw UnsupportedOperationException("IActivityManager.setThemeOverlayReady is not hosted") }
    override fun registerTaskStackListener(listener: android.app.ITaskStackListener?) { throw UnsupportedOperationException("IActivityManager.registerTaskStackListener is not hosted") }
    override fun unregisterTaskStackListener(listener: android.app.ITaskStackListener?) { throw UnsupportedOperationException("IActivityManager.unregisterTaskStackListener is not hosted") }
    override fun notifyCleartextNetwork(uid: Int, firstPacket: ByteArray?) { throw UnsupportedOperationException("IActivityManager.notifyCleartextNetwork is not hosted") }
    override fun setTaskResizeable(taskId: Int, resizeableMode: Int) { throw UnsupportedOperationException("IActivityManager.setTaskResizeable is not hosted") }
    override fun resizeTask(taskId: Int, bounds: android.graphics.Rect?, resizeMode: Int) { throw UnsupportedOperationException("IActivityManager.resizeTask is not hosted") }
    override fun getLockTaskModeState(): Int = throw UnsupportedOperationException("IActivityManager.getLockTaskModeState is not hosted")
    override fun setDumpHeapDebugLimit(processName: String?, uid: Int, maxMemSize: Long, reportPackage: String?) { throw UnsupportedOperationException("IActivityManager.setDumpHeapDebugLimit is not hosted") }
    override fun dumpHeapFinished(path: String?) { throw UnsupportedOperationException("IActivityManager.dumpHeapFinished is not hosted") }
    override fun updateLockTaskPackages(userId: Int, packages: Array<String?>?) { throw UnsupportedOperationException("IActivityManager.updateLockTaskPackages is not hosted") }
    override fun noteAlarmStart(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) { throw UnsupportedOperationException("IActivityManager.noteAlarmStart is not hosted") }
    override fun noteAlarmFinish(sender: android.content.IIntentSender?, workSource: android.os.WorkSource?, sourceUid: Int, tag: String?) { throw UnsupportedOperationException("IActivityManager.noteAlarmFinish is not hosted") }
    override fun getPackageProcessState(packageName: String?, callingPackage: String?): Int = throw UnsupportedOperationException("IActivityManager.getPackageProcessState is not hosted")
    override fun startBinderTracking(): Boolean = throw UnsupportedOperationException("IActivityManager.startBinderTracking is not hosted")
    override fun stopBinderTrackingAndDump(fd: android.os.ParcelFileDescriptor?): Boolean = throw UnsupportedOperationException("IActivityManager.stopBinderTrackingAndDump is not hosted")
    override fun suppressResizeConfigChanges(suppress: Boolean) { throw UnsupportedOperationException("IActivityManager.suppressResizeConfigChanges is not hosted") }
    override fun unlockUser(userid: Int, token: ByteArray?, secret: ByteArray?, listener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.unlockUser is not hosted")
    override fun unlockUser2(userId: Int, listener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.unlockUser2 is not hosted")
    override fun killPackageDependents(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.killPackageDependents is not hosted") }
    override fun makePackageIdle(packageName: String?, userId: Int) { throw UnsupportedOperationException("IActivityManager.makePackageIdle is not hosted") }
    override fun setDeterministicUidIdle(deterministic: Boolean) { throw UnsupportedOperationException("IActivityManager.setDeterministicUidIdle is not hosted") }
    override fun getMemoryTrimLevel(): Int = throw UnsupportedOperationException("IActivityManager.getMemoryTrimLevel is not hosted")
    override fun isVrModePackageEnabled(packageName: android.content.ComponentName?): Boolean = throw UnsupportedOperationException("IActivityManager.isVrModePackageEnabled is not hosted")
    override fun notifyLockedProfile(userId: Int) { throw UnsupportedOperationException("IActivityManager.notifyLockedProfile is not hosted") }
    override fun startConfirmDeviceCredentialIntent(intent: android.content.Intent?, options: android.os.Bundle?) { throw UnsupportedOperationException("IActivityManager.startConfirmDeviceCredentialIntent is not hosted") }
    override fun sendIdleJobTrigger() { throw UnsupportedOperationException("IActivityManager.sendIdleJobTrigger is not hosted") }
    override fun sendIntentSender(caller: android.app.IApplicationThread?, target: android.content.IIntentSender?, whitelistToken: android.os.IBinder?, code: Int, intent: android.content.Intent?, resolvedType: String?, finishedReceiver: android.content.IIntentReceiver?, requiredPermission: String?, options: android.os.Bundle?): Int = throw UnsupportedOperationException("IActivityManager.sendIntentSender is not hosted")
    override fun isBackgroundRestricted(packageName: String?): Boolean = throw UnsupportedOperationException("IActivityManager.isBackgroundRestricted is not hosted")
    override fun setRenderThread(tid: Int) { throw UnsupportedOperationException("IActivityManager.setRenderThread is not hosted") }
    override fun setHasTopUi(hasTopUi: Boolean) { throw UnsupportedOperationException("IActivityManager.setHasTopUi is not hosted") }
    override fun cancelTaskWindowTransition(taskId: Int) { throw UnsupportedOperationException("IActivityManager.cancelTaskWindowTransition is not hosted") }
    override fun scheduleApplicationInfoChanged(packageNames: MutableList<String?>?, userId: Int) { throw UnsupportedOperationException("IActivityManager.scheduleApplicationInfoChanged is not hosted") }
    override fun setPersistentVrThread(tid: Int) { throw UnsupportedOperationException("IActivityManager.setPersistentVrThread is not hosted") }
    override fun waitForNetworkStateUpdate(procStateSeq: Long) { throw UnsupportedOperationException("IActivityManager.waitForNetworkStateUpdate is not hosted") }
    override fun backgroundAllowlistUid(uid: Int) { throw UnsupportedOperationException("IActivityManager.backgroundAllowlistUid is not hosted") }
    override fun startUserInBackgroundWithListener(userid: Int, unlockProgressListener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.startUserInBackgroundWithListener is not hosted")
    override fun startDelegateShellPermissionIdentity(uid: Int, permissions: Array<String?>?) { throw UnsupportedOperationException("IActivityManager.startDelegateShellPermissionIdentity is not hosted") }
    override fun stopDelegateShellPermissionIdentity() { throw UnsupportedOperationException("IActivityManager.stopDelegateShellPermissionIdentity is not hosted") }
    override fun getDelegatedShellPermissions(): MutableList<String?>? = throw UnsupportedOperationException("IActivityManager.getDelegatedShellPermissions is not hosted")
    override fun getLifeMonitor(): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IActivityManager.getLifeMonitor is not hosted")
    override fun startUserInForegroundWithListener(userid: Int, unlockProgressListener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.startUserInForegroundWithListener is not hosted")
    override fun appNotResponding(reason: String?) { throw UnsupportedOperationException("IActivityManager.appNotResponding is not hosted") }
    override fun getHistoricalProcessStartReasons(packageName: String?, maxNum: Int, userId: Int): android.content.pm.ParceledListSlice<android.app.ApplicationStartInfo>? = throw UnsupportedOperationException("IActivityManager.getHistoricalProcessStartReasons is not hosted")
    override fun addApplicationStartInfoCompleteListener(listener: android.app.IApplicationStartInfoCompleteListener?, userId: Int) { throw UnsupportedOperationException("IActivityManager.addApplicationStartInfoCompleteListener is not hosted") }
    override fun removeApplicationStartInfoCompleteListener(listener: android.app.IApplicationStartInfoCompleteListener?, userId: Int) { throw UnsupportedOperationException("IActivityManager.removeApplicationStartInfoCompleteListener is not hosted") }
    override fun addStartInfoTimestamp(key: Int, timestampNs: Long, userId: Int) { throw UnsupportedOperationException("IActivityManager.addStartInfoTimestamp is not hosted") }
    override fun reportStartInfoViewTimestamps(renderThreadDrawStartTimeNs: Long, framePresentedTimeNs: Long) { throw UnsupportedOperationException("IActivityManager.reportStartInfoViewTimestamps is not hosted") }
    override fun getHistoricalProcessExitReasons(packageName: String?, pid: Int, maxNum: Int, userId: Int): android.content.pm.ParceledListSlice<android.app.ApplicationExitInfo>? = throw UnsupportedOperationException("IActivityManager.getHistoricalProcessExitReasons is not hosted")
    override fun killProcessesWhenImperceptible(pids: IntArray?, reason: String?) { throw UnsupportedOperationException("IActivityManager.killProcessesWhenImperceptible is not hosted") }
    override fun setActivityLocusContext(activity: android.content.ComponentName?, locusId: android.content.LocusId?, appToken: android.os.IBinder?) { throw UnsupportedOperationException("IActivityManager.setActivityLocusContext is not hosted") }
    override fun setProcessStateSummary(state: ByteArray?) { throw UnsupportedOperationException("IActivityManager.setProcessStateSummary is not hosted") }
    override fun isAppFreezerSupported(): Boolean = throw UnsupportedOperationException("IActivityManager.isAppFreezerSupported is not hosted")
    override fun isAppFreezerEnabled(): Boolean = throw UnsupportedOperationException("IActivityManager.isAppFreezerEnabled is not hosted")
    override fun killUidForPermissionChange(appId: Int, userId: Int, reason: String?) { throw UnsupportedOperationException("IActivityManager.killUidForPermissionChange is not hosted") }
    override fun resetAppErrors() { throw UnsupportedOperationException("IActivityManager.resetAppErrors is not hosted") }
    override fun enableAppFreezer(enable: Boolean): Boolean = throw UnsupportedOperationException("IActivityManager.enableAppFreezer is not hosted")
    override fun enableFgsNotificationRateLimit(enable: Boolean): Boolean = throw UnsupportedOperationException("IActivityManager.enableFgsNotificationRateLimit is not hosted")
    override fun holdLock(token: android.os.IBinder?, durationMs: Int) { throw UnsupportedOperationException("IActivityManager.holdLock is not hosted") }
    override fun startProfile(userId: Int): Boolean = throw UnsupportedOperationException("IActivityManager.startProfile is not hosted")
    override fun stopProfile(userId: Int): Boolean = throw UnsupportedOperationException("IActivityManager.stopProfile is not hosted")
    override fun queryIntentComponentsForIntentSender(sender: android.content.IIntentSender?, matchFlags: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IActivityManager.queryIntentComponentsForIntentSender is not hosted")
    override fun getUidProcessCapabilities(uid: Int, callingPackage: String?): Int = throw UnsupportedOperationException("IActivityManager.getUidProcessCapabilities is not hosted")
    override fun waitForBroadcastIdle() { throw UnsupportedOperationException("IActivityManager.waitForBroadcastIdle is not hosted") }
    override fun waitForBroadcastBarrier() { throw UnsupportedOperationException("IActivityManager.waitForBroadcastBarrier is not hosted") }
    override fun forceDelayBroadcastDelivery(targetPackage: String?, delayedDurationMs: Long) { throw UnsupportedOperationException("IActivityManager.forceDelayBroadcastDelivery is not hosted") }
    override fun isProcessFrozen(pid: Int): Boolean = throw UnsupportedOperationException("IActivityManager.isProcessFrozen is not hosted")
    override fun getBackgroundRestrictionExemptionReason(uid: Int): Int = throw UnsupportedOperationException("IActivityManager.getBackgroundRestrictionExemptionReason is not hosted")
    override fun startUserInBackgroundVisibleOnDisplay(userid: Int, displayId: Int, unlockProgressListener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.startUserInBackgroundVisibleOnDisplay is not hosted")
    override fun startProfileWithListener(userid: Int, unlockProgressListener: android.os.IProgressListener?): Boolean = throw UnsupportedOperationException("IActivityManager.startProfileWithListener is not hosted")
    override fun restartUserInBackground(userId: Int, userStartMode: Int): Int = throw UnsupportedOperationException("IActivityManager.restartUserInBackground is not hosted")
    override fun getDisplayIdsForStartingVisibleBackgroundUsers(): IntArray? = throw UnsupportedOperationException("IActivityManager.getDisplayIdsForStartingVisibleBackgroundUsers is not hosted")
    override fun shouldServiceTimeOut(className: android.content.ComponentName?, token: android.os.IBinder?): Boolean = throw UnsupportedOperationException("IActivityManager.shouldServiceTimeOut is not hosted")
    override fun hasServiceTimeLimitExceeded(className: android.content.ComponentName?, token: android.os.IBinder?): Boolean = throw UnsupportedOperationException("IActivityManager.hasServiceTimeLimitExceeded is not hosted")
    override fun registerUidFrozenStateChangedCallback(callback: android.app.IUidFrozenStateChangedCallback?) { throw UnsupportedOperationException("IActivityManager.registerUidFrozenStateChangedCallback is not hosted") }
    override fun unregisterUidFrozenStateChangedCallback(callback: android.app.IUidFrozenStateChangedCallback?) { throw UnsupportedOperationException("IActivityManager.unregisterUidFrozenStateChangedCallback is not hosted") }
    override fun getUidFrozenState(uids: IntArray?): IntArray? = throw UnsupportedOperationException("IActivityManager.getUidFrozenState is not hosted")
    override fun checkPermissionForDevice(permission: String?, pid: Int, uid: Int, deviceId: Int): Int = throw UnsupportedOperationException("IActivityManager.checkPermissionForDevice is not hosted")
    override fun frozenBinderTransactionDetected(debugPid: Int, code: Int, flags: Int, err: Int) { throw UnsupportedOperationException("IActivityManager.frozenBinderTransactionDetected is not hosted") }
    override fun getBindingUidProcessState(uid: Int, callingPackage: String?): Int = throw UnsupportedOperationException("IActivityManager.getBindingUidProcessState is not hosted")
    override fun getUidLastIdleElapsedTime(uid: Int, callingPackage: String?): Long = throw UnsupportedOperationException("IActivityManager.getUidLastIdleElapsedTime is not hosted")
    override fun addOverridePermissionState(originatingUid: Int, uid: Int, permission: String?, result: Int) { throw UnsupportedOperationException("IActivityManager.addOverridePermissionState is not hosted") }
    override fun removeOverridePermissionState(originatingUid: Int, uid: Int, permission: String?) { throw UnsupportedOperationException("IActivityManager.removeOverridePermissionState is not hosted") }
    override fun clearOverridePermissionStates(originatingUid: Int, uid: Int) { throw UnsupportedOperationException("IActivityManager.clearOverridePermissionStates is not hosted") }
    override fun clearAllOverridePermissionStates(originatingUid: Int) { throw UnsupportedOperationException("IActivityManager.clearAllOverridePermissionStates is not hosted") }
    override fun noteAppRestrictionEnabled(packageName: String?, uid: Int, restrictionType: Int, enabled: Boolean, reason: Int, subReason: String?, source: Int, threshold: Long) { throw UnsupportedOperationException("IActivityManager.noteAppRestrictionEnabled is not hosted") }
    override fun refreshIntentCreatorToken(intent: android.content.Intent?): android.os.IBinder? = throw UnsupportedOperationException("IActivityManager.refreshIntentCreatorToken is not hosted")
}
