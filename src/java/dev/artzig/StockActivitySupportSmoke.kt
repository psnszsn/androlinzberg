package dev.artzig

import android.app.DirectAction
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.CursorLoader
import android.content.LocusId
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.util.Dumpable
import com.android.internal.util.dump.DumpableContainerImpl
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

internal object StockActivitySupportSmoke {
    private fun testActivityAttachment(base: Context, pump: () -> Unit) {
        val pkg = base.packageName
        for ((className, localName) in listOf(
            "$pkg.Alias" to "Alias", "${pkg}x.Alias" to "${pkg}x.Alias", pkg to pkg,
        )) {
            val activity = android.app.Activity()
            check(activity.componentName == null)
            val component = android.content.ComponentName(pkg, className)
            activity.intent = android.content.Intent().setComponent(component)
            activity.attach(base)
            try {
                check(activity.componentName == component && activity.localClassName == localName)
                activity.intent = android.content.Intent().setComponent(android.content.ComponentName("other", "Other"))
                check(activity.componentName == component && activity.localClassName == localName)
                val uiThread = Thread.currentThread()
                var inline = false
                activity.runOnUiThread { inline = true }
                check(inline) { "UI-thread action was unnecessarily deferred" }
                val posted = java.util.concurrent.atomic.AtomicBoolean()
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val worker = kotlin.concurrent.thread(name = "activity-ui-dispatch") {
                    try {
                        activity.runOnUiThread {
                            check(Thread.currentThread() === uiThread)
                            posted.set(true)
                        }
                    } catch (error: Throwable) { failure.set(error) }
                }
                worker.join(3000)
                check(!worker.isAlive && failure.get() == null) { "worker dispatch failed: ${failure.get()}" }
                check(!posted.get()) { "worker action ran before the UI queue was pumped" }
                pump()
                check(posted.get())
            } finally { activity.window.destroy() }
        }
        System.err.println("stock-activity-attachment: component/alias identity, local names, setIntent stability and UI-thread dispatch passed")
    }

    private fun testLegacyActionBar(base: Context) {
        val themed = android.view.ContextThemeWrapper(base, android.R.style.Theme_Holo)
        val window = com.android.internal.policy.PhoneWindow(themed)
        try {
            window.setTitle("Legacy action bar")
            window.setContentView(android.widget.TextView(themed).apply { text = "Content" })
            val bar = checkNotNull(window.decorView.findViewById<
                com.android.internal.widget.ActionBarView>(com.android.internal.R.id.action_bar))
            check(bar.title.toString() == "Legacy action bar")
            window.setTitle("Updated title")
            check(bar.title.toString() == "Updated title")
        } finally {
            window.destroy()
        }
        System.err.println("stock-action-bar: Holo PhoneWindow inflation and title updates passed")
    }

    fun run(pump: () -> Unit) {
        val base = HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        testLegacyActionBar(base)
        testActivityAttachment(base, pump)
        val provider = object : dev.artzig.systemtest.TestProvider() {
            override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                selectionArgs: Array<String>?, sortOrder: String?): Cursor {
                check(Thread.currentThread() !== android.os.Looper.getMainLooper().thread)
                check(uri == Uri.parse("content://fixture/rows"))
                check(projection.contentEquals(arrayOf("_id", "name")))
                check(selection == "name=?" && selectionArgs.contentEquals(arrayOf("alpha")))
                check(sortOrder == "_id ASC")
                return super.query(uri, projection, selection, selectionArgs, sortOrder)
            }
        }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getContentResolver(): ContentResolver = resolver
            override fun getApplicationContext(): Context = this
        }
        val loader = CursorLoader(context, Uri.parse("content://fixture/rows"),
            arrayOf("_id", "name"), "name=?", arrayOf("alpha"), "_id ASC")
        val delivered = ArrayList<Cursor>()
        loader.registerListener(42) { _, cursor ->
            check(Thread.currentThread() === android.os.Looper.getMainLooper().thread)
            delivered.add(cursor)
        }
        fun awaitCount(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (delivered.size < count && System.nanoTime() < deadline) {
                pump()
                Thread.sleep(5)
            }
            check(delivered.size == count) { "CursorLoader delivered ${delivered.size}, expected $count" }
        }
        try {
            loader.startLoading()
            awaitCount(1)
            check(delivered[0].count == 2 && !delivered[0].isClosed)
            loader.forceLoad()
            awaitCount(2)
            check(delivered[0].isClosed && !delivered[1].isClosed)
            loader.reset()
            check(delivered[1].isClosed)
            val late = MatrixCursor(arrayOf("_id"))
            loader.deliverResult(late)
            check(late.isClosed && delivered.size == 2)
            val canceled = MatrixCursor(arrayOf("_id"))
            loader.onCanceled(canceled)
            check(canceled.isClosed)
        } finally { loader.reset() }

        val token = Binder()
        val action = DirectAction.Builder("open")
            .setExtras(Bundle().apply { putString("key", "value") })
            .setLocusId(LocusId("document"))
            .build().apply { setSource(73, token) }
        val parcel = Parcel.obtain()
        try {
            action.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = DirectAction.CREATOR.createFromParcel(parcel)
            check(restored.id == "open" && restored.taskId == 73 && restored.activityId === token)
            check(restored.extras?.getString("key") == "value")
            check(restored.locusId == LocusId("document") && restored == action)
            check(restored == DirectAction.Builder("open").build())
        } finally { parcel.recycle() }
        check(runCatching { DirectAction.Builder("").build() }.exceptionOrNull() is IllegalArgumentException)

        val dumps = DumpableContainerImpl()
        fun dumpable(text: String) = object : Dumpable {
            override fun getDumpableName(): String = "fixture"
            override fun dump(writer: PrintWriter, args: Array<String>?) { writer.println(text) }
        }
        val first = dumpable("first")
        val sameName = dumpable("replacement")
        check(dumps.addDumpable(first) && !dumps.addDumpable(sameName))
        check(!dumps.removeDumpable(sameName))
        val output = StringWriter()
        dumps.dumpAllDumpables("  ", PrintWriter(output), emptyArray())
        check(output.toString().contains("first") && !output.toString().contains("replacement"))
        check(dumps.removeDumpable(first) && !dumps.removeDumpable(first))
        System.err.println("stock-activity-support: cursor threading/ownership, direct-action parcel, dump identity passed")
    }
}
