package dev.artzig.system

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.IActivityClientController
import android.app.IActivityTaskManager
import android.app.IApplicationThread
import android.app.ITaskStackListener
import android.app.ProfilerInfo
import android.app.ResultInfo
import android.app.servertransaction.ActivityRelaunchItem
import android.app.servertransaction.ActivityConfigurationChangeItem
import android.app.servertransaction.ActivityResultItem
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.DestroyActivityItem
import android.app.servertransaction.LaunchActivityItem
import android.app.servertransaction.NewIntentItem
import android.app.servertransaction.PauseActivityItem
import android.app.servertransaction.ResumeActivityItem
import android.app.servertransaction.StopActivityItem
import android.content.ComponentName
import android.content.Context
import android.content.IIntentSender
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.util.MergedConfiguration
import android.view.Display
import android.window.ActivityWindowInfo
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import com.android.internal.content.ReferrerIntent
import com.android.server.LocalServices
import com.android.server.uri.GrantUri
import com.android.server.uri.NeededUriGrants
import com.android.server.uri.UriGrantsManagerInternal
import com.android.server.uri.UriPermissionOwner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session-local activity/task owner on the stock IActivityTaskManager and
 * IActivityClientController transports.
 *
 * Source note: this follows the bounded control flow of AOSP
 * `ActivityTaskManagerService`, `ActivityStartController`, `ActivityTaskSupervisor`,
 * `ActivityClientController`, and `TaskChangeNotificationController`. The server-side WM
 * container graph cannot be reused without system_server/WindowManagerService, so the host keeps
 * only the observable task/activity records and emits the same ClientTransaction items. Actual
 * windows remain owned by ActivityThread's HostAdapter fence.
 */
