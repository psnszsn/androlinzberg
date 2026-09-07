package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.TextView

/** Public Android contracts only: the provider observes callbacks in the same
 * manifest-selected secondary process as the activity. */
private object ColdLifecycle {
    private val events = ArrayList<String>()
    @Synchronized fun record(event: String) { events.add(event) }
    @Synchronized fun snapshot() = Bundle().apply {
        putInt("pid", Process.myPid())
        putInt("uid", Process.myUid())
        putString("process", Application.getProcessName())
        putStringArrayList("events", ArrayList(events))
    }
}

class ColdProcessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ColdLifecycle.record("create")
        setContentView(TextView(this).apply { text = "Cold process activity" })
    }
    override fun onStart() { super.onStart(); ColdLifecycle.record("start") }
    override fun onResume() { super.onResume(); ColdLifecycle.record("resume") }
    override fun onPause() { ColdLifecycle.record("pause"); super.onPause() }
    override fun onStop() { ColdLifecycle.record("stop"); super.onStop() }
    override fun onDestroy() { ColdLifecycle.record("destroy"); super.onDestroy() }
}

open class ColdProcessProvider : ContentProvider() {
    override fun onCreate(): Boolean { ColdLifecycle.record("provider"); return true }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "snapshot")
        return ColdLifecycle.snapshot()
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

class ProviderOnlyProcess : ColdProcessProvider()

/** Crash before Application.onCreate once, then allow an ordinary service retry. */
class FailingStartupProvider : ColdProcessProvider() {
    override fun onCreate(): Boolean {
        val marker = java.io.File(checkNotNull(context).filesDir, "startup-failure-once")
        if (marker.createNewFile()) throw IllegalStateException("fixture startup failure")
        return super.onCreate()
    }
}

class RetryProcessService : ColdProcessService()

open class ColdProcessService : android.app.Service() {
    override fun onCreate() { super.onCreate(); ColdLifecycle.record("service-create") }
    override fun onBind(intent: android.content.Intent): android.os.IBinder {
        ColdLifecycle.record("service-bind")
        return android.os.Messenger(android.os.Handler(mainLooper) { request ->
            request.replyTo.send(android.os.Message.obtain().apply { data = ColdLifecycle.snapshot() })
            true
        }).binder
    }
}
