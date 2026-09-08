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
    private val activityEvents = ArrayList<String>()
    @Volatile var activity = java.lang.ref.WeakReference<Activity>(null)
    @Volatile var frameCommitted = false
    @Volatile var activityGender = android.content.res.Configuration.GRAMMATICAL_GENDER_UNDEFINED
    @Volatile var activityUiMode = 0
    @Volatile var restoredState = false
    @Synchronized fun record(event: String, owner: Activity? = null) {
        events.add(event)
        if (owner != null) activityEvents.add("${owner.javaClass.simpleName}:$event")
    }
    @Synchronized fun snapshot() = Bundle().apply {
        putInt("pid", Process.myPid())
        putInt("uid", Process.myUid())
        putString("process", Application.getProcessName())
        putStringArrayList("events", ArrayList(events))
        putStringArrayList("activityEvents", ArrayList(activityEvents))
        putString("activityClass", activity.get()?.javaClass?.simpleName)
        putBoolean("frameCommitted", frameCommitted)
        putInt("activityGender", activityGender)
        putInt("activityUiMode", activityUiMode)
        putBoolean("restoredState", restoredState)
    }
}

open class ColdProcessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ColdLifecycle.record("create", this)
        ColdLifecycle.frameCommitted = false
        ColdLifecycle.activityGender = resources.configuration.grammaticalGender
        ColdLifecycle.activityUiMode = resources.configuration.uiMode
        ColdLifecycle.restoredState = savedInstanceState?.getBoolean("cold-state", false) == true
        setTaskDescription(android.app.ActivityManager.TaskDescription.Builder()
            .setLabel(intent.getStringExtra("title") ?: "Cyan task").build())
        setContentView(object : TextView(this) {
            private val marker = android.graphics.Paint()
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val side = minOf(width, height) / 8f
                marker.color = android.graphics.Color.RED
                canvas.drawRect(0f, 0f, side, side, marker)
                marker.color = android.graphics.Color.GREEN
                canvas.drawRect(width - side, 0f, width.toFloat(), side, marker)
                marker.color = android.graphics.Color.BLUE
                canvas.drawRect(0f, height - side, side, height.toFloat(), marker)
                marker.color = android.graphics.Color.YELLOW
                canvas.drawRect(width - side, height - side, width.toFloat(), height.toFloat(), marker)
            }
        }.apply {
            text = "Cold process activity"
            setBackgroundColor(intent.getIntExtra("color", android.graphics.Color.CYAN))
        })
        window.decorView.viewTreeObserver.registerFrameCommitCallback { ColdLifecycle.frameCommitted = true }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("cold-state", true)
        ColdLifecycle.record("save", this)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); ColdLifecycle.record("start", this) }
    override fun onResume() {
        super.onResume()
        ColdLifecycle.activity = java.lang.ref.WeakReference(this)
        ColdLifecycle.activityGender = resources.configuration.grammaticalGender
        ColdLifecycle.activityUiMode = resources.configuration.uiMode
        ColdLifecycle.record("resume", this)
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if (ColdLifecycle.activity.get() === this) {
            ColdLifecycle.activityGender = configuration.grammaticalGender
            ColdLifecycle.activityUiMode = configuration.uiMode
        }
        ColdLifecycle.record("configuration", this)
    }
    override fun onPause() { ColdLifecycle.record("pause", this); super.onPause() }
    override fun onStop() { ColdLifecycle.record("stop", this); super.onStop() }
    override fun onDestroy() { ColdLifecycle.record("destroy", this); super.onDestroy() }
}

class ColdGroupedActivity : ColdProcessActivity()
class ColdHandledConfigurationActivity : ColdProcessActivity()

open class ColdProcessProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // The cold-launch inspection gate creates this file before starting us.
        // Holding provider startup also holds AMS.finishAttachApplication, without
        // relying on a race against a fast Application.onCreate.
        val gate = java.io.File(checkNotNull(context).filesDir, "inspection-startup-gate")
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (gate.exists()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Inspection startup gate was not released" }
            android.os.SystemClock.sleep(5)
        }
        ColdLifecycle.record("provider")
        return true
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "finish") {
            val current = checkNotNull(ColdLifecycle.activity.get())
            check(android.os.Handler(android.os.Looper.getMainLooper()).post { current.finish() })
            return Bundle()
        }
        if (method == "night") {
            // A provider retains its caller's Binder identity. Exercise the API
            // as this application, just as an Activity's UiModeManager call does.
            val identity = android.os.Binder.clearCallingIdentity()
            try {
                checkNotNull(context).getSystemService(android.app.UiModeManager::class.java)
                    .setApplicationNightMode(checkNotNull(arg).toInt())
            } finally { android.os.Binder.restoreCallingIdentity(identity) }
            return Bundle()
        }
        require(method == "snapshot")
        return ColdLifecycle.snapshot().apply {
            putInt("applicationUiMode", checkNotNull(context).resources.configuration.uiMode)
            putInt("reportedUiMode", android.app.ActivityManager.getService().configuration.uiMode)
            putInt("applicationGender", checkNotNull(context).resources.configuration.grammaticalGender)
            putInt("reportedGender", android.app.ActivityManager.getService().configuration.grammaticalGender)
        }
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
