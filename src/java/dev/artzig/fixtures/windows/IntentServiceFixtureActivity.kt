package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.IntentService
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Public IntentService client: stock ActiveServices owns each start, the stop and the destroy. */
class IntentServiceFixtureActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "IntentService ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, 220))
            addView(Button(this@IntentServiceFixtureActivity).apply {
                text = "Check IntentService"
                setOnClickListener { start() }
            })
        })
    }
    private fun start() {
        FixtureIntentService.handled.clear()
        FixtureIntentService.destroyed = Runnable { runOnUiThread(::verify) }
        for (value in listOf("first", "second")) {
            checkNotNull(startService(Intent(this, FixtureIntentService::class.java).putExtra("value", value)))
        }
    }
    private fun verify() {
        // Both starts run in order on one worker; the service stops only after the newest start ID.
        val worker = "IntentService[${FixtureIntentService::class.java.simpleName}]"
        check(FixtureIntentService.handled == listOf("first@$worker", "second@$worker")) { FixtureIntentService.handled }
        status.text = "IntentService contracts verified"
        status.setBackgroundColor(Color.CYAN)
        Log.i("IntentServiceFixture", "stock IntentService: ordered starts on one worker, then stopSelf and destroy")
    }
}

class FixtureIntentService : IntentService(FixtureIntentService::class.java.simpleName) {
    override fun onHandleIntent(intent: Intent?) {
        check(Looper.myLooper() != Looper.getMainLooper())
        handled += "${intent?.getStringExtra("value")}@${Thread.currentThread().name}"
    }
    override fun onDestroy() {
        super.onDestroy()
        Handler(Looper.getMainLooper()).post { destroyed?.run() }
    }
    companion object {
        val handled: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile var destroyed: Runnable? = null
    }
}
