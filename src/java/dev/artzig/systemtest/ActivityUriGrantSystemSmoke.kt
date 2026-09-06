package dev.artzig.systemtest

import android.app.Activity
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
    }

    override fun onResume() {
        super.onResume()
        observation.putBoolean("resumed", true)
    }

    override fun onPause() {
        super.onPause()
        observation.putBoolean("resumed", false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        verifyUris(intent)
        observation.putInt("newIntents", observation.getInt("newIntents") + 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data?.let(::verifyUris)
        observation.putInt("resultRequest", requestCode)
        observation.putInt("resultCode", resultCode)
        observation.putString("resultUri", data?.dataString)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityUriGrantSystemSmoke.activities.remove(id, this)
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
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val activities = HashMap<Int, UriGrantActivity>()
    val observations = HashMap<Int, Bundle>()

    fun publish(context: Context) {
        val name = context.packageName + ".activity-uri-grants"
        val handler = Handler(Looper.getMainLooper())
        ServiceManager.addService(name, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in START..RELEASE_PERSISTABLE) return false
                data.enforceInterface(name)
                val args = checkNotNull(data.readBundle())
                val action = FutureTask {
                    val id = args.getInt("id", -1)
                    when (code) {
                        START -> {
                            val intent = checkNotNull(args.getParcelable("intent", Intent::class.java))
                            if (id < 0) context.startActivity(intent) else {
                                checkNotNull(activities[id]).startActivityForResult(intent,
                                    args.getInt("request", -1))
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
                    if (code == PERMISSIONS) {
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
    private fun awaitState(pkg: String, id: Int, condition: (Bundle) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var state: Bundle
        do {
            state = call(pkg, STATUS, args(id))
            if (condition(state)) return
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
        System.err.println("system-test: activity URI grants cover launch, ClipData, rejection, " +
            "recreation, new intents, shared owners, results, persistence and task removal")
    }
}
