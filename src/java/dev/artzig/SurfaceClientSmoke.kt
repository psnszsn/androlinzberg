package dev.artzig

import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceSession

/** Stock Java/JNI clients talking to the launcher's separate SurfaceFlinger process. */
object SurfaceClientSmoke {
    private fun await(done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        val queue = checkNotNull(Looper.myLooper()).queue
        while (!done() && SystemClock.uptimeMillis() < deadline) {
            val message = queue.`pollDueMessage$artzig`()
            if (message == null) { Thread.sleep(1); continue }
            try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        check(done()) { "Timed out waiting for stock SurfaceFlinger callback" }
    }

    @JvmStatic fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 640, 480, 120, 0)
        val looper = checkNotNull(Looper.myLooper())
        val owner = Thread.currentThread()
        val executor = HandlerExecutor(Handler(looper))
        val session = SurfaceSession()
        val original = SurfaceControl.Builder(session).setName("java-surface-root")
            .setContainerLayer().build()
        val child = SurfaceControl.Builder(session).setName("java-surface-child")
            .setColorLayer().setParent(original).build()
        val alias = SurfaceControl(original, "java-parcel-lifetime")
        val retained = mutableListOf(original, child, alias)
        try {
            check(original.isValid && child.isValid && alias.isSameSurface(original))
            check(original.layerId > 0 && child.layerId != original.layerId)
            val parcel = Parcel.obtain()
            val restored: SurfaceControl
            try {
                parcel.writeInt(0x1234)
                original.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                check(!original.isValid && alias.isValid)
                parcel.writeInt(0x5678)
                parcel.setDataPosition(0)
                check(parcel.readInt() == 0x1234)
                restored = SurfaceControl.CREATOR.createFromParcel(parcel).also { retained.add(it) }
                check(parcel.readInt() == 0x5678 && parcel.dataAvail() == 0)
            } finally { parcel.recycle() }
            check(restored.isValid && restored.isSameSurface(alias))
            alias.release()
            var commits = 0
            fun committed() {
                check(Thread.currentThread() === owner)
                commits++
            }
            SurfaceControl.Transaction().use { transaction ->
                transaction.show(restored).show(child).setColor(child, floatArrayOf(1f, 0f, 0f))
                    .setPosition(child, 13f, 17f).setCrop(child, android.graphics.Rect(0, 0, 40, 30))
                    .addTransactionCommittedListener(executor, ::committed).apply()
            }
            await { commits == 1 }
            val transactionParcel = Parcel.obtain()
            val imported: SurfaceControl.Transaction
            try {
                SurfaceControl.Transaction().use { source ->
                    source.setPosition(child, 23f, 29f).setAlpha(child, .75f)
                    source.writeToParcel(transactionParcel, 0)
                }
                transactionParcel.setDataPosition(0)
                imported = SurfaceControl.Transaction.CREATOR.createFromParcel(transactionParcel)
                check(transactionParcel.dataAvail() == 0)
            } finally { transactionParcel.recycle() }
            imported.use { it.addTransactionCommittedListener(executor, ::committed).apply() }
            await { commits == 2 }

            val choreographer = restored.getChoreographer(looper)
            check(restored.hasChoreographer() && restored.getChoreographer() === choreographer)
            val phases = mutableListOf<String>()
            choreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL, { phases.add("traversal") }, null)
            choreographer.postCallback(Choreographer.CALLBACK_INPUT, { phases.add("input") }, null)
            choreographer.postCallback(Choreographer.CALLBACK_COMMIT, { phases.add("commit") }, null)
            await { phases.size == 3 }
            check(phases == listOf("input", "traversal", "commit"))
            check(choreographer.lastFrameTimeNanos > 0 && choreographer.frameIntervalNanos > 0)

            SurfaceControl.Transaction().use { transaction ->
                transaction.remove(child).remove(restored)
                    .addTransactionCommittedListener(executor, ::committed).apply()
            }
            await { commits == 3 }
            check(!child.isValid && !restored.isValid && !restored.hasChoreographer())
        } finally {
            retained.forEach { it.release() }
            session.kill()
        }
        println("SurfaceClient: stock JNI creation, ownership, Parcel, committed callbacks, per-surface vsync and removal passed")
    }
}
