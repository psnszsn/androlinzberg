package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** APK fixture: lifecycle and window contracts, with no hosted-framework hooks. */
open class WindowFixtureActivity : Activity() {
    private val instance = nextInstance.incrementAndGet()
    private var counter = 0
    private lateinit var count: TextView
    private lateinit var editor: EditText
    private lateinit var permissions: Button
    private var permissionState = intArrayOf()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        counter = state?.getInt("counter") ?: 0
        journal("create", state != null)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { text = "Window fixture: ${this@WindowFixtureActivity.javaClass.simpleName}" })
        count = TextView(this).apply { contentDescription = "Fixture counter" }
        content.addView(count)
        updateCounter()
        editor = EditText(this).apply {
            id = android.R.id.edit
            contentDescription = "Fixture editor"
            hint = "Type here"
            setSingleLine(true)
        }
        content.addView(editor)
        fun button(label: String, action: () -> Unit): Button {
            val widget = Button(this).apply {
                text = label
                setOnClickListener { action() }
            }
            content.addView(widget)
            return widget
        }
        button("Finish this activity") { finish() }
        button("Increment counter") { counter++; updateCounter(); journal("increment") }.apply {
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            val pixels = IntArray(32 * 32) { if (it % 32 < 16) Color.MAGENTA else Color.CYAN }
            bitmap.setPixels(pixels, 0, 32, 0, 0, 32, 32)
            pointerIcon = android.view.PointerIcon.create(bitmap, 2f, 3f)
        }
        button("Next activity in this task") { startActivity(Intent(this, SecondActivity::class.java)) }
        button("Open independent task") {
            startActivity(Intent(this, IndependentTaskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
        button("Open handled resize activity") { startActivity(Intent(this, HandledResizeActivity::class.java)) }
        button("Show attached dialog") {
            journal("dialog-requested")
            val dialog = AlertDialog.Builder(this).setTitle("Fixture dialog")
                .setMessage("This dialog belongs to the current task")
                .setPositiveButton("Close dialog") { dialog, _ -> dialog.dismiss() }.show()
            check(dialog.isShowing)
            journal("dialog-shown")
        }
        button("Show decoded video") { startActivity(Intent(this, VideoFixtureActivity::class.java)) }
        button("Recreate activity") { recreate() }
        button("Show SurfaceView") {
            content.addView(SurfaceView(this).apply {
                contentDescription = "Fixture SurfaceView"
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { journal("surface-created") }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        journal("surface-changed")
                        val canvas = holder.lockCanvas() ?: return
                        try { canvas.drawColor(Color.BLUE) } finally { holder.unlockCanvasAndPost(canvas) }
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { journal("surface-destroyed") }
                })
            }, LinearLayout.LayoutParams(240, 120))
        }
        button("Show TextureView") { startActivity(Intent(this, TextureFixtureActivity::class.java)) }
        permissions = button("Check permission results") {
            permissionState = permissionNames.map(::checkSelfPermission).toIntArray()
            requestPermissions(permissionNames, 71)
        }
        setContentView(content)
    }

    override fun onRequestPermissionsResult(requestCode: Int, names: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, names, results)
        check(requestCode == 71 && names.contentEquals(permissionNames))
        check(results.contentEquals(permissionState)) { "A permission request changed administrator-managed grants" }
        check(results.contentEquals(permissionNames.map(::checkSelfPermission).toIntArray()))
        permissions.text = "Permission results verified"
        journal("permission-result")
    }

    private fun updateCounter() { count.text = "Counter: $counter" }
    override fun onStart() { super.onStart(); journal("start") }
    override fun onResume() { super.onResume(); journal("resume") }
    override fun onPause() { journal("pause"); super.onPause() }
    override fun onStop() { journal("stop"); super.onStop() }
    override fun onDestroy() { journal("destroy"); super.onDestroy() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("counter", counter)
        super.onSaveInstanceState(state)
        journal("save")
    }
    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        journal("restore", true)
    }
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        journal("configuration")
    }
    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        journal(if (focused) "focus-gained" else "focus-lost")
    }
    private fun journal(event: String, restored: Boolean = false) {
        val config = resources.configuration
        Log.i("WindowFixture", JSONObject().put("event", event)
            .put("activity", javaClass.name).put("instance", instance).put("task", taskId)
            .put("counter", counter).put("restored", restored)
            .put("widthDp", config.screenWidthDp).put("heightDp", config.screenHeightDp)
            .put("densityDpi", config.densityDpi).toString())
    }
    companion object {
        private val nextInstance = AtomicInteger()
        private val permissionNames = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.INTERNET)
    }
}

class SecondActivity : WindowFixtureActivity()
class IndependentTaskActivity : WindowFixtureActivity()
class HandledResizeActivity : WindowFixtureActivity()
