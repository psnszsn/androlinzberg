package dev.artzig.system

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Process
import com.android.server.pm.SaferIntentUtils
import java.util.concurrent.atomic.AtomicReference

/** Stock exported-component filtering with the host's real registered-filter values. */
object StockSaferIntentSmoke {
    fun run() {
        val original = IntentFilter("fixture.action")
        val exported = HostedBroadcastFilter(original, true)
        val unexported = HostedBroadcastFilter(original, false)
        original.addAction("later.action")
        check(!exported.hasAction("later.action")) // registration takes the same copy as AOSP
        fun activity(exported: Boolean) = ResolveInfo().apply {
            activityInfo = ActivityInfo().also { it.exported = exported }
        }
        val publicActivity = activity(true)
        val privateActivity = activity(false)
        fun candidates() = arrayListOf<Any>(exported, unexported, publicActivity, privateActivity)
        fun filter(intent: Intent, uid: Int): List<Any> {
            val result = candidates()
            SaferIntentUtils.filterNonExportedComponents(
                SaferIntentUtils.IntentArgs(intent, null, true, false, uid, Process.INVALID_PID), result)
            return result
        }
        check(filter(Intent("fixture.action"), 10001) == listOf(exported, publicActivity))
        check(filter(Intent("fixture.action").setPackage("fixture"), 10001) == candidates())
        check(filter(Intent("fixture.action"), Process.SYSTEM_UID) == candidates())
        check(filter(Intent("fixture.action").setClassName("fixture", "fixture.Activity"), 10001) == candidates())

        val suppression = SaferIntentUtils.DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS
        check(!suppression.get())
        suppression.set(true)
        try {
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try { check(!suppression.get()) } catch (t: Throwable) { failure.set(t) }
            }
            thread.start()
            thread.join(5_000)
            check(!thread.isAlive)
            failure.get()?.let { throw it }
            check(suppression.get())
        } finally { suppression.remove() }
        check(!suppression.get())
    }
}
