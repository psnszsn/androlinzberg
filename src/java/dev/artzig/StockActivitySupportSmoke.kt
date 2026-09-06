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
    fun run(pump: () -> Unit) {
        val base = HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
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