internal class HostedActivityTaskManagerService(
    private val context: Context,
    private val ams: HostedActivityManagerService,
    private val packages: HostedPackageManagerService,
    private val testActivityResolver: ((Intent, String?) -> ActivityInfo?)? = null,
) : IActivityTaskManager.Stub(), IActivityTaskManager by unsupported<IActivityTaskManager>() {
    override fun asBinder(): IBinder = this

    private companion object {
        const val TAG = "HostedActivityTaskManager"
        const val STATE_INITIALIZING = 0
        const val STATE_RESUMED = 1
        const val STATE_PAUSED = 2
        const val STATE_STOPPED = 3
        const val DONT_FINISH_TASK_WITH_ACTIVITY = Activity.DONT_FINISH_TASK_WITH_ACTIVITY
        const val FINISH_TASK_WITH_ROOT_ACTIVITY = Activity.FINISH_TASK_WITH_ROOT_ACTIVITY
        const val FINISH_TASK_WITH_ACTIVITY = Activity.FINISH_TASK_WITH_ACTIVITY
    }

    private class ProcessRecord(
        val thread: IApplicationThread,
        val binder: IBinder,
        val pid: Int,
        val uid: Int,
        val packageName: String,
        val processName: String,
    )

    private class TaskRecord(
        val taskId: Int,
        var baseIntent: Intent,
        val rootAffinity: String?,
        val userId: Int,
        var realActivity: ComponentName,
        val requiredDisplayCategory: String?,
        val token: WindowContainerToken = WindowContainerToken(
            object : IWindowContainerToken.Stub() {},
        ),
    ) {
        val activities = ArrayList<ActivityRecord>()
        var inRecents = true
        var description: ActivityManager.TaskDescription? = null
        var lastActiveTime: Long = android.os.SystemClock.elapsedRealtime()
    }

    private class ActivityRecord(
        val token: IBinder,
        val task: TaskRecord,
        var process: ProcessRecord?,
        val intent: Intent,
        val info: ActivityInfo,
        val launchedFromUid: Int,
        val launchedFromPackage: String?,
        val resultTo: IBinder?,
        val resultWho: String?,
        val requestCode: Int,
    ) {
        val assistToken: IBinder = Binder()
        val shareableActivityToken: IBinder = Binder()
        val initialCallerInfoAccessToken: IBinder = Binder()
        var state = STATE_INITIALIZING
        var savedState: Bundle? = null
        var persistentState: PersistableBundle? = null
        var description: CharSequence? = null
        var requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var immersive = false
        var finishing = false
        var stateBeforeRelaunch = STATE_INITIALIZING
        var previousToStop: IBinder? = null
        val destroyAfterResume = ArrayList<IBinder>()
        val afterPause = ArrayList<() -> Unit>()
        val pendingResults = ArrayList<ResultInfo>()
        val pendingNewIntents = ArrayList<ReferrerIntent>()
        var sceneTransitionInfo: android.app.ActivityOptions.SceneTransitionInfo? = null
        var launchScheduled = false
        var cancelIfStale = true
        var uriPermissions: UriPermissionOwner? = null
    }

    private data class CallerIdentity(
        val ownerActivityToken: IBinder,
        val uid: Int,
        val packageName: String?,
        val uris: ActivityCallerUris,
        val shareIdentity: Boolean = false,
    )

    private data class ConfigurationDispatch(
        val record: ActivityRecord,
        val handled: Boolean,
        val stateBeforeRelaunch: Int,
    )

    private val lock = Any()
    private val nextTaskId = AtomicInteger(1)
    private val nextLaunchRequestId = AtomicInteger(1)
    private val processes = LinkedHashMap<IBinder, ProcessRecord>()
    private val activities = LinkedHashMap<IBinder, ActivityRecord>()
    /** ComponentCaller identities are scoped to the receiving activity record. */
    private val callerIdentities = LinkedHashMap<IBinder, CallerIdentity>()
    /** In insertion order, with the focused/front task last. */
    private val tasks = LinkedHashMap<Int, TaskRecord>()
    private val listeners = RemoteCallbackList<ITaskStackListener>()
    private val globalConfiguration = Configuration(context.resources.configuration)
    private var configurationSeq = maxOf(globalConfiguration.seq, 1)
    private val activityClientController = HostedActivityClientController()

    override fun getActivityClientController(): IActivityClientController = activityClientController

    internal fun onProcessAttached(
        thread: IApplicationThread,
        pid: Int,
        uid: Int,
        packageName: String,
        processName: String,
    ) {
        synchronized(lock) {
            processes[thread.asBinder()] = ProcessRecord(
                thread, thread.asBinder(), pid, uid, packageName, processName,
            )
        }
    }

    internal fun onProcessDied(processBinder: IBinder) {
        val removedTasks = ArrayList<Int>()
        val afterPause = ArrayList<() -> Unit>()
        val deferredDestroy = ArrayList<ActivityRecord>()
        var reveal: ActivityRecord? = null
        var hadDeadActivities = false
        synchronized(lock) {
            processes.remove(processBinder)
            val dead = activities.values.filter { it.process?.binder == processBinder }
            hadDeadActivities = dead.isNotEmpty()
            val focusedDied = focusedActivityLocked()?.let(dead::contains) == true
            for (record in dead) {
                afterPause.addAll(record.afterPause)
                record.afterPause.clear()
                for (destroyToken in record.destroyAfterResume) {
                    activityForTokenLocked(destroyToken)?.let(deferredDestroy::add)
                }
                record.destroyAfterResume.clear()
                removeActivityRecordLocked(record)?.let(removedTasks::add)
            }
            if (focusedDied) {
                reveal = focusedActivityLocked()?.takeIf {
                    it.process != null &&
                        (it.state == STATE_PAUSED || it.state == STATE_STOPPED)
                }
            }
        }
        for (taskId in removedTasks) notifyTaskRemoved(taskId)
        afterPause.forEach { it() }
        deferredDestroy.distinctBy { it.token }.forEach { record ->
            val stillLive = synchronized(lock) {
                activities[record.token] === record && record.process != null
            }
            if (stillLive) scheduleDestroy(record)
        }
        reveal?.let(::scheduleResume)
        if (hadDeadActivities) notifyTaskStackChanged()
    }

    override fun startActivity(
        caller: IApplicationThread?,
        callingPackage: String?,
        callingFeatureId: String?,
        intent: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        flags: Int,
        profilerInfo: ProfilerInfo?,
        options: Bundle?,
    ): Int = startActivityInternal(
        caller,
        callingPackage,
        Binder.getCallingUid(),
        intent,
        resolvedType,
        resultTo,
        resultWho,
        requestCode,
        options,
    )

    override fun startActivityAsUser(
        caller: IApplicationThread?,
        callingPackage: String?,
        callingFeatureId: String?,
        intent: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        flags: Int,
        profilerInfo: ProfilerInfo?,
        options: Bundle?,
        userId: Int,
    ): Int {
        if (userId != UserHandle.USER_SYSTEM && userId != UserHandle.USER_CURRENT) {
            return ActivityManager.START_PERMISSION_DENIED
        }
        return startActivityInternal(
            caller,
            callingPackage,
            Binder.getCallingUid(),
            intent,
            resolvedType,
            resultTo,
            resultWho,
            requestCode,
            options,
        )
    }

    override fun startActivities(
        caller: IApplicationThread?,
        callingPackage: String?,
        callingFeatureId: String?,
        intents: Array<Intent?>?,
        resolvedTypes: Array<String?>?,
        resultTo: IBinder?,
        options: Bundle?,
        userId: Int,
    ): Int {
        if (userId != UserHandle.USER_SYSTEM && userId != UserHandle.USER_CURRENT) {
            return ActivityManager.START_PERMISSION_DENIED
        }
        return startActivitySequence(
            caller,
            callingPackage,
            Binder.getCallingUid(),
            intents,
            resolvedTypes,
            resultTo,
            options,
        )
    }

    override fun startActivityIntentSender(
        caller: IApplicationThread?,
        target: IIntentSender?,
        whitelistToken: IBinder?,
        fillInIntent: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        flagsMask: Int,
        flagsValues: Int,
        options: Bundle?,
    ): Int = ams.sendActivityIntentSender(
        target,
        fillInIntent,
        resolvedType,
        resultTo,
        resultWho,
        requestCode,
        flagsMask,
        flagsValues,
        options,
    )

    internal fun startActivityFromPendingIntent(
        creatorPackage: String?,
        creatorUid: Int,
        intent: Intent,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        options: Bundle?,
    ): Int = startActivityInternal(
        null,
        creatorPackage,
        creatorUid,
        intent,
        resolvedType,
        resultTo,
        resultWho,
        requestCode,
        options,
    )

    internal fun startActivitiesFromPendingIntent(
        creatorPackage: String?,
        creatorUid: Int,
        intents: Array<Intent?>,
        resolvedTypes: Array<String?>?,
        resultTo: IBinder?,
        options: Bundle?,
    ): Int = startActivitySequence(
        null,
        creatorPackage,
        creatorUid,
        intents,
        resolvedTypes,
        resultTo,
        options,
    )

    private fun startActivitySequence(
        caller: IApplicationThread?,
        callingPackage: String?,
        callingUid: Int,
        intents: Array<Intent?>?,
        resolvedTypes: Array<String?>?,
        resultTo: IBinder?,
        options: Bundle?,
    ): Int {
        val launches = intents ?: return ActivityManager.START_INTENT_NOT_RESOLVED
        if (launches.isEmpty()) return ActivityManager.START_INTENT_NOT_RESOLVED
        var task: TaskRecord? = null
        var result = ActivityManager.START_SUCCESS
        for (index in launches.indices) {
            result = startActivityInternal(
                caller,
                callingPackage,
                callingUid,
                launches[index],
                resolvedTypes?.getOrNull(index),
                if (index == 0) resultTo else null,
                null,
                -1,
                if (index == launches.lastIndex) options else null,
                task,
                index == launches.lastIndex,
            ) { selected -> task = selected.task }
            if (ActivityManager.isStartResultFatalError(result)) return result
        }
        return result
    }

    internal fun deliverPendingActivityResult(
        activityToken: IBinder?,
        resultWho: String?,
        requestCode: Int,
        resultCode: Int,
        data: Intent,
        callerUid: Int,
        callerPackage: String?,
    ): Boolean {
        val callerUris = if (callerUid != android.os.Process.INVALID_UID) {
            ActivityCallerUris.capture(uriGrants(), data, callerUid)
        } else null
        var deliverNow = false
        lateinit var result: ResultInfo
        val record = synchronized(lock) {
            activityForTokenLocked(activityToken)?.also {
                val callerToken = if (callerUid != android.os.Process.INVALID_UID) {
                    registerCallerIdentityLocked(it.token, callerUid, callerPackage, checkNotNull(callerUris))
                } else {
                    null
                }
                result = ResultInfo(resultWho, requestCode, resultCode, data, callerToken)
                deliverNow = it.state == STATE_RESUMED && focusedActivityLocked() === it
                if (!deliverNow) it.pendingResults += result
            }
        } ?: return false
        if (deliverNow) {
            val process = record.process ?: return false
            schedule(process, ActivityResultItem(record.token, listOf(result)))
        }
        return true
    }

    private fun startActivityInternal(
        caller: IApplicationThread?,
        callingPackage: String?,
        callingUid: Int,
        sourceIntent: Intent?,
        resolvedType: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        options: Bundle?,
        taskOverride: TaskRecord? = null,
        cancelIfStale: Boolean = true,
        onRecordSelected: ((ActivityRecord) -> Unit)? = null,
    ): Int {
        val intent = sourceIntent?.let(::Intent) ?: return ActivityManager.START_INTENT_NOT_RESOLVED
        val info = testActivityResolver?.invoke(intent, resolvedType)
            ?: packages.resolveActivityInfo(intent, resolvedType)
            ?: return if (intent.component != null) {
                ActivityManager.START_CLASS_NOT_FOUND
            } else {
                ActivityManager.START_INTENT_NOT_RESOLVED
            }
        val targetUid = packages.getPackageUidInternal(info.packageName)
        // ActivityTaskManagerService.assertPackageMatchesCallingUid authenticates against the
        // Binder caller. A supplied IApplicationThread is never an authority for package identity.
        if (callingPackage != null && !packages.isSameAppInternal(callingPackage, callingUid)) {
            throw SecurityException("uid $callingUid does not own $callingPackage")
        }
        // AOSP ActivityStarter.Request.resolveActivity collects grants before changing the
        // activity graph. UriGrantsManagerService owns URI traversal and access validation.
        val grants = uriGrants()
        val requestId = nextLaunchRequestId.getAndIncrement()
        val callerActivityName = synchronized(lock) {
            resultTo?.let(activities::get)?.info?.name
        }
        var successful = false
        try {
            val needed = if (android.security.Flags.contentUriPermissionApis()) {
                grants.checkGrantUriPermissionFromIntent(intent, callingUid, info.packageName,
                    UserHandle.getUserId(targetUid), info.requireContentUriPermissionFromCaller,
                    requestId)
            } else {
                grants.checkGrantUriPermissionFromIntent(intent, callingUid, info.packageName,
                    UserHandle.getUserId(targetUid))
            }
            if (!info.exported && callingUid != targetUid && callingUid != android.os.Process.SYSTEM_UID) {
                return ActivityManager.START_PERMISSION_DENIED
            }
            val permission = info.permission
            if (permission != null && callingUid != targetUid && callingUid != android.os.Process.SYSTEM_UID &&
                packages.checkUidPermission(permission, callingUid) != PackageManager.PERMISSION_GRANTED
            ) {
                return ActivityManager.START_PERMISSION_DENIED
            }
            return startResolvedActivity(intent, info, callingUid, callingPackage, resultTo,
                resultWho, requestCode, options, taskOverride, cancelIfStale, onRecordSelected,
                needed).also { successful = ActivityManager.isStartResultSuccessful(it) }
        } finally {
            // AOSP ActivityStarter.execute also clears unsuccessful launch accounting.
            grants.notifyActivityLaunchRequestCompleted(requestId, successful, intent.action,
                callingUid, callerActivityName, targetUid, info.name, resultTo != null)
        }
    }

    private fun realComponent(info: ActivityInfo) = ComponentName(info.packageName, info.targetActivity ?: info.name)

    private fun taskBaseIntent(intent: Intent) = Intent(intent).apply {
        selector = null
        sourceBounds = null
    }

    /** ActivityStarter.adjustLaunchFlagsToDocumentMode; manifest document policy wins. */
    private fun documentLaunchFlags(original: Int, info: ActivityInfo): Int {
        val documentFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        if (original and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0 &&
            (info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE || info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK)
        ) return original and documentFlags.inv()
        return when (info.documentLaunchMode) {
            ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, ActivityInfo.DOCUMENT_LAUNCH_ALWAYS ->
                original or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            ActivityInfo.DOCUMENT_LAUNCH_NEVER -> if (
                info.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK ||
                original and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0
            ) original and documentFlags.inv() else original
            else -> original
        }
    }

    /**
     * ActivityStarter.resolveReusableTask and RootWindowContainer.FindTaskResult:
     * exact component/document matches outrank the most recent affinity candidate.
     * The host has one task display area rather than the stock WM container graph.
     */
    private fun findReusableTaskLocked(intent: Intent, info: ActivityInfo): TaskRecord? {
        val singleInstance = info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
        val singleTask = info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
        if (!singleInstance && !singleTask &&
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0 ||
                intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        ) return null
        val component = realComponent(info)
        val userId = UserHandle.getUserId(packages.getPackageUidInternal(info.packageName))
        val documentData = if (intent.isDocument) intent.data else null
        var affinity: TaskRecord? = null
        for (task in tasks.values.toList().asReversed()) {
            val top = task.activities.lastOrNull { !it.finishing } ?: continue
            if (task.userId != userId) continue
            if (singleInstance) {
                if (task.activities.any { !it.finishing && realComponent(it.info) == component }) return task
                continue
            }
            if (top.info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) continue
            if (info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK && task.realActivity != component) continue
            val taskData = if (task.baseIntent.isDocument) task.baseIntent.data else null
            if (task.realActivity == component && taskData == documentData) return task
            if (affinity == null && !intent.isDocument && !task.baseIntent.isDocument &&
                task.rootAffinity != null && task.rootAffinity == info.taskAffinity &&
                task.requiredDisplayCategory == info.requiredDisplayCategory
            ) affinity = task
        }
        return affinity
    }

    /** Task.isSameIntentFilter normalizes aliases and explicit package restrictions. */
    private fun sameTaskIntent(task: TaskRecord, intent: Intent): Boolean = Intent(intent).apply {
        component = task.baseIntent.component
        if (selector == null) setPackage(task.baseIntent.`package`)
    }.filterEquals(task.baseIntent)

    /** ActivityRecord.finishActivityResults for a task clear with no result data. */
    private fun queueCanceledActivityResultLocked(record: ActivityRecord) {
        if (record.requestCode < 0) return
        val target = record.resultTo?.let(activities::get)?.takeIf { !it.finishing } ?: return
        val uid = packages.getPackageUidInternal(record.info.packageName)
        val callerToken = registerCallerIdentityLocked(target.token, uid, record.info.packageName,
            ActivityCallerUris.capture(uriGrants(), null, uid))
        target.pendingResults += ResultInfo(record.resultWho, record.requestCode,
            Activity.RESULT_CANCELED, null, callerToken)
    }

    private fun startResolvedActivity(
        intent: Intent,
        info: ActivityInfo,
        effectiveUid: Int,
        effectivePackage: String?,
        resultTo: IBinder?,
        resultWho: String?,
        requestCode: Int,
        options: Bundle?,
        taskOverride: TaskRecord?,
        cancelIfStale: Boolean,
        onRecordSelected: ((ActivityRecord) -> Unit)?,
        neededGrants: NeededUriGrants?,
    ): Int {
        val callerUris = ActivityCallerUris.capture(uriGrants(), intent, effectiveUid)
        val shareIdentity = android.app.ActivityOptions.fromBundle(options)?.isShareIdentityEnabled == true
        val component = ComponentName(info.packageName, info.name)
        intent.component = component
        var record: ActivityRecord? = null
        var deliveredToTop: ActivityRecord? = null
        var previous: ActivityRecord? = null
        var supersededLaunch: ActivityRecord? = null
        var supersededTaskId: Int? = null
        var createdTask = false
        var reusedTop: ActivityRecord? = null
        var movedTask = false
        var resultRequest = requestCode
        var canceledResult: Pair<ActivityRecord, ResultInfo>? = null
        val cleared = ArrayList<ActivityRecord>()
        synchronized(lock) {
            val source = resultTo?.let(activities::get)
            // AOSP ActivityStarter keeps an activity that starts a child in its task. The
            // request proves that source has run, even if its oneway resume acknowledgement
            // has not reached this controller yet; it is no longer a cancelable stale launch.
            source?.cancelIfStale = false
            intent.flags = documentLaunchFlags(intent.flags, info)
            if (info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // ActivityStarter.sendNewTaskResultRequestIfNeeded runs before source-task
            // selection. Explicit NEW_TASK cancels the request rather than retaining it.
            if (source != null && resultRequest >= 0 &&
                intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
            ) {
                canceledResult = source to ResultInfo(resultWho, resultRequest,
                    Activity.RESULT_CANCELED, null, null)
                resultRequest = -1
            }
            if (intent.isDocument && resultRequest < 0) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 &&
                info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS
            ) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (taskOverride == null && (source == null ||
                    source.info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val reusable = if (taskOverride == null && resultRequest < 0) {
                findReusableTaskLocked(intent, info)
            } else null
            val task = taskOverride ?: reusable ?: if (source != null &&
                (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0
            ) {
                source.task
            } else {
                TaskRecord(nextTaskId.getAndIncrement(), taskBaseIntent(intent), info.taskAffinity,
                    UserHandle.getUserId(packages.getPackageUidInternal(info.packageName)),
                    realComponent(info), info.requiredDisplayCategory).also {
                    tasks[it.taskId] = it
                    createdTask = true
                }
            }
            previous = focusedActivityLocked()
            movedTask = previous?.task !== task
            if (reusable != null && movedTask) intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
            val singleTop = (intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0 ||
                info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP
            val documentReuse = intent.isDocument && intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK == 0
            val clearTask = intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ==
                (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val clearTop = intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0 || documentReuse ||
                info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK
            val match = task.activities.lastOrNull { !it.finishing && realComponent(it.info) == realComponent(info) }
            // Task.performClearTaskForReuse/clearTopActivities defer destruction until the
            // replacement can resume, retaining the task and canceling outstanding results.
            if (clearTask) {
                cleared += task.activities.filter { !it.finishing }
                task.baseIntent = taskBaseIntent(intent)
                task.realActivity = realComponent(info)
            } else if (clearTop && match != null) {
                cleared += task.activities.drop(task.activities.indexOf(match) + 1).filter { !it.finishing }
                if (match.info.launchMode == ActivityInfo.LAUNCH_MULTIPLE && !singleTop && !documentReuse) {
                    cleared += match
                } else {
                    deliveredToTop = match
                }
            } else if (!clearTop && intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0 && match != null) {
                task.activities.remove(match)
                task.activities += match
                deliveredToTop = match
            }
            cleared.forEach { it.finishing = true }
            for (finished in cleared) queueCanceledActivityResultLocked(finished)
            val top = task.activities.lastOrNull { !it.finishing }
            if (deliveredToTop == null && !clearTask && top != null &&
                realComponent(top.info) == realComponent(info) && singleTop &&
                (top.state != STATE_INITIALIZING || top.launchScheduled)
            ) {
                deliveredToTop = top
            }
            if (deliveredToTop == null && !clearTask && !clearTop && reusable != null &&
                top != null && task.realActivity == realComponent(info) && sameTaskIntent(task, intent)
            ) reusedTop = top
            if (deliveredToTop != null || reusedTop != null) {
                val selected = checkNotNull(deliveredToTop ?: reusedTop)
                selected.cancelIfStale = false
                if (selected !== previous) selected.previousToStop = previous?.token
                selected.destroyAfterResume.addAll(cleared.map { it.token })
                moveTaskToFrontLocked(task)
            } else {
                val focused = previous
                // A newer start can arrive while a cross-process launch is still waiting for its
                // predecessor's pause acknowledgement. The newer record supersedes that pending
                // top; continue gating on the real resumed predecessor instead of launching both.
                if (taskOverride == null && focused != null &&
                    focused !== source && focused.state == STATE_INITIALIZING &&
                    focused.previousToStop != null
                ) {
                    val predecessor = focused.previousToStop?.let(activities::get)?.takeIf {
                        !it.finishing && it.process != null
                    }
                    if (predecessor != null) {
                        previous = predecessor
                        focused.finishing = true
                        if (focused.launchScheduled) {
                            supersededLaunch = focused
                        } else {
                            removeActivityRecordLocked(focused)?.let { supersededTaskId = it }
                        }
                    }
                }
                val token = Binder()
                record = ActivityRecord(
                    token,
                    task,
                    processes.values.firstOrNull {
                        it.packageName == info.packageName &&
                            it.processName == (info.processName ?: info.packageName)
                    },
                    intent,
                    info,
                    effectiveUid,
                    effectivePackage,
                    resultTo,
                    resultWho,
                    resultRequest,
                ).also {
                    it.cancelIfStale = cancelIfStale
                    it.sceneTransitionInfo = android.app.ActivityOptions.fromBundle(options)?.sceneTransitionInfo
                }
                task.activities += checkNotNull(record)
                checkNotNull(record).previousToStop = previous?.token
                checkNotNull(record).destroyAfterResume.addAll(cleared.map { it.token })
                task.lastActiveTime = android.os.SystemClock.elapsedRealtime()
                activities[token] = checkNotNull(record)
                callerIdentities[checkNotNull(record).initialCallerInfoAccessToken] =
                    CallerIdentity(token, effectiveUid, effectivePackage, callerUris, shareIdentity)
                grantActivityUrisLocked(checkNotNull(record), neededGrants)
                moveTaskToFrontLocked(task)
            }
        }

        canceledResult?.let { (target, result) ->
            val deliver = synchronized(lock) {
                if (target.state == STATE_RESUMED) true else { target.pendingResults += result; false }
            }
            if (deliver) target.process?.let { schedule(it, ActivityResultItem(target.token, listOf(result))) }
        }

        supersededTaskId?.takeIf { taskId -> synchronized(lock) { !tasks.containsKey(taskId) } }
            ?.let(::notifyTaskRemoved)
        supersededLaunch?.let(::scheduleDestroy)
        synchronized(lock) {
            cleared.filter { it.process == null }.forEach { removeActivityRecordLocked(it) }
        }

        (deliveredToTop ?: reusedTop)?.let { top ->
            val process = top.process ?: return ActivityManager.START_CANCELED
            synchronized(lock) {
                if (activities[top.token] !== top || top.finishing) {
                    return ActivityManager.START_CANCELED
                }
                if (deliveredToTop != null) {
                    grantActivityUrisLocked(top, neededGrants)
                    val callerToken = registerCallerIdentityLocked(top.token, effectiveUid, effectivePackage, callerUris, shareIdentity)
                    top.pendingNewIntents += ReferrerIntent(intent, effectivePackage, callerToken)
                }
            }
            val resume: () -> Unit = {
                if (top === previous && top.state == STATE_RESUMED) {
                    val transaction = ClientTransaction(process.thread)
                    synchronized(lock) {
                        if (top.pendingResults.isNotEmpty()) {
                            transaction.addTransactionItem(ActivityResultItem(top.token, top.pendingResults.toList()))
                            top.pendingResults.clear()
                        }
                        if (top.pendingNewIntents.isNotEmpty()) {
                            transaction.addTransactionItem(NewIntentItem(top.token, top.pendingNewIntents.toList(), true))
                            top.pendingNewIntents.clear()
                        }
                        top.destroyAfterResume.removeAll(cleared.map { it.token }.toSet())
                    }
                    if (transaction.transactionItems.isNotEmpty()) schedule(process, transaction)
                    cleared.forEach(::scheduleDestroy)
                } else scheduleResume(top)
                Unit
            }
            previous?.takeIf { it !== top }?.let { gateAfterPause(it, it.finishing, resume) } ?: resume()
            onRecordSelected?.invoke(top)
            notifyTaskMovedToFront(top.task)
            notifyTaskStackChanged()
            return if (movedTask) ActivityManager.START_TASK_TO_FRONT else ActivityManager.START_DELIVERED_TO_TOP
        }

        val launch = checkNotNull(record)
        val accepted = ams.withApplicationThread(
            info.packageName,
            info.processName ?: info.packageName,
            false,
        ) { attached ->
            val process = ProcessRecord(
                attached.thread,
                attached.thread.asBinder(),
                attached.pid,
                attached.uid,
                attached.packageName,
                attached.processName,
            )
            synchronized(lock) {
                processes[process.binder] = process
                if (activities[launch.token] !== launch || launch.finishing) return@withApplicationThread
                launch.process = process
            }
            val launchTarget = { scheduleLaunch(launch, process); Unit }
            if (previous?.process?.binder != process.binder || previous?.finishing == true) {
                previous?.let { gateAfterPause(it, it.finishing, launchTarget) } ?: launchTarget()
            } else {
                previous?.let(::schedulePauseForLaunch)
                launchTarget()
            }
        }
        if (!accepted) {
            synchronized(lock) {
                removeActivityRecordLocked(launch)
            }
            return ActivityManager.START_CANCELED
        }
        if (createdTask) notifyTaskCreated(launch.task.taskId, component)
        onRecordSelected?.invoke(launch)
        notifyTaskMovedToFront(launch.task)
        notifyTaskStackChanged()
        return ActivityManager.START_SUCCESS
    }

    private fun scheduleLaunch(record: ActivityRecord, process: ProcessRecord) {
        var failed: RemoteException? = null
        synchronized(lock) {
            if (activities[record.token] !== record || record.finishing ||
                focusedActivityLocked() !== record
            ) return
            val results = record.pendingResults.takeIf { it.isNotEmpty() }?.toList()
            record.pendingResults.clear()
            val newIntents = record.pendingNewIntents.takeIf { it.isNotEmpty() }?.toList()
            record.pendingNewIntents.clear()
            // AOSP ActivityRecord.takeSceneTransitionInfo consumes launch options once.
            val sceneTransitionInfo = record.sceneTransitionInfo
            record.sceneTransitionInfo = null
            val transaction = ClientTransaction(process.thread)
            transaction.addTransactionItem(
                LaunchActivityItem(
                    record.token,
                    Intent(record.intent),
                    0,
                    record.info,
                    Configuration(globalConfiguration),
                    Configuration(),
                    Context.DEVICE_ID_DEFAULT,
                    record.launchedFromPackage,
                    null,
                    ActivityManager.PROCESS_STATE_TOP,
                    record.savedState,
                    record.persistentState,
                    results,
                    newIntents,
                    sceneTransitionInfo,
                    true,
                    null,
                    record.assistToken,
                    activityClientController,
                    record.shareableActivityToken,
                    false,
                    null,
                    record.initialCallerInfoAccessToken,
                    ActivityWindowInfo(),
                ),
            )
            transaction.addTransactionItem(ResumeActivityItem(record.token, true, false))
            try {
                // Keep the focus validation and the oneway launch enqueue atomic with respect to a
                // concurrent superseding start. No client callback is made synchronously here.
                process.thread.scheduleTransaction(transaction)
                record.launchScheduled = true
            } catch (e: RemoteException) {
                failed = e
            }
        }
        failed?.let {
            Log.w(TAG, "launch transaction failed for ${process.processName}", it)
            onProcessDied(process.binder)
        }
    }

    private fun schedulePauseForLaunch(record: ActivityRecord) {
        val process = record.process ?: return
        if (record.finishing || record.state == STATE_STOPPED) return
        schedule(
            process,
            PauseActivityItem(record.token, false, true, false, false),
        )
    }

    private fun gateAfterPause(
        record: ActivityRecord,
        finishing: Boolean,
        continuation: () -> Unit,
    ) {
        var process: ProcessRecord? = null
        var runNow = false
        var schedulePause = false
        synchronized(lock) {
            if (activities[record.token] !== record || record.state != STATE_RESUMED ||
                record.process == null
            ) {
                runNow = true
            } else {
                schedulePause = record.afterPause.isEmpty()
                record.afterPause += continuation
                process = record.process
            }
        }
        if (runNow) {
            continuation()
        } else if (schedulePause) {
            schedule(
                checkNotNull(process),
                PauseActivityItem(record.token, finishing, !finishing, false, false),
            )
        }
    }

    private fun scheduleBackground(record: ActivityRecord) {
        val process = record.process ?: return
        if (record.finishing || record.state == STATE_STOPPED) return
        schedule(process, StopActivityItem(record.token))
    }

    private fun scheduleResume(record: ActivityRecord): Boolean {
        val process: ProcessRecord
        val results: List<ResultInfo>
        val newIntents: List<ReferrerIntent>
        synchronized(lock) {
            if (activities[record.token] !== record || record.finishing ||
                focusedActivityLocked() !== record
            ) return false
            process = record.process ?: return false
            results = record.pendingResults.toList()
            record.pendingResults.clear()
            newIntents = record.pendingNewIntents.toList()
            record.pendingNewIntents.clear()
        }
        val transaction = ClientTransaction(process.thread)
        if (results.isNotEmpty()) {
            transaction.addTransactionItem(ActivityResultItem(record.token, results))
        }
        if (newIntents.isNotEmpty()) {
            transaction.addTransactionItem(NewIntentItem(record.token, newIntents, false))
        }
        transaction.addTransactionItem(ResumeActivityItem(record.token, false, false))
        schedule(process, transaction)
        return true
    }

    private fun schedule(process: ProcessRecord, vararg items: android.app.servertransaction.ClientTransactionItem) {
        val transaction = ClientTransaction(process.thread)
        for (item in items) transaction.addTransactionItem(item)
        schedule(process, transaction)
    }

    private fun schedule(process: ProcessRecord, transaction: ClientTransaction) {
        try {
            process.thread.scheduleTransaction(transaction)
        } catch (e: RemoteException) {
            Log.w(TAG, "transaction failed for ${process.processName}", e)
            onProcessDied(process.binder)
        }
    }

    private fun focusedActivityLocked(): ActivityRecord? {
        for (task in tasks.values.toList().asReversed()) {
            task.activities.lastOrNull { !it.finishing }?.let { return it }
        }
        return null
    }

    private fun activityForTokenLocked(token: IBinder?): ActivityRecord? =
        token?.let(activities::get)

    private fun uriGrants(): UriGrantsManagerInternal =
        checkNotNull(LocalServices.getService(UriGrantsManagerInternal::class.java))

    /** AOSP ActivityRecord.getUriPermissionsLocked/deliverNewIntentLocked/finishActivityResults.
     * The owner belongs to the server record and survives client configuration recreation. */
    private fun grantActivityUrisLocked(record: ActivityRecord, needed: NeededUriGrants?) {
        if (needed == null) return
        val grants = uriGrants()
        val owner = record.uriPermissions ?: UriPermissionOwner(grants, record).also {
            record.uriPermissions = it
        }
        grants.grantUriPermissionUncheckedFromIntent(needed, owner)
    }

    private fun removeActivityRecordLocked(record: ActivityRecord): Int? {
        if (activities.remove(record.token) !== record) return null
        // AOSP ActivityRecord.removeFromHistory: only final record removal releases grants.
        record.uriPermissions?.removeUriPermissions()
        record.uriPermissions = null
        callerIdentities.entries.removeAll { it.value.ownerActivityToken == record.token }
        for (other in activities.values) {
            if (other.previousToStop == record.token) other.previousToStop = null
            other.destroyAfterResume.remove(record.token)
        }
        record.task.activities.remove(record)
        return if (record.task.activities.isEmpty()) {
            tasks.remove(record.task.taskId)
            record.task.taskId
        } else {
            null
        }
    }

    private fun registerCallerIdentityLocked(
        ownerActivityToken: IBinder,
        uid: Int,
        packageName: String?,
        uris: ActivityCallerUris,
        shareIdentity: Boolean = false,
    ): IBinder = Binder().also {
        callerIdentities[it] = CallerIdentity(ownerActivityToken, uid, packageName, uris, shareIdentity)
    }

    private fun callerIdentityLocked(
        activityToken: IBinder?,
        callerToken: IBinder?,
    ): CallerIdentity? {
        if (activityToken == null || callerToken == null) return null
        return callerIdentities[callerToken]?.takeIf {
            it.ownerActivityToken == activityToken && activities.containsKey(activityToken)
        }
    }

    /** Pinned ActivityClientController.getUid/getPackage and canGetLaunchedFromLocked.
     * The hosted installer runs under the system UID; there is no separately designated
     * Android package-installer app. Platform certificates come from parsed package state.
     * Query compatibility outside the activity lock, as it can cross the service boundary.
     */
    private fun visibleCallerIdentity(
        activityToken: IBinder?, callerToken: IBinder?, activityCaller: Boolean,
    ): CallerIdentity? {
        val uid = Binder.getCallingUid()
        val internal = UserHandle.getAppId(uid) == android.os.Process.SYSTEM_UID ||
            packages.isPlatformSignedUid(uid)
        val sharedIdentityEnabled = internal || android.app.compat.CompatChanges.isChangeEnabled(
            259743961L /* ActivityClientController.ACCESS_SHARED_IDENTITY */, uid)
        return synchronized(lock) {
            val record = activityForTokenLocked(activityToken) ?: return@synchronized null
            val token = if (activityCaller) callerToken else record.initialCallerInfoAccessToken
            val identity = callerIdentityLocked(activityToken, token) ?: return@synchronized null
            identity.takeIf { internal || (sharedIdentityEnabled && (it.shareIdentity || it.uid == uid)) }
        }
    }

    private fun hasHostedPermission(permission: String): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.ROOT_UID) return true
        // Do not make a nested Context -> ActivityManager permission IPC while serving an ATMS
        // call. The hosted package service is the session's authoritative grant map.
        return packages.checkUidPermission(permission, uid) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enforceHostedPermission(permission: String, operation: String) {
        if (!hasHostedPermission(permission)) {
            throw SecurityException(
                "Permission Denial: $operation from pid=${Binder.getCallingPid()}, " +
                    "uid=${Binder.getCallingUid()} requires $permission",
            )
        }
    }

    private fun enforceTaskPermission(operation: String) {
        if (hasHostedPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS) ||
            hasHostedPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        ) return
        throw SecurityException(
            "Permission Denial: $operation requires android.permission.MANAGE_ACTIVITY_TASKS",
        )
    }

    private fun canSeeAllTasks(): Boolean =
        hasHostedPermission(android.Manifest.permission.REAL_GET_TASKS) ||
            hasHostedPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)

    private fun moveTaskToFrontLocked(task: TaskRecord) {
        tasks.remove(task.taskId)
        tasks[task.taskId] = task
        task.lastActiveTime = android.os.SystemClock.elapsedRealtime()
    }

    override fun removeTask(taskId: Int): Boolean {
        enforceHostedPermission(android.Manifest.permission.REMOVE_TASKS, "removeTask()")
        return removeTaskInternal(taskId)
    }

    private fun removeTaskInternal(taskId: Int): Boolean {
        val token = synchronized(lock) {
            tasks[taskId]?.activities?.lastOrNull { !it.finishing }?.token
        } ?: return false
        val removed = finishActivityRecord(
            token,
            Activity.RESULT_CANCELED,
            null,
            FINISH_TASK_WITH_ACTIVITY,
        )
        if (removed) synchronized(lock) { tasks[taskId]?.inRecents = false }
        return removed
    }

    override fun unhandledBack() {
        enforceHostedPermission(android.Manifest.permission.FORCE_BACK, "unhandledBack()")
        val token = synchronized(lock) { focusedActivityLocked()?.token } ?: return
        finishActivityRecord(
            token,
            Activity.RESULT_CANCELED,
            null,
            DONT_FINISH_TASK_WITH_ACTIVITY,
        )
    }

    override fun startActivityFromRecents(taskId: Int, options: Bundle?): Int {
        enforceHostedPermission(
            android.Manifest.permission.START_TASKS_FROM_RECENTS,
            "startActivityFromRecents()",
        )
        return startActivityFromRecentsInternal(taskId)
    }

    private fun startActivityFromRecentsInternal(taskId: Int): Int {
        var previous: ActivityRecord? = null
        var target: ActivityRecord? = null
        val task = synchronized(lock) {
            val value = tasks[taskId] ?: return ActivityManager.START_TASK_TO_FRONT
            previous = focusedActivityLocked()
            moveTaskToFrontLocked(value)
            target = value.activities.lastOrNull { !it.finishing }
            if (target !== previous) target?.previousToStop = previous?.token
            value
        }
        target?.takeIf { it !== previous || it.state != STATE_RESUMED }?.let { record ->
            val resume: () -> Unit = { scheduleResume(record); Unit }
            previous?.takeIf { it !== record }?.let { gateAfterPause(it, false, resume) }
                ?: resume()
        }
        notifyTaskMovedToFront(task)
        notifyTaskStackChanged()
        return ActivityManager.START_TASK_TO_FRONT
    }

    override fun moveTaskToFront(
        app: IApplicationThread?,
        callingPackage: String?,
        task: Int,
        flags: Int,
        options: Bundle?,
    ) {
        enforceHostedPermission(android.Manifest.permission.REORDER_TASKS, "moveTaskToFront()")
        if (callingPackage != null && !packages.isSameAppInternal(callingPackage, Binder.getCallingUid())) {
            throw SecurityException("uid ${Binder.getCallingUid()} does not own $callingPackage")
        }
        startActivityFromRecentsInternal(task)
    }

    /** RecentTasks.getAppTasksList: handles are scoped to the requesting uid and base package. */
    override fun getAppTasks(callingPackage: String?): MutableList<IBinder> {
        val uid = Binder.getCallingUid()
        assertCallingPackage(callingPackage)
        return synchronized(lock) {
            tasks.values.toList().asReversed().filter { task ->
                task.inRecents && task.baseIntent.component?.packageName == callingPackage &&
                    task.activities.firstOrNull { !it.finishing }?.let {
                        packages.getPackageUidInternal(it.info.packageName) == uid
                    } == true
            }.mapTo(ArrayList()) { HostedAppTask(it.taskId, uid).asBinder() }
        }
    }

    private fun assertCallingPackage(callingPackage: String?) {
        if (callingPackage == null || !packages.isSameAppInternal(callingPackage, Binder.getCallingUid())) {
            throw SecurityException("uid ${Binder.getCallingUid()} does not own $callingPackage")
        }
    }

    private fun recentTaskInfoLocked(task: TaskRecord): ActivityManager.RecentTaskInfo =
        ActivityManager.RecentTaskInfo().also {
            fillTaskInfoLocked(task, it, true)
            it.id = task.taskId
            it.persistentId = task.taskId
        }

    /** AppTaskImpl control flow; the hosted task owner emits the lifecycle transactions. */
    private inner class HostedAppTask(private val taskId: Int, private val ownerUid: Int) :
        android.app.IAppTask.Stub() {
        private fun checkCaller() {
            val caller = Binder.getCallingUid()
            if (caller != ownerUid && caller != android.os.Process.SYSTEM_UID && caller != android.os.Process.ROOT_UID) {
                throw SecurityException("Caller $caller does not match caller of getAppTasks(): $ownerUid")
            }
        }

        private fun taskLocked(): TaskRecord = tasks[taskId]
            ?: throw IllegalArgumentException("Unable to find task ID $taskId")

        override fun getTaskInfo(): ActivityManager.RecentTaskInfo {
            checkCaller()
            return synchronized(lock) { recentTaskInfoLocked(taskLocked()) }
        }

        override fun finishAndRemoveTask() {
            checkCaller()
            if (!removeTaskInternal(taskId)) throw IllegalArgumentException("Unable to find task ID $taskId")
        }

        override fun moveToFront(appThread: IApplicationThread?, callingPackage: String?) {
            checkCaller()
            assertCallingPackage(callingPackage)
            synchronized(lock) { taskLocked() }
            startActivityFromRecentsInternal(taskId)
        }

        override fun startActivity(
            whoThread: IBinder?, callingPackage: String?, callingFeatureId: String?,
            intent: Intent?, resolvedType: String?, options: Bundle?,
        ): Int {
            checkCaller()
            assertCallingPackage(callingPackage)
            val task = synchronized(lock) { taskLocked() }
            val appThread = IApplicationThread.Stub.asInterface(whoThread)
                ?: throw IllegalArgumentException("Bad app thread null")
            return startActivityInternal(appThread, callingPackage, Binder.getCallingUid(), intent,
                resolvedType, null, null, -1, options, taskOverride = task)
        }

        override fun setExcludeFromRecents(exclude: Boolean) {
            checkCaller()
            synchronized(lock) {
                val intent = taskLocked().baseIntent
                intent.flags = if (exclude) intent.flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    else intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()
            }
        }
    }

    // RecentTasks.isInVisibleRange keeps an excluded task while its activity is visible.
    // Hosted lifecycle state supplies visibility until server WindowStates are available.
    private fun visibleInRecentsLocked(task: TaskRecord): Boolean =
        task.inRecents && (task.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0 ||
            task.activities.any { !it.finishing && (it.state == STATE_RESUMED || it.state == STATE_PAUSED) })

    override fun getTasks(
        maxNum: Int,
        filterOnlyVisibleRecents: Boolean,
        keepIntentExtra: Boolean,
        displayId: Int,
    ): MutableList<ActivityManager.RunningTaskInfo> {
        if (displayId != Display.INVALID_DISPLAY && displayId != Display.DEFAULT_DISPLAY) {
            return ArrayList()
        }
        val callingUid = Binder.getCallingUid()
        val allowed = canSeeAllTasks()
        return synchronized(lock) {
            tasks.values.toList().asReversed().asSequence()
                .filter { task ->
                    allowed || task.activities.lastOrNull { !it.finishing }?.let {
                        packages.getPackageUidInternal(it.info.packageName) == callingUid
                    } == true
                }
                .filter { !filterOnlyVisibleRecents || visibleInRecentsLocked(it) }
                .take(maxNum.coerceAtLeast(0))
                .mapTo(ArrayList()) { runningTaskInfoLocked(it, keepIntentExtra) }
        }
    }

    override fun getRecentTasks(
        maxNum: Int,
        flags: Int,
        userId: Int,
    ): ParceledListSlice<ActivityManager.RecentTaskInfo> {
        if (userId != UserHandle.USER_SYSTEM && userId != UserHandle.USER_CURRENT) {
            return ParceledListSlice.emptyList()
        }
        val callingUid = Binder.getCallingUid()
        val allowed = canSeeAllTasks()
        return synchronized(lock) {
        val result = tasks.values.toList().asReversed().asSequence().filter { task ->
            allowed || task.activities.lastOrNull { !it.finishing }?.let {
                packages.getPackageUidInternal(it.info.packageName) == callingUid
            } == true
        }.filter { it.inRecents && (flags and ActivityManager.RECENT_WITH_EXCLUDED != 0 || visibleInRecentsLocked(it)) }
        .take(maxNum.coerceAtLeast(0)).map { task ->
            recentTaskInfoLocked(task)
        }.toList()
            ParceledListSlice(result)
        }
    }

    override fun getFocusedRootTaskInfo(): ActivityTaskManager.RootTaskInfo? {
        enforceTaskPermission("getFocusedRootTaskInfo()")
        return synchronized(lock) {
            tasks.values.lastOrNull()?.let(::rootTaskInfoLocked)
        }
    }

    override fun getAllRootTaskInfos(): MutableList<ActivityTaskManager.RootTaskInfo> {
        enforceTaskPermission("getAllRootTaskInfos()")
        return synchronized(lock) { tasks.values.mapTo(ArrayList(), ::rootTaskInfoLocked) }
    }

    override fun getAllRootTaskInfosOnDisplay(
        displayId: Int,
    ): MutableList<ActivityTaskManager.RootTaskInfo> {
        enforceTaskPermission("getAllRootTaskInfosOnDisplay()")
        return if (displayId == Display.DEFAULT_DISPLAY) {
            synchronized(lock) { tasks.values.mapTo(ArrayList(), ::rootTaskInfoLocked) }
        } else {
            ArrayList()
        }
    }

    override fun getRootTaskInfo(windowingMode: Int, activityType: Int): ActivityTaskManager.RootTaskInfo? {
        enforceTaskPermission("getRootTaskInfo()")
        if (!matchesHostedRoot(windowingMode, activityType)) return null
        return synchronized(lock) { tasks.values.lastOrNull()?.let(::rootTaskInfoLocked) }
    }

    override fun getRootTaskInfoOnDisplay(
        windowingMode: Int,
        activityType: Int,
        displayId: Int,
    ): ActivityTaskManager.RootTaskInfo? {
        enforceTaskPermission("getRootTaskInfoOnDisplay()")
        return if (displayId == Display.DEFAULT_DISPLAY &&
            matchesHostedRoot(windowingMode, activityType)
        ) {
            synchronized(lock) { tasks.values.lastOrNull()?.let(::rootTaskInfoLocked) }
        } else {
            null
        }
    }

    override fun getTaskBounds(taskId: Int): Rect {
        enforceTaskPermission("getTaskBounds()")
        return synchronized(lock) {
            tasks[taskId]?.let { Rect(globalConfiguration.windowConfiguration.bounds) } ?: Rect()
        }
    }

    override fun getLastResumedActivityUserId(): Int = UserHandle.USER_SYSTEM

    override fun updateConfiguration(values: Configuration?): Boolean {
        enforceHostedPermission(
            android.Manifest.permission.CHANGE_CONFIGURATION,
            "updateConfiguration()",
        )
        return updateConfigurationFromHost(values)
    }

    /** Trusted compositor/session path; Binder callers use the permission-checked entry above. */
    internal fun updateConfigurationFromHost(values: Configuration?): Boolean {
        val value = values ?: return false
        var changes = 0
        var current = Configuration()
        val dispatches = synchronized(lock) {
            val next = Configuration(globalConfiguration)
            changes = next.updateFrom(value)
            if (changes == 0) return@synchronized emptyList()
            configurationSeq = maxOf(configurationSeq + 1, 1)
            next.seq = configurationSeq
            globalConfiguration.setTo(next)
            current = Configuration(next)
            activities.values.filter { !it.finishing }.map { record ->
                val handled = record.info.realConfigChanged and changes == changes
                val priorState = record.state
                if (!handled) {
                    // ActivityRecord.startRelaunching: server-owned relaunches retain the prior
                    // state until activityRelaunched, rather than using the local-relaunch IPC.
                    record.stateBeforeRelaunch = priorState
                    record.state = STATE_INITIALIZING
                }
                ConfigurationDispatch(record, handled, priorState)
            }
        }
        if (changes == 0) return false
        // The process-wide item updates process/Application Resources. Transaction-launched
        // activities also need their token-scoped callback or relaunch transaction explicitly.
        ams.updateGlobalConfiguration(current)
        for ((record, handled, priorState) in dispatches) {
            val process = record.process ?: continue
            if (handled) {
                schedule(
                    process,
                    ActivityConfigurationChangeItem(
                        record.token,
                        Configuration().apply { seq = current.seq },
                        ActivityWindowInfo(),
                    ),
                )
            } else {
                val transaction = ClientTransaction(process.thread)
                transaction.addTransactionItem(
                    ActivityRelaunchItem(
                        record.token,
                        null,
                        null,
                        changes,
                        MergedConfiguration(current, Configuration()),
                        false,
                        ActivityWindowInfo(),
                    ),
                )
                // ActivityRecord.relaunchActivityLocked pairs a server relaunch with Resume when
                // it was resumed and Pause otherwise, preserving the lifecycle state contract.
                transaction.addTransactionItem(
                    if (priorState == STATE_RESUMED) {
                        ResumeActivityItem(record.token, false, false)
                    } else {
                        PauseActivityItem(record.token)
                    },
                )
                schedule(process, transaction)
            }
        }
        return true
    }

    override fun registerTaskStackListener(listener: ITaskStackListener?) {
        enforceTaskPermission("registerTaskStackListener()")
        listener?.let(listeners::register)
    }

    override fun unregisterTaskStackListener(listener: ITaskStackListener?) {
        enforceTaskPermission("unregisterTaskStackListener()")
        listener?.let(listeners::unregister)
    }

    private fun runningTaskInfoLocked(
        task: TaskRecord,
        keepIntentExtra: Boolean,
    ): ActivityManager.RunningTaskInfo = ActivityManager.RunningTaskInfo().also {
        fillTaskInfoLocked(task, it, keepIntentExtra)
        it.id = task.taskId
    }

    private fun rootTaskInfoLocked(task: TaskRecord): ActivityTaskManager.RootTaskInfo =
        ActivityTaskManager.RootTaskInfo().also {
            fillTaskInfoLocked(task, it, true)
            it.bounds = Rect(globalConfiguration.windowConfiguration.bounds)
            it.childTaskIds = intArrayOf(task.taskId)
            it.childTaskNames = arrayOf(
                task.activities.lastOrNull()?.intent?.component?.flattenToShortString() ?: "",
            )
            it.childTaskBounds = arrayOf(Rect(it.bounds))
            it.childTaskUserIds = intArrayOf(UserHandle.USER_SYSTEM)
            it.visible = task === tasks.values.lastOrNull()
            it.position = tasks.keys.indexOf(task.taskId)
        }

    private fun fillTaskInfoLocked(task: TaskRecord, info: android.app.TaskInfo, keepExtras: Boolean) {
        val top = task.activities.lastOrNull { !it.finishing }
        info.userId = task.userId
        info.taskId = task.taskId
        info.effectiveUid = task.activities.firstOrNull { !it.finishing }?.let { packages.getPackageUidInternal(it.info.packageName) } ?: -1
        info.displayId = Display.DEFAULT_DISPLAY
        info.isRunning = top != null
        info.baseIntent = Intent(task.baseIntent).also { if (!keepExtras) it.replaceExtras(null as Bundle?) }
        info.baseActivity = task.activities.firstOrNull { !it.finishing }?.intent?.component
        info.topActivity = top?.intent?.component
        info.origActivity = info.baseActivity
        info.realActivity = task.realActivity
        info.numActivities = task.activities.count { !it.finishing }
        info.lastActiveTime = task.lastActiveTime
        info.taskDescription = task.description
        info.supportsMultiWindow = top?.info?.resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE
        info.resizeMode = top?.info?.resizeMode ?: ActivityInfo.RESIZE_MODE_UNRESIZEABLE
        info.configuration.setTo(globalConfiguration)
        info.configuration.windowConfiguration.windowingMode =
            android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
        info.configuration.windowConfiguration.activityType =
            android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
        info.token = task.token
        info.topActivityType = android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
        info.topActivityInfo = top?.info
        info.isResizeable = info.supportsMultiWindow
        info.parentTaskId = ActivityTaskManager.INVALID_TASK_ID
        info.isFocused = task === tasks.values.lastOrNull()
        info.isVisible = info.isFocused
        info.isVisibleRequested = info.isFocused
        info.positionInParent = android.graphics.Point()
    }

    private fun matchesHostedRoot(windowingMode: Int, activityType: Int): Boolean =
        (windowingMode == android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED ||
            windowingMode == android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN) &&
            (activityType == android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED ||
                activityType == android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD)

    private inline fun notifyListeners(block: (ITaskStackListener) -> Unit) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (index in 0 until count) {
                    try {
                        block(listeners.getBroadcastItem(index))
                    } catch (_: RemoteException) {
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun notifyTaskCreated(taskId: Int, component: ComponentName) = notifyListeners {
        it.onTaskCreated(taskId, component)
    }

    private fun notifyTaskRemoved(taskId: Int) = notifyListeners { it.onTaskRemoved(taskId) }

    private fun notifyTaskMovedToFront(task: TaskRecord) {
        val info = synchronized(lock) { runningTaskInfoLocked(task, true) }
        notifyListeners { it.onTaskMovedToFront(info) }
    }

    private fun notifyTaskStackChanged() = notifyListeners { it.onTaskStackChanged() }

    private inner class HostedActivityClientController :
        IActivityClientController.Stub(),
        IActivityClientController by unsupported<IActivityClientController>() {
        override fun asBinder(): IBinder = this

        override fun activityResumed(token: IBinder?, handleSplashScreenExit: Boolean) {
            var previous: ActivityRecord? = null
            val destroy = ArrayList<ActivityRecord>()
            var process: ProcessRecord? = null
            val pendingResults = ArrayList<ResultInfo>()
            var accepted = false
            var staleToBackground: ActivityRecord? = null
            synchronized(lock) {
                val record = activityForTokenLocked(token) ?: return
                // Ignore a late ResumeActivityItem after the server focus already changed or
                // finish began; it must not resurrect or reorder the stale record.
                if (record.finishing || focusedActivityLocked() !== record) {
                    if (!record.finishing && record.state == STATE_INITIALIZING &&
                        record.cancelIfStale
                    ) {
                        record.finishing = true
                        destroy += record
                    } else if (!record.finishing && record.state == STATE_INITIALIZING) {
                        record.state = STATE_RESUMED
                        staleToBackground = record
                    }
                    for (destroyToken in record.destroyAfterResume) {
                        activityForTokenLocked(destroyToken)?.let(destroy::add)
                    }
                    record.destroyAfterResume.clear()
                    return@synchronized
                }
                accepted = true
                record.state = STATE_RESUMED
                moveTaskToFrontLocked(record.task)
                process = record.process
                pendingResults.addAll(record.pendingResults)
                record.pendingResults.clear()
                previous = record.previousToStop?.let(activities::get)
                record.previousToStop = null
                for (destroyToken in record.destroyAfterResume) {
                    activityForTokenLocked(destroyToken)?.let(destroy::add)
                }
                record.destroyAfterResume.clear()
            }
            if (!accepted) {
                staleToBackground?.let(::scheduleBackground)
                for (record in destroy) scheduleDestroy(record)
                return
            }
            if (pendingResults.isNotEmpty()) {
                process?.let {
                    schedule(it, ActivityResultItem(checkNotNull(token), pendingResults))
                }
            }
            previous?.let(::scheduleBackground)
            for (record in destroy) scheduleDestroy(record)
        }

        override fun activityIdle(token: IBinder?, config: Configuration?, stopProfiling: Boolean) {
            synchronized(lock) {
                activityForTokenLocked(token)?.takeIf {
                    !it.finishing && focusedActivityLocked() === it
                }?.state = STATE_RESUMED
            }
        }

        override fun activityRefreshed(token: IBinder?) = Unit

        override fun activityTopResumedStateLost() = Unit

        override fun activityPaused(token: IBinder?) {
            val continuations = ArrayList<() -> Unit>()
            synchronized(lock) {
                val record = activityForTokenLocked(token) ?: return
                record.state = STATE_PAUSED
                continuations.addAll(record.afterPause)
                record.afterPause.clear()
            }
            continuations.forEach { it() }
        }

        override fun activityStopped(
            token: IBinder?,
            state: Bundle?,
            persistentState: PersistableBundle?,
            description: CharSequence?,
        ) {
            synchronized(lock) {
                val record = activityForTokenLocked(token) ?: return
                record.state = STATE_STOPPED
                record.savedState = state?.let(::Bundle)
                record.persistentState = persistentState?.let(::PersistableBundle)
                record.description = description
            }
        }

        override fun activityDestroyed(token: IBinder?) {
            val taskRemoved: Int?
            synchronized(lock) {
                val record = activityForTokenLocked(token) ?: return
                if (!record.finishing) return
                taskRemoved = removeActivityRecordLocked(record)
            }
            taskRemoved?.let(::notifyTaskRemoved)
            notifyTaskStackChanged()
        }

        override fun activityLocalRelaunch(token: IBinder?) {
            synchronized(lock) {
                activityForTokenLocked(token)?.let {
                    it.stateBeforeRelaunch = it.state
                    it.state = STATE_INITIALIZING
                }
            }
        }

        override fun activityRelaunched(token: IBinder?) {
            synchronized(lock) {
                activityForTokenLocked(token)?.let { it.state = it.stateBeforeRelaunch }
            }
        }

        override fun finishActivity(
            token: IBinder?,
            code: Int,
            data: Intent?,
            finishTask: Int,
        ): Boolean = finishActivityRecord(token, code, data, finishTask)

        override fun finishActivityAffinity(token: IBinder?): Boolean =
            finishActivityRecord(token, Activity.RESULT_CANCELED, null,
                DONT_FINISH_TASK_WITH_ACTIVITY, finishAffinity = true)

        override fun finishSubActivity(token: IBinder?, resultWho: String?, requestCode: Int) {
            val children = synchronized(lock) {
                activities.values.filter {
                    it.resultTo == token && it.resultWho == resultWho && it.requestCode == requestCode
                }.map { it.token }
            }
            for (child in children) {
                finishActivityRecord(
                    child,
                    Activity.RESULT_CANCELED,
                    null,
                    DONT_FINISH_TASK_WITH_ACTIVITY,
                )
            }
        }

        override fun releaseActivityInstance(token: IBinder?): Boolean {
            // Releasing a stopped client instance requires server-side relaunch-on-demand state.
            // Until that bounded state is hosted, report that no instance was released.
            return false
        }

        override fun shouldUpRecreateTask(token: IBinder?, destAffinity: String?): Boolean = false

        override fun navigateUpTo(
            token: IBinder?,
            target: Intent?,
            resolvedType: String?,
            resultCode: Int,
            resultData: Intent?,
        ): Boolean {
            val record = synchronized(lock) { activityForTokenLocked(token) } ?: return false
            val launch = startActivityInternal(
                record.process?.thread,
                record.info.packageName,
                record.process?.uid ?: record.launchedFromUid,
                target,
                resolvedType,
                record.resultTo,
                record.resultWho,
                record.requestCode,
                null,
            )
            if (ActivityManager.isStartResultFatalError(launch)) return false
            return finishActivityRecord(
                token,
                resultCode,
                resultData,
                DONT_FINISH_TASK_WITH_ACTIVITY,
            )
        }

        override fun isTopOfTask(token: IBinder?): Boolean = synchronized(lock) {
            activityForTokenLocked(token)?.let {
                it.task.activities.lastOrNull { item -> !item.finishing } === it
            }
                ?: false
        }

        override fun willActivityBeVisible(token: IBinder?): Boolean = synchronized(lock) {
            activityForTokenLocked(token)?.let {
                !it.finishing && it.task === tasks.values.lastOrNull()
            } ?: false
        }

        override fun getDisplayId(activityToken: IBinder?): Int = Display.DEFAULT_DISPLAY

        override fun getTaskForActivity(token: IBinder?, onlyRoot: Boolean): Int = synchronized(lock) {
            val record = activityForTokenLocked(token)
                ?: return@synchronized ActivityTaskManager.INVALID_TASK_ID
            if (onlyRoot && record.task.activities.firstOrNull { !it.finishing } !== record) {
                ActivityTaskManager.INVALID_TASK_ID
            } else {
                record.task.taskId
            }
        }

        override fun getTaskConfiguration(activityToken: IBinder?): Configuration =
            synchronized(lock) { Configuration(globalConfiguration) }

        override fun getActivityTokenBelow(token: IBinder?): IBinder? = synchronized(lock) {
            val record = activityForTokenLocked(token) ?: return@synchronized null
            val index = record.task.activities.indexOf(record)
            if (index > 0) record.task.activities[index - 1].token else null
        }

        override fun getCallingActivity(token: IBinder?): ComponentName? = synchronized(lock) {
            activityForTokenLocked(token)?.takeIf { it.requestCode >= 0 }
                ?.resultTo?.let(activities::get)?.intent?.component
        }

        override fun getCallingPackage(token: IBinder?): String? = synchronized(lock) {
            activityForTokenLocked(token)?.takeIf { it.requestCode >= 0 }
                ?.resultTo?.let(activities::get)?.info?.packageName
        }

        override fun getLaunchedFromUid(token: IBinder?): Int =
            visibleCallerIdentity(token, null, false)?.uid ?: android.os.Process.INVALID_UID

        override fun getLaunchedFromPackage(token: IBinder?): String? =
            visibleCallerIdentity(token, null, false)?.packageName

        override fun getActivityCallerUid(activityToken: IBinder?, callerToken: IBinder?): Int =
            visibleCallerIdentity(activityToken, callerToken, true)?.uid ?: android.os.Process.INVALID_UID

        override fun getActivityCallerPackage(activityToken: IBinder?, callerToken: IBinder?): String? =
            visibleCallerIdentity(activityToken, callerToken, true)?.packageName

        override fun checkActivityCallerContentUriPermission(
            activityToken: IBinder?,
            callerToken: IBinder?,
            uri: android.net.Uri?,
            modeFlags: Int,
            userId: Int,
        ): Int {
            // AOSP ActivityClientController checks the receiver's current full access before
            // consulting the caller snapshot. Keep provider resolution outside the activity lock.
            val grant = GrantUri(userId, uri, modeFlags)
            if (!uriGrants().checkUriPermission(grant, Binder.getCallingUid(), modeFlags, true)) {
                throw SecurityException("You don't have access to the content URI, hence can't " +
                    "check if the caller has access to it: $uri")
            }
            return synchronized(lock) {
                if (activityForTokenLocked(activityToken) == null) return@synchronized PackageManager.PERMISSION_DENIED
                require(Intent.isAccessUriMode(modeFlags)) {
                    "Mode flags are not access URI mode flags: $modeFlags"
                }
                if (callerIdentityLocked(activityToken, callerToken)?.uris?.check(grant, modeFlags) == true)
                    PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }

        override fun setRequestedOrientation(token: IBinder?, requestedOrientation: Int) {
            synchronized(lock) {
                activityForTokenLocked(token)?.requestedOrientation = requestedOrientation
            }
        }

        override fun getRequestedOrientation(token: IBinder?): Int = synchronized(lock) {
            activityForTokenLocked(token)?.requestedOrientation
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        override fun isImmersive(token: IBinder?): Boolean = synchronized(lock) {
            activityForTokenLocked(token)?.immersive ?: false
        }

        override fun setImmersive(token: IBinder?, immersive: Boolean) {
            synchronized(lock) { activityForTokenLocked(token)?.immersive = immersive }
        }

        override fun setTaskDescription(token: IBinder?, values: ActivityManager.TaskDescription?) {
            val task: TaskRecord
            synchronized(lock) {
                task = activityForTokenLocked(token)?.task ?: return
                task.description = values
            }
            val info = synchronized(lock) { runningTaskInfoLocked(task, true) }
            notifyListeners { it.onTaskDescriptionChanged(info) }
        }

        override fun reportActivityFullyDrawn(token: IBinder?, restoredFromBundle: Boolean) = Unit

        override fun isRootVoiceInteraction(token: IBinder?): Boolean = false

        override fun moveActivityTaskToBack(token: IBinder?, nonRoot: Boolean): Boolean {
            val task: TaskRecord
            val moving: ActivityRecord?
            val reveal: ActivityRecord?
            synchronized(lock) {
                val record = activityForTokenLocked(token) ?: return false
                if (!nonRoot && record.task.activities.firstOrNull { !it.finishing } !== record) return false
                task = record.task
                moving = task.activities.lastOrNull { !it.finishing }
                tasks.remove(task.taskId)
                val reordered = LinkedHashMap<Int, TaskRecord>()
                reordered[task.taskId] = task
                reordered.putAll(tasks)
                tasks.clear()
                tasks.putAll(reordered)
                reveal = focusedActivityLocked()
                if (reveal !== moving) reveal?.previousToStop = moving?.token
            }
            reveal?.let { target ->
                val resume: () -> Unit = { scheduleResume(target); Unit }
                moving?.takeIf { it !== target }?.let { gateAfterPause(it, false, resume) }
                    ?: resume()
            } ?: moving?.let(::scheduleBackground)
            notifyTaskStackChanged()
            return true
        }

        override fun onBackPressed(
            activityToken: IBinder?,
            callback: android.app.IRequestFinishCallback?,
        ) {
            callback?.requestFinish()
        }
    }

    private fun finishActivityRecord(
        token: IBinder?,
        resultCode: Int,
        resultData: Intent?,
        finishTask: Int,
        finishAffinity: Boolean = false,
    ): Boolean {
        if (resultData?.hasFileDescriptors() == true) {
            throw IllegalArgumentException("File descriptors passed in Intent")
        }
        // AOSP ActivityClientController.finishActivity/ATMS.collectGrants validate the
        // result under the Binder caller's identity before acquiring the activity lock.
        val source = synchronized(lock) { activityForTokenLocked(token) } ?: return false
        val destination = synchronized(lock) { source.resultTo?.let(activities::get) }
        val resultGrants = destination?.let {
            uriGrants().checkGrantUriPermissionFromIntent(resultData, Binder.getCallingUid(),
                it.info.packageName,
                UserHandle.getUserId(packages.getPackageUidInternal(it.info.packageName)))
        }
        val callerUris = ActivityCallerUris.capture(uriGrants(), resultData,
            packages.getPackageUidInternal(source.info.packageName))
        val finishing: List<ActivityRecord>
        val reveal: ActivityRecord?
        val removedTasks = ArrayList<Int>()
        synchronized(lock) {
            val record = activityForTokenLocked(token) ?: return false
            if (record !== source || (record.finishing && !finishAffinity)) return false
            val finishWholeTask = finishTask == FINISH_TASK_WITH_ACTIVITY ||
                (finishTask == FINISH_TASK_WITH_ROOT_ACTIVITY &&
                    record.task.activities.firstOrNull { !it.finishing } === record)
            // ActivityClientController.finishActivityAffinity walks downward from the
            // caller, stopping at the first different affinity (including null equality).
            // Select and mark the whole range before choosing the revealed activity.
            finishing = when {
                finishAffinity -> record.task.activities
                    .subList(0, record.task.activities.indexOf(record) + 1).asReversed()
                    .takeWhile { it.info.taskAffinity == record.info.taskAffinity }
                    .filter { !it.finishing }
                finishWholeTask -> record.task.activities.filter { !it.finishing }
                else -> listOf(record)
            }
            finishing.forEach { it.finishing = true }
            finishing.filter { it !== record }.forEach(::queueCanceledActivityResultLocked)
            if (record in finishing && record.requestCode >= 0) {
                val resultTarget = record.resultTo?.let(activities::get)?.takeIf {
                    it !in finishing && !it.finishing && it.process != null
                }
                resultTarget?.let { target ->
                    if (target === destination) grantActivityUrisLocked(target, resultGrants)
                    val sourceUserId = UserHandle.getUserId(
                        packages.getPackageUidInternal(record.info.packageName))
                    if (sourceUserId != UserHandle.getUserId(
                            packages.getPackageUidInternal(target.info.packageName))) {
                        resultData?.prepareToLeaveUser(sourceUserId)
                    }
                    val callerToken = registerCallerIdentityLocked(
                        target.token,
                        packages.getPackageUidInternal(record.info.packageName),
                        record.info.packageName,
                        callerUris,
                    )
                    target.pendingResults += ResultInfo(
                        record.resultWho,
                        record.requestCode,
                        resultCode,
                        resultData,
                        callerToken,
                    )
                }
            }
            reveal = focusedActivityLocked()?.takeIf { it.process != null }
            // There is no client transaction/callback that can complete teardown for a record
            // whose process is gone. Remove it synchronously rather than retaining a zombie.
            finishing.filter { it.process == null }.forEach { orphan ->
                removeActivityRecordLocked(orphan)?.let(removedTasks::add)
            }
        }
        for (taskId in removedTasks.distinct()) notifyTaskRemoved(taskId)
        if (finishing.any { it.process == null }) notifyTaskStackChanged()
        val liveFinishing = finishing.filter { it.process != null }
        // ActivityRecord.finishActivityResults queues results independently of
        // which activity becomes visible. A covered recipient receives them on
        // its own next resume; finishing this child must reveal the actual top.
        val complete = { completeFinish(liveFinishing, reveal) }
        val pausing = liveFinishing.firstOrNull { it.state == STATE_RESUMED }
        if (pausing != null) gateAfterPause(pausing, true, complete) else complete()
        return true
    }

    private fun completeFinish(
        requestedFinishing: List<ActivityRecord>,
        requestedResumeTarget: ActivityRecord?,
    ) {
        val finishing = synchronized(lock) {
            requestedFinishing.filter { activities[it.token] === it && it.finishing }
        }
        val resumeTarget = synchronized(lock) {
            requestedResumeTarget?.takeIf {
                activities[it.token] === it && !it.finishing && it.process != null
            }?.also { moveTaskToFrontLocked(it.task) }
        }
        if (resumeTarget == null) {
            finishing.forEach(::scheduleDestroy)
            return
        }
        if (resumeTarget.state == STATE_RESUMED) {
            val results = synchronized(lock) {
                resumeTarget.pendingResults.toList().also { resumeTarget.pendingResults.clear() }
            }
            if (results.isNotEmpty()) resumeTarget.process?.let {
                schedule(it, ActivityResultItem(resumeTarget.token, results))
            }
            finishing.forEach(::scheduleDestroy)
            return
        }
        synchronized(lock) {
            resumeTarget.destroyAfterResume.addAll(finishing.map { it.token })
        }
        if (!scheduleResume(resumeTarget)) {
            finishing.forEach(::scheduleDestroy)
        }
    }

    private fun scheduleDestroy(record: ActivityRecord) {
        record.process?.let { schedule(it, DestroyActivityItem(record.token, true)) }
    }
}
