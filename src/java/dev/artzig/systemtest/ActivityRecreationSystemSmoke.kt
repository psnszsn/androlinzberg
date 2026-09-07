package dev.artzig.systemtest

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ServiceManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A real activity in peer B: public callbacks are observed from peer A over Binder. */
class BMainActivity : Activity() {
    private var generation = 0

    override fun onCreate(state: Bundle?) {
        check(!isDestroyed && !window.isDestroyed)
        super.onCreate(state)
        generation = ++ActivityRecreationSystemSmoke.generation
        ActivityRecreationSystemSmoke.current = this
        record("create:${state?.getString("saved")}:${lastNonConfigurationInstance}")
    }
    override fun onStart() { super.onStart(); record("start") }
    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        record("restore:${state.getString("saved")}")
    }
    override fun onResume() { super.onResume(); record("resume") }
    override fun onPause() { super.onPause(); record("pause") }
    override fun onStop() { super.onStop(); record("stop:$isChangingConfigurations") }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("saved", "instance-$generation")
        record("save")
    }
    override fun onRetainNonConfigurationInstance(): Any {
        record("retain")
        return generation
    }
    override fun onDestroy() {
        check(isDestroyed && window.isDestroyed)
        super.onDestroy()
        record("destroy:$isChangingConfigurations")
        if (ActivityRecreationSystemSmoke.current === this) {
            ActivityRecreationSystemSmoke.current = null
        }
    }
    private fun record(event: String) {
        ActivityRecreationSystemSmoke.events.add("$generation:$event")
    }
}

internal object ActivityRecreationSystemSmoke {
    private const val NAME = "dev.artzig.test.b.activity-recreation"
    // Accessed only on peer B's main thread (including Binder queries marshalled below).
    var generation = 0
    var current: BMainActivity? = null
    val events = ArrayList<String>()

    fun publish() {
        val handler = Handler.createAsync(Looper.getMainLooper())
        ServiceManager.addService(NAME, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(NAME)
                if (code !in 1..4) return false
                val action = FutureTask {
                    when (code) {
                        2 -> {
                            val activity = checkNotNull(current)
                            check(activity.activityToken !is Binder)
                            events.clear()
                            activity.recreate()
                            activity.recreate()
                            check(current === activity && events.isEmpty()) {
                                "recreate destroyed its caller synchronously: $events"
                            }
                            events.add("request:return")
                        }
                        3 -> checkNotNull(current).finish()
                    }
                    if (code == 4) current else ArrayList(events)
                }
                handler.post(action)
                val result = action.get(10, TimeUnit.SECONDS)
                val response = checkNotNull(reply)
                if (code == 4) {
                    val rejected = runCatching { (result as Activity).recreate() }
                        .exceptionOrNull() is IllegalStateException
                    response.writeNoException()
                    response.writeBoolean(rejected)
                } else {
                    response.writeNoException()
                    @Suppress("UNCHECKED_CAST")
                    response.writeStringList(result as ArrayList<String>)
                }
                return true
            }
        })
    }

    fun verify(context: Context) {
        val remote = checkNotNull(ServiceManager.waitForService(NAME))
        check(remote !is Binder)
        fun <T> call(code: Int, read: (Parcel) -> T): T {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(NAME)
                check(remote.transact(code, request, reply, 0))
                reply.readException()
                return read(reply)
            } finally { request.recycle(); reply.recycle() }
        }
        fun snapshot() = call(1) { checkNotNull(it.createStringArrayList()) }
        fun awaitEvent(event: String): List<String> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var observed = snapshot()
            while (event !in observed && System.nanoTime() < deadline) {
                Thread.sleep(10)
                observed = snapshot()
            }
            check(event in observed) { "missing $event in $observed" }
            return observed
        }
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName("dev.artzig.test.b", BMainActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(awaitEvent("1:resume") == listOf("1:create:null:null", "1:start", "1:resume"))
        check(call(4) { it.readBoolean() }) { "recreate must reject non-main threads" }
        check(call(2) { it.createStringArrayList() } == listOf("request:return"))
        val recreated = awaitEvent("2:resume")
        check(recreated == listOf("request:return", "1:pause", "1:stop:true", "1:save",
            "1:retain", "1:destroy:true", "2:create:instance-1:1", "2:start",
            "2:restore:instance-1", "2:resume")) { "recreation protocol: $recreated" }
        call(3) { it.createStringArrayList() }
        val finished = awaitEvent("2:destroy:false")
        check(finished.takeLast(3) == listOf("2:pause", "2:stop:false", "2:destroy:false")) {
            "recreated activity did not finish normally: $finished"
        }
        System.err.println("system-test-client-a: ok: Activity.recreate queues and coalesces " +
            "remote-token relaunch, restores saved/nonconfiguration state, and enforces main thread")
    }
}
