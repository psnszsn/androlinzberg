package dev.artzig.systemtest

import android.app.Activity
import android.app.ComponentCaller
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** These activities query the granted provider inside the public delivery callbacks. */
open class UriGrantActivity : Activity() {
    private var id = 0
    private lateinit var observation: Bundle

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        id = intent.getIntExtra("id", -1)
        observation = ActivityUriGrantSystemSmoke.observations.getOrPut(id) { Bundle() }
        observation.putInt("creates", observation.getInt("creates") + 1)
        ActivityUriGrantSystemSmoke.activities[id] = this
        verifyUris(intent)
        rememberCaller("initial", initialCaller)
    }

    override fun onResume() {
        super.onResume()
        observation.putBoolean("resumed", true)
        recordCallback("resume")
    }

    override fun onPause() {
        super.onPause()
        observation.putBoolean("resumed", false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recordCallback("new-intent")
        verifyUris(intent)
        observation.putInt("newIntents", observation.getInt("newIntents") + 1)
        if (android.security.Flags.contentUriPermissionApis()) {
            rememberCaller("new", currentCaller)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data?.let(::verifyUris)
        recordCallback("result:$requestCode")
        observation.putInt("resultRequest", requestCode)
        observation.putInt("resultCode", resultCode)
        observation.putString("resultUri", data?.dataString)
        if (android.security.Flags.contentUriPermissionApis()) rememberCaller("result", currentCaller)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityUriGrantSystemSmoke.activities.remove(id, this)
    }

    private fun recordCallback(callback: String) {
        val callbacks = observation.getStringArrayList("callbacks") ?: ArrayList<String>().also {
            observation.putStringArrayList("callbacks", it)
        }
        callbacks.add(callback)
    }

    private fun rememberCaller(kind: String, caller: ComponentCaller?) {
        if (!android.security.Flags.contentUriPermissionApis()) return
        val old = ActivityUriGrantSystemSmoke.callers.put(id to kind, checkNotNull(caller))
        if (kind == "initial" && old != null) check(old == caller) { "recreation changed the caller" }
    }

    private fun verifyUris(delivered: Intent) {
        for (value in delivered.getStringArrayExtra("verifyUris") ?: emptyArray()) {
            val uri = Uri.parse(value)
            check(checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                "URI grant was absent in the activity callback: $uri"
            }
            contentResolver.query(uri, null, null, null, null).use {
                check(it != null && it.count == 2) { "granted provider unavailable: $uri" }
            }
        }
    }
}

class AUriGrantActivity : UriGrantActivity()
class BUriGrantActivity : UriGrantActivity()

/** Test controls marshal every activity operation to its actual process's main thread. */
internal object ActivityUriGrantSystemSmoke {
    private const val A = "dev.artzig.test.a"
    private const val B = "dev.artzig.test.b"
    private const val START = 1
    private const val STATUS = 2
    private const val RECREATE = 3
    private const val FINISH = 4
    private const val PERMISSIONS = 5
    private const val TAKE_PERSISTABLE = 6
    private const val RELEASE_PERSISTABLE = 7
    private const val CALLER_APIS = 8
    private const val GRANT = 9
    private const val REVOKE = 10
    private const val CALLER_PERMISSION = 11
    private const val IDENTITY = 12
    private const val STOP = 13
    private const val MOVE_BACK = 14
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val activities = HashMap<Int, UriGrantActivity>()
    val observations = HashMap<Int, Bundle>()
    val callers = HashMap<Pair<Int, String>, ComponentCaller>()
    private var originalCallerFlags: Any? = null

    fun publish(context: Context) {
        val name = context.packageName + ".activity-uri-grants"
        val handler = Handler(Looper.getMainLooper())
        ServiceManager.addService(name, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in START..MOVE_BACK) return false
                data.enforceInterface(name)
                val args = checkNotNull(data.readBundle())
                val action = FutureTask {
                    val id = args.getInt("id", -1)
                    when (code) {
                        CALLER_APIS -> setCallerApis(args.getBoolean("enabled"))
                        STOP -> handler.postDelayed({ Runtime.getRuntime().exit(0) }, 100)
                        MOVE_BACK -> check(checkNotNull(activities[id]).moveTaskToBack(args.getBoolean("nonRoot")) == args.getBoolean("expected"))
                        GRANT, REVOKE -> {
                            val uri = Uri.parse(checkNotNull(args.getString("uri")))
                            val target = args.getString("target")
                            val mode = args.getInt("mode")
                            if (code == GRANT) context.grantUriPermission(checkNotNull(target), uri, mode)
                            else if (target != null) context.revokeUriPermission(target, uri, mode)
                            else context.revokeUriPermission(uri, mode)
                        }
                        START -> {
                            val intent = checkNotNull(args.getParcelable("intent", Intent::class.java))
                            val options = args.getBundle("options")
                            if (id < 0) context.startActivity(intent, options) else {
                                checkNotNull(activities[id]).startActivityForResult(intent,
                                    args.getInt("request", -1), options)
                            }
                        }
                        RECREATE -> checkNotNull(activities[id]).recreate()
                        FINISH -> {
                            val activity = checkNotNull(activities[id])
                            args.getParcelable("intent", Intent::class.java)?.let {
                                activity.setResult(Activity.RESULT_OK, it)
                            }
                            if (args.getBoolean("removeTask")) activity.finishAndRemoveTask()
                            else activity.finish()
                        }
                        TAKE_PERSISTABLE, RELEASE_PERSISTABLE -> {
                            val uri = Uri.parse(checkNotNull(args.getString("uri")))
                            if (code == TAKE_PERSISTABLE) {
                                context.contentResolver.takePersistableUriPermission(uri, READ)
                            } else context.contentResolver.releasePersistableUriPermission(uri, READ)
                        }
                    }
                    if (code == IDENTITY) {
                        val activity = activities[id]
                        val kind = args.getString("kind")
                        val caller = kind?.let { checkNotNull(callers[id to it]) }
                        Bundle().apply {
                            putInt("callerUid", caller?.uid ?: Process.INVALID_UID)
                            putString("callerPackage", caller?.getPackage())
                            putInt("launchUid", activity?.launchedFromUid ?: Process.INVALID_UID)
                            putString("launchPackage", activity?.launchedFromPackage)
                            putString("callingPackage", activity?.callingPackage)
                            putParcelable("callingActivity", activity?.callingActivity)
                        }
                    } else if (code == CALLER_PERMISSION) {
                        val caller = checkNotNull(callers[id to args.getString("kind")])
                        Bundle().apply {
                            putInt("permission", caller.checkContentUriPermission(
                                Uri.parse(checkNotNull(args.getString("uri"))), args.getInt("mode")))
                        }
                    } else if (code == PERMISSIONS) {
                        val uris = checkNotNull(args.getStringArray("uris"))
                        Bundle().apply {
                            for ((key, mode) in listOf("read" to READ, "write" to WRITE)) {
                                putIntArray(key, IntArray(uris.size) {
                                    context.checkUriPermission(Uri.parse(uris[it]), Process.myPid(),
                                        Process.myUid(), mode)
                                })
                            }
                        }
                    } else Bundle(observations[id] ?: Bundle()).apply {
                        putBoolean("live", activities.containsKey(id))
                        putInt("taskId", activities[id]?.taskId ?: -1)
                        putBoolean("taskRoot", activities[id]?.isTaskRoot == true)
                        putInt("uid", Process.myUid())
                    }
                }
                handler.post(action)
                val response = checkNotNull(reply)
                try {
                    val result = action.get(10, TimeUnit.SECONDS)
                    response.writeNoException()
                    response.writeBundle(result)
                } catch (failure: ExecutionException) {
                    response.writeException(failure.cause as? Exception ?: failure)
                }
                return true
            }
        })
    }

    private fun setCallerApis(enabled: Boolean) {
        val field = android.security.Flags::class.java.getDeclaredField("FEATURE_FLAGS")
        field.isAccessible = true
        if (!enabled) {
            field.set(null, checkNotNull(originalCallerFlags))
            originalCallerFlags = null
            return
        }
        check(originalCallerFlags == null)
        val original = field.get(null)
        originalCallerFlags = original
        field.set(null, java.lang.reflect.Proxy.newProxyInstance(field.type.classLoader,
            arrayOf(field.type)) { _, method, arguments ->
                if (method.name == "contentUriPermissionApis") true
                else method.invoke(original, *(arguments ?: emptyArray()))
            })
    }

    private fun call(pkg: String, code: Int, args: Bundle = Bundle()): Bundle {
        val name = "$pkg.activity-uri-grants"
        val remote = checkNotNull(ServiceManager.waitForService(name))
        if (pkg == B) check(remote !is Binder)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(name)
            request.writeBundle(args)
            check(remote.transact(code, request, reply, 0))
            reply.readException()
            return checkNotNull(reply.readBundle())
        } finally { request.recycle(); reply.recycle() }
    }

    private fun args(id: Int) = Bundle().apply { putInt("id", id) }
    private fun awaitState(pkg: String, id: Int, condition: (Bundle) -> Boolean): Bundle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var state: Bundle
        do {
            state = call(pkg, STATUS, args(id))
            if (condition(state)) return state
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("activity $pkg/$id did not reach expected state: $state")
    }
    private fun activityIntent(pkg: String, id: Int) = Intent().setComponent(
        ComponentName(pkg, if (pkg == A) AUriGrantActivity::class.java.name
            else BUriGrantActivity::class.java.name)).putExtra("id", id)
    private fun start(parent: Int, intent: Intent, request: Int = -1) = call(A, START,
        args(parent).apply { putParcelable("intent", intent); putInt("request", request) })

    private fun requireAccess(pkg: String, granted: Boolean, vararg uris: String) {
        val expected = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            val result = call(pkg, PERMISSIONS, Bundle().apply { putStringArray("uris", arrayOf(*uris)) })
            check(checkNotNull(result.getIntArray("write")).all { it == PackageManager.PERMISSION_DENIED })
            if (checkNotNull(result.getIntArray("read")).all { it == expected }) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("unexpected URI access: $pkg granted=$granted uris=${uris.toList()}")
    }

    fun verify() {
        val data = "content://$A.secret/launch/data"
        val clip = "content://$A.secret/launch/clip"
        val nested = "content://$A.secret/launch/nested"
        val extra = "content://$A.secret/launch/new"
        val result = "content://$B.secret/result/data"
        val persisted = "content://$B.secret/result/persisted"
        requireAccess(B, false, data, clip, nested, extra)
        start(-1, activityIntent(A, 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitState(A, 1) { it.getBoolean("resumed") }

        val forbidden = activityIntent(A, 25).setData(Uri.parse("content://$B.secret/unowned"))
            .addFlags(READ)
        check(runCatching { start(1, forbidden, 42) }.exceptionOrNull() is SecurityException) {
            "launching app must hold the permissions it offers"
        }
        check(!call(A, STATUS, args(25)).getBoolean("live"))

        val launch = activityIntent(B, 2).setData(Uri.parse(data)).addFlags(READ)
            .putExtra("verifyUris", arrayOf(data, clip, nested)).apply {
                clipData = ClipData.newRawUri("direct", Uri.parse(clip)).apply {
                    addItem(ClipData.Item(Intent().setData(Uri.parse(nested))))
                }
            }
        start(1, launch, 42)
        awaitState(B, 2) { it.getBoolean("resumed") && it.getInt("creates") == 1 }
        val platformIdentity = call(B, IDENTITY, args(2))
        check(platformIdentity.getInt("launchUid") == call(A, STATUS).getInt("uid"))
        check(platformIdentity.getString("launchPackage") == A)
        check(platformIdentity.getString("callingPackage") == A)
        requireAccess(B, true, data, clip, nested)
        requireAccess(B, false, "$data/child", extra)
        call(B, RECREATE, args(2))
        awaitState(B, 2) { it.getBoolean("resumed") && it.getInt("creates") == 2 }
        requireAccess(B, true, data, clip, nested)

        start(1, activityIntent(B, 2).setData(Uri.parse(extra))
            .addFlags(READ or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("verifyUris", arrayOf(extra)), 42)
        awaitState(B, 2) { it.getInt("newIntents") == 1 && it.getInt("creates") == 2 }
        requireAccess(B, true, data, extra)

        start(1, activityIntent(B, 3).setData(Uri.parse(data)).addFlags(READ)
            .putExtra("verifyUris", arrayOf(data)), 43)
        awaitState(B, 3) { it.getBoolean("resumed") }
        // A non-root finishAndRemoveTask must preserve the task's other owners.
        call(B, FINISH, args(3).apply { putBoolean("removeTask", true) })
        awaitState(B, 3) { !it.getBoolean("live") }
        requireAccess(B, true, data)

        val resultIntent = Intent().setData(Uri.parse(result))
            .addFlags(READ or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra("verifyUris", arrayOf(result, persisted)).apply {
                clipData = ClipData.newRawUri("persist", Uri.parse(persisted))
            }
        call(B, FINISH, args(2).apply { putParcelable("intent", resultIntent) })
        awaitState(A, 1) { it.getInt("resultRequest") == 42 &&
            it.getInt("resultCode") == Activity.RESULT_OK && it.getString("resultUri") == result }
        awaitState(B, 2) { !it.getBoolean("live") }
        requireAccess(B, false, data, clip, nested, extra)
        requireAccess(A, true, result, persisted)
        call(A, TAKE_PERSISTABLE, Bundle().apply { putString("uri", persisted) })
        call(A, FINISH, args(1))
        awaitState(A, 1) { !it.getBoolean("live") }
        requireAccess(A, false, result)
        requireAccess(A, true, persisted)
        call(A, RELEASE_PERSISTABLE, Bundle().apply { putString("uri", persisted) })
        requireAccess(A, false, persisted)

        start(-1, activityIntent(B, 4).setData(Uri.parse(data))
            .addFlags(READ or Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("verifyUris", arrayOf(data)))
        awaitState(B, 4) { it.getBoolean("resumed") }
        call(B, FINISH, args(4).apply { putBoolean("removeTask", true) })
        awaitState(B, 4) { !it.getBoolean("live") }
        requireAccess(B, false, data)
        verifyCallerSnapshots()
        verifyTaskReuse()
        System.err.println("system-test: activity URI grants cover launch, ClipData, rejection, " +
            "recreation, new intents, shared owners, results, persistence and task removal")
    }

    private fun verifyTaskReuse() {
        val separateTask = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        start(-1, activityIntent(A, 80).addFlags(separateTask))
        val root = awaitState(A, 80) { it.getBoolean("resumed") }
        start(80, activityIntent(A, 81))
        val child = awaitState(A, 81) { it.getBoolean("resumed") }
        check(root.getInt("taskId") == child.getInt("taskId"))
        check(root.getBoolean("taskRoot") && !child.getBoolean("taskRoot"))
        start(-1, activityIntent(B, 82).addFlags(separateTask))
        awaitState(B, 82) { it.getBoolean("resumed") }
        start(-1, activityIntent(A, 80).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val restored = awaitState(A, 81) { it.getBoolean("resumed") }
        check(restored.getInt("creates") == 1 && restored.getInt("newIntents") == 0)
        check(restored.getInt("taskId") == root.getInt("taskId"))
        check(call(A, STATUS, args(80)).getInt("creates") == 1)
        call(A, MOVE_BACK, args(81).apply { putBoolean("nonRoot", false); putBoolean("expected", false) })
        check(call(A, STATUS, args(81)).getBoolean("resumed"))
        call(A, MOVE_BACK, args(81).apply { putBoolean("nonRoot", true); putBoolean("expected", true) })
        awaitState(B, 82) { it.getBoolean("resumed") }
        start(-1, activityIntent(A, 83).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val delivered = awaitState(A, 81) { it.getBoolean("resumed") && it.getInt("newIntents") == 1 }
        check(delivered.getInt("creates") == 1 && !call(A, STATUS, args(83)).getBoolean("live"))
        check(checkNotNull(delivered.getStringArrayList("callbacks")).takeLast(2) == listOf("new-intent", "resume"))
        call(A, FINISH, args(80).apply { putBoolean("removeTask", true) })
        awaitState(A, 80) { !it.getBoolean("live") }
        awaitState(A, 81) { !it.getBoolean("live") }
        awaitState(B, 82) { it.getBoolean("resumed") }
        call(B, FINISH, args(82).apply { putBoolean("removeTask", true) })
        awaitState(B, 82) { !it.getBoolean("live") }

        start(-1, activityIntent(A, 90).addFlags(separateTask))
        awaitState(A, 90) { it.getBoolean("resumed") }
        start(90, activityIntent(B, 91), 41)
        awaitState(B, 91) { it.getBoolean("resumed") }
        start(-1, activityIntent(A, 90).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val cleared = awaitState(A, 90) { it.getBoolean("resumed") && it.getInt("resultRequest") == 41 }
        check(cleared.getInt("resultCode") == Activity.RESULT_CANCELED && cleared.getInt("creates") == 1)
        check(checkNotNull(cleared.getStringArrayList("callbacks")).takeLast(3) == listOf("result:41", "new-intent", "resume"))
        awaitState(B, 91) { !it.getBoolean("live") }
        start(90, activityIntent(B, 92).addFlags(separateTask), 42)
        awaitState(B, 92) { it.getBoolean("resumed") }
        val canceled = awaitState(A, 90) { it.getInt("resultRequest") == 42 }
        check(canceled.getInt("resultCode") == Activity.RESULT_CANCELED)
        check(call(B, IDENTITY, args(92)).getString("callingPackage") == null)
        call(B, FINISH, args(92).apply { putBoolean("removeTask", true) })
        awaitState(B, 92) { !it.getBoolean("live") }
        val returned = awaitState(A, 90) { it.getBoolean("resumed") }
        check(checkNotNull(returned.getStringArrayList("callbacks")).count { it == "result:42" } == 1)
        call(A, FINISH, args(90).apply { putBoolean("removeTask", true) })
        awaitState(A, 90) { !it.getBoolean("live") }
        System.err.println("system-test: task reuse preserves the top screen, task identity/root/back APIs, new-intent ordering and canceled results across processes")
    }

    /** Run from a separately signed process; both peers share a test key but not a UID. */
    fun verifyCallerIdentity() {
        val source = "dev.artzig.identity.a"
        val target = "dev.artzig.identity.b"
        fun identity(pkg: String, id: Int, kind: String = "initial") = call(pkg, IDENTITY,
            args(id).apply { putString("kind", kind) })
        fun launch(pkg: String, parent: Int, to: String, id: Int, shared: Boolean = false,
            request: Int = -1, flags: Int = 0) = call(pkg, START, args(parent).apply {
                putParcelable("intent", activityIntent(to, id).addFlags(flags))
                putInt("request", request)
                putBundle("options", android.app.ActivityOptions.makeBasic().apply {
                    setShareIdentityEnabled(shared)
                }.toBundle())
            })
        fun expected(snapshot: Bundle, uid: Int, pkg: String?, launching: Boolean = true) {
            check(snapshot.getInt("callerUid") == uid && snapshot.getString("callerPackage") == pkg) {
                "caller identity: $snapshot expected $uid/$pkg"
            }
            if (launching) check(snapshot.getInt("launchUid") == uid &&
                snapshot.getString("launchPackage") == pkg) { "launch identity: $snapshot" }
        }
        call(source, CALLER_APIS, Bundle().apply { putBoolean("enabled", true) })
        call(target, CALLER_APIS, Bundle().apply { putBoolean("enabled", true) })
        val sourceUid = call(source, STATUS).getInt("uid")
        val targetUid = call(target, STATUS).getInt("uid")
        check(sourceUid != targetUid && sourceUid != Process.SYSTEM_UID && targetUid != Process.SYSTEM_UID)
        try {
            launch(source, -1, source, 101, flags = Intent.FLAG_ACTIVITY_NEW_TASK)
            awaitState(source, 101) { it.getBoolean("resumed") }
            expected(identity(source, 101), sourceUid, source)
            check(identity(source, 101).getString("callingPackage") == null)
            launch(source, 101, target, 102)
            awaitState(target, 102) { it.getBoolean("resumed") }
            expected(identity(target, 102), Process.INVALID_UID, null)
            check(identity(target, 102).getString("callingPackage") == null)
            call(target, RECREATE, args(102))
            awaitState(target, 102) { it.getInt("creates") == 2 && it.getBoolean("resumed") }
            expected(identity(target, 102), Process.INVALID_UID, null)
            launch(source, 101, target, 102, shared = true, flags = Intent.FLAG_ACTIVITY_SINGLE_TOP)
            awaitState(target, 102) { it.getInt("newIntents") == 1 }
            expected(identity(target, 102, "new"), sourceUid, source, launching = false)
            expected(identity(target, 102), Process.INVALID_UID, null)
            launch(source, 101, target, 102, flags = Intent.FLAG_ACTIVITY_SINGLE_TOP)
            awaitState(target, 102) { it.getInt("newIntents") == 2 }
            expected(identity(target, 102, "new"), Process.INVALID_UID, null)
            launch(source, 101, target, 103, shared = true, request = 42)
            awaitState(target, 103) { it.getBoolean("resumed") }
            val shared = identity(target, 103)
            expected(shared, sourceUid, source)
            check(shared.getString("callingPackage") == source)
            check(shared.getParcelable("callingActivity", ComponentName::class.java) ==
                activityIntent(source, 101).component)
            call(target, FINISH, args(103))
            awaitState(target, 102) { it.getBoolean("resumed") }
            check(call(source, STATUS, args(101)).getInt("resultRequest", -1) == -1)
            // A second result must join the same queue without waking the covered recipient.
            launch(source, 101, target, 105, request = 44)
            awaitState(target, 105) { it.getBoolean("resumed") }
            call(target, FINISH, args(105))
            awaitState(target, 102) { it.getBoolean("resumed") }
            check(call(source, STATUS, args(101)).getInt("resultRequest", -1) == -1)
            call(target, FINISH, args(102))
            val delivered = awaitState(source, 101) {
                it.getInt("resultRequest") == 44 && it.getBoolean("resumed")
            }
            val callbacks = checkNotNull(delivered.getStringArrayList("callbacks"))
            check(callbacks.takeLast(3) == listOf("result:42", "result:44", "resume")) { callbacks }
            check(callbacks.count { it.startsWith("result:") } == 2) { callbacks }
            awaitState(target, 103) { !it.getBoolean("live") }
            expected(identity(source, 101, "result"), Process.INVALID_UID, null, launching = false)
            // The retained object loses access once its owning activity record is removed.
            expected(identity(target, 103), Process.INVALID_UID, null)
            launch(source, 101, source, 104, request = 43)
            awaitState(source, 104) { it.getBoolean("resumed") }
            expected(identity(source, 104), sourceUid, source)
            call(source, FINISH, args(104))
            awaitState(source, 101) { it.getInt("resultRequest") == 43 }
            expected(identity(source, 101, "result"), sourceUid, source)
            System.err.println("system-test: caller identity hides unshared cross-UID callers, retains per-delivery sharing, same-UID access, results and recreation")
        } finally {
            for (pkg in listOf(target, source)) call(pkg, CALLER_APIS,
                Bundle().apply { putBoolean("enabled", false) })
        }
    }

    fun stopIdentityHost() { call("dev.artzig.identity.b", STOP) }

    private fun verifyCallerSnapshots() {
        fun toggle(pkg: String, enabled: Boolean) = call(pkg, CALLER_APIS,
            Bundle().apply { putBoolean("enabled", enabled) })
        fun grant(uri: String, mode: Int, revoke: Boolean = false) = call(B,
            if (revoke) REVOKE else GRANT, Bundle().apply {
                putString("uri", uri); putString("target", A); putInt("mode", mode)
            })
        fun permission(pkg: String, id: Int, kind: String, uri: String, mode: Int) =
            call(pkg, CALLER_PERMISSION, args(id).apply {
                putString("kind", kind); putString("uri", uri); putInt("mode", mode)
            }).getInt("permission")
        fun expect(id: Int, kind: String, uri: String, mode: Int, granted: Boolean) {
            check(permission(B, id, kind, uri, mode) == if (granted)
                PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED) {
                "caller permission $id/$kind $uri mode=$mode granted=$granted"
            }
        }
        toggle(A, true)
        toggle(B, true)
        try {
            val data = "content://$B.secret/caller/data"
            val clip = "content://$B.secret/caller/clip"
            val stream = "content://$B.secret/caller/stream"
            val denied = "content://$B.secret/caller/denied"
            val unknown = "content://$B.secret/caller/unknown"
            val unshared = "content://$A.secret/caller/unshared"
            val readable = arrayOf(data, clip, stream)
            readable.forEach { grant(it, READ) }
            start(-1, activityIntent(A, 19).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitState(A, 19) { it.getBoolean("resumed") }
            val launch = activityIntent(B, 20).setData(Uri.parse(data)).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                        arrayListOf(Uri.parse(denied), Uri.parse(unshared)))
                    clipData = ClipData.newRawUri("clip", Uri.parse(clip)).apply {
                        addItem(ClipData.Item(Intent().putExtra(Intent.EXTRA_STREAM, Uri.parse(stream))))
                    }
                }
            start(19, launch)
            awaitState(B, 20) { it.getBoolean("resumed") }
            readable.forEach {
                expect(20, "initial", it, READ, true)
                expect(20, "initial", it, WRITE, false)
                expect(20, "initial", it, READ or WRITE, false)
            }
            expect(20, "initial", denied, READ, false)
            check(runCatching { permission(B, 20, "initial", unknown, READ) }.exceptionOrNull()
                is IllegalArgumentException)
            check(runCatching { permission(B, 20, "initial", data, 0) }.exceptionOrNull()
                is IllegalArgumentException)
            check(runCatching { permission(B, 20, "initial", unshared, READ) }.exceptionOrNull()
                is SecurityException)
            readable.forEach { grant(it, READ, true) }
            requireAccess(A, false, *readable)
            // Revocation and recreation must not recompute the original caller snapshot.
            call(B, RECREATE, args(20))
            awaitState(B, 20) { it.getInt("creates") == 2 && it.getBoolean("resumed") }
            readable.forEach { expect(20, "initial", it, READ, true) }
            grant(data, WRITE)
            start(19, activityIntent(B, 20).setData(Uri.parse(data))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            awaitState(B, 20) { it.getInt("newIntents") == 1 }
            expect(20, "new", data, READ, false)
            expect(20, "new", data, WRITE, true)
            expect(20, "initial", data, READ, true)
            expect(20, "initial", data, WRITE, false)
            grant(data, WRITE, true)
            call(B, FINISH, args(20))
            awaitState(B, 20) { !it.getBoolean("live") }
            expect(20, "initial", data, READ, false)
            expect(20, "new", data, WRITE, false)
            call(A, FINISH, args(19))
            awaitState(A, 19) { !it.getBoolean("live") }

            start(-1, activityIntent(A, 21).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitState(A, 21) { it.getBoolean("resumed") }
            start(21, activityIntent(B, 22), 71)
            awaitState(B, 22) { it.getBoolean("resumed") }
            val result = "content://$B.secret/caller/result"
            call(B, FINISH, args(22).apply {
                putParcelable("intent", Intent().setData(Uri.parse(result)).addFlags(READ))
            })
            awaitState(A, 21) { it.getInt("resultRequest") == 71 && it.getBoolean("resumed") }
            check(permission(A, 21, "result", result, READ) == PackageManager.PERMISSION_GRANTED)
            // The receiver must still have access when checking a retained caller.
            // The package-specific revoke overload intentionally excludes Intent-owned grants.
            call(B, REVOKE, Bundle().apply { putString("uri", result); putInt("mode", READ) })
            requireAccess(A, false, result)
            check(runCatching { permission(A, 21, "result", result, READ) }.exceptionOrNull()
                is SecurityException)
            call(A, FINISH, args(21))
            awaitState(A, 21) { !it.getBoolean("live") }
            System.err.println("system-test: ComponentCaller URI snapshots preserve delivery-time " +
                "read/write access, nested streams, denied/absent distinctions and activity lifetime")
        } finally {
            toggle(B, false)
            toggle(A, false)
        }
    }

}
