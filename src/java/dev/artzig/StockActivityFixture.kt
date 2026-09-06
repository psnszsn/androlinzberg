package dev.artzig

import android.app.Activity
import android.app.ActivityClient
import android.app.ActivityTaskManager
import android.app.ActivityThread
import android.app.IApplicationThread
import android.app.servertransaction.ActivityTransactionItem
import android.app.servertransaction.ClientTransaction
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.os.IBinder
import android.os.Process
import dev.artzig.system.HostedActivityManagerService
import dev.artzig.system.HostedActivityTaskManagerService
import dev.artzig.system.HostedPackageManagerService

/** Manifest-backed process fixture. The production task manager and stock transactions own
 * lifecycle decisions; only component metadata and service lookup are replaced for the test. */
internal class StockActivityFixture(apk: String, private val pump: () -> Unit) : AutoCloseable {
    private val context = HostContexts.systemContext(
        checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
    private val packages = HostedPackageManagerService().apply {
        installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        check(installPackage(apk) == PACKAGE)
        bindPackageUid(PACKAGE, Process.myUid())
    }
    private val applicationInfo = checkNotNull(packages.getApplicationInfo(PACKAGE, 0L, 0))
    private var activityInfos: Map<String, ActivityInfo> = emptyMap()
    private var resolver: (Intent) -> ComponentName? = { it.component }
    private val tokens = LinkedHashSet<IBinder>()
    private val applicationThread = ActivityThread.currentActivityThread().applicationThread
    private val transport = object : IApplicationThread by applicationThread {
        override fun bindApplication(
            packageName: String?, info: android.content.pm.ApplicationInfo?,
            sdkSandboxClientAppVolumeUuid: String?, sdkSandboxClientAppPackage: String?,
            isSdkInSandbox: Boolean, providerList: android.content.pm.ProviderInfoList?,
            testName: android.content.ComponentName?, profilerInfo: android.app.ProfilerInfo?,
            testArguments: android.os.Bundle?, testWatcher: android.app.IInstrumentationWatcher?,
            uiAutomationConnection: android.app.IUiAutomationConnection?, debugMode: Int,
            enableBinderTracking: Boolean, trackAllocation: Boolean,
            restrictedBackupMode: Boolean, persistent: Boolean,
            config: android.content.res.Configuration?, compatInfo: android.content.res.CompatibilityInfo?,
            services: MutableMap<*, *>?, coreSettings: android.os.Bundle?, buildSerial: String?,
            autofillOptions: android.content.AutofillOptions?, contentCaptureOptions: android.content.ContentCaptureOptions?,
            disabledCompatChanges: LongArray?, loggableCompatChanges: LongArray?,
            serializedSystemFontMap: android.os.SharedMemory?, applicationSharedMemoryFd: java.io.FileDescriptor?,
            startRequestedElapsedTime: Long, startRequestedUptime: Long,
        ) {
            // The fixture models an already bound process. Application binding
            // itself is exercised by the separate-process system tests.
            check(packageName == PACKAGE && info?.packageName == PACKAGE)
            checkNotNull(providerList)
        }
        override fun scheduleTransaction(transaction: ClientTransaction?) {
            checkNotNull(transaction).transactionItems.forEach {
                if (it is ActivityTransactionItem) tokens += it.activityToken
            }
            applicationThread.scheduleTransaction(transaction)
        }
    }
    private val activityManager = HostedActivityManagerService(context).apply { packageService = packages }
    private val tasks = HostedActivityTaskManagerService(context, activityManager, packages) { intent, _ ->
        val component = intent.component ?: resolver(Intent(intent))
        activityInfos[component?.className]?.let(::ActivityInfo)
    }
    private val packageField = ActivityThread::class.java.getDeclaredField("sPackageManager")
        .apply { isAccessible = true }
    private val originalPackages = ActivityThread.getPackageManager()
    private val fixturePackages = object : IPackageManager by originalPackages {
        override fun getActivityInfo(name: ComponentName?, flags: Long, userId: Int): ActivityInfo? =
            activityInfos[name?.className]?.let(::ActivityInfo)
                ?: originalPackages.getActivityInfo(name, flags, userId)
        override fun getApplicationInfo(name: String?, flags: Long, userId: Int): ApplicationInfo? =
            if (name == PACKAGE) packages.getApplicationInfo(name, flags, userId)
            else originalPackages.getApplicationInfo(name, flags, userId)
    }
    private val taskSingleton = ActivityTaskManager::class.java
        .getDeclaredField("IActivityTaskManagerSingleton").apply { isAccessible = true }.get(null)
    private val instanceField = android.util.Singleton::class.java
        .getDeclaredField("mInstance").apply { isAccessible = true }
    private val originalTasks = ActivityTaskManager.getService()
    private val clientSingleton = ActivityClient::class.java.getDeclaredField("INTERFACE_SINGLETON")
        .apply { isAccessible = true }.get(null)
    private val controllerField = clientSingleton.javaClass.getDeclaredField("mKnownInstance")
        .apply { isAccessible = true }
    private val originalController = controllerField.get(clientSingleton)
    private val contextPackageField = Class.forName("android.app.ContextImpl").getDeclaredField("mPackageManager")
        .apply { isAccessible = true }
    private val contextPackages = java.util.IdentityHashMap<Context, Any?>()

    init {
        activityManager.activityTaskManager = tasks
        activityManager.noteProcessPackage(Process.myUid(), PACKAGE, PACKAGE,
            android.content.res.Configuration(context.resources.configuration))
        activityManager.attachApplication(transport, 0)
        packageField.set(null, fixturePackages)
        for (source in listOfNotNull(context, ActivityThread.currentApplication())) {
            var base: Context = source
            while (base is ContextWrapper) base = base.baseContext
            if (!contextPackages.containsKey(base)) {
                contextPackages[base] = contextPackageField.get(base)
                contextPackageField.set(base, null)
            }
        }
        instanceField.set(taskSingleton, tasks)
        ActivityClient.setActivityClientController(tasks.activityClientController)
    }

    fun configure(adapter: ActivityThread.HostAdapter, infos: Map<String, ActivityInfo>,
        resolver: (Intent) -> ComponentName? = { it.component }) {
        this.resolver = resolver
        activityInfos = infos.mapValues { (_, source) -> ActivityInfo(source).apply {
            processName = PACKAGE
            applicationInfo = ApplicationInfo(source.applicationInfo).apply {
                packageName = PACKAGE
                processName = PACKAGE
                uid = this@StockActivityFixture.applicationInfo.uid
                sourceDir = this@StockActivityFixture.applicationInfo.sourceDir
                publicSourceDir = this@StockActivityFixture.applicationInfo.publicSourceDir
                dataDir = this@StockActivityFixture.applicationInfo.dataDir
                credentialProtectedDataDir = this@StockActivityFixture.applicationInfo.credentialProtectedDataDir
                deviceProtectedDataDir = this@StockActivityFixture.applicationInfo.deviceProtectedDataDir
            }
        } }
        ActivityThread.configureActivityHost(adapter)
    }

    fun launch(name: String): Activity {
        val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PACKAGE, name))
        val result = tasks.startActivity(applicationThread, PACKAGE, null, intent, null,
            null, null, -1, 0, null, null)
        android.app.Instrumentation.checkStartActivityResult(result, intent)
        pump()
        return checkNotNull(ActivityThread.currentActivity()).also { check(it.javaClass.name == name) }
    }

    fun activitySender(intent: Intent, canceled: Boolean = false): android.content.IntentSender {
        val target = checkNotNull(activityManager.getIntentSenderWithFeature(
            android.app.ActivityManager.INTENT_SENDER_ACTIVITY, PACKAGE, null, null, null, 0,
            arrayOf(intent), arrayOf(null), android.app.PendingIntent.FLAG_IMMUTABLE,
            null, android.os.UserHandle.USER_SYSTEM))
        if (canceled) activityManager.cancelIntentSender(target)
        return android.content.IntentSender(target)
    }

    /** This in-process server owns local Binder tokens, which public Activity.recreate
     * deliberately ignores. Exercise server relaunch transactions for these fixtures. */
    fun relaunch(activity: Activity) {
        val thread = ActivityThread.currentActivityThread()
        val record = checkNotNull(thread.getActivityClient(activity.activityToken))
        val transaction = ClientTransaction(applicationThread)
        tasks.activityClientController.activityLocalRelaunch(record.token)
        transaction.addTransactionItem(android.app.servertransaction.ActivityRelaunchItem(
            record.token, null, null, 0,
            android.util.MergedConfiguration(context.resources.configuration, record.overrideConfig),
            false, record.activityWindowInfo))
        transaction.addTransactionItem(android.app.servertransaction.TransactionExecutorHelper
            .getLifecycleRequestForCurrentState(record))
        transport.scheduleTransaction(transaction)
        pump()
    }

    override fun close() {
        try {
            for (token in tokens.toList().asReversed()) {
                tasks.activityClientController.finishActivity(token, Activity.RESULT_CANCELED, null, 0)
            }
            pump()
            ActivityThread.shutdownActivitiesInProcess()
        } finally {
            tasks.onProcessDied(transport.asBinder())
            instanceField.set(taskSingleton, originalTasks)
            controllerField.set(clientSingleton, originalController)
            packageField.set(null, originalPackages)
            contextPackages.forEach { (base, previous) -> contextPackageField.set(base, previous) }
        }
    }

    private companion object { const val PACKAGE = "org.example" }
}
