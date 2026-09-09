package dev.artzig

import android.app.ActivityThread
import java.lang.reflect.InvocationTargetException

/** AOSP ActivityThread.main prepares and attaches the application thread. The
 * host starts ART directly instead of forking a zygote; lifecycle transactions
 * and the application Looper remain stock. Reflection is limited to bootstrap. */
object HostedActivityProcess {
    @JvmStatic
    fun attachApplication(): ActivityThread {
        check(ActivityThread.currentActivityThread() == null)
        val constructor = ActivityThread::class.java.getDeclaredConstructor().apply { isAccessible = true }
        val thread = constructor.newInstance()
        val attach = ActivityThread::class.java.getDeclaredMethod("attach",
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val startSequence = System.getenv("AZK_APP_START_SEQUENCE")?.toLong() ?: 0L
        require(startSequence >= 0)
        try { attach.invoke(thread, false, startSequence) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        initializeMainHandler(thread)
        return thread
    }

    /** AOSP main initializes this handler after attach; the host owns the outer Looper. */
    @JvmStatic fun initializeMainHandler(thread: ActivityThread) {
        val field = ActivityThread::class.java.getDeclaredField("sMainThreadHandler").apply { isAccessible = true }
        if (field.get(null) == null) field.set(null, thread.handler)
    }

}
