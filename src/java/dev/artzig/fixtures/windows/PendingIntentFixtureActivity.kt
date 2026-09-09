package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Public PendingIntent/IntentSender clients; stock ATMS owns every launch and result. */
class PendingIntentFixtureActivity : Activity() {
    private lateinit var status: TextView
    private var oneShot: PendingIntent? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Activity PendingIntent ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, 220))
            addView(Button(this@PendingIntentFixtureActivity).apply {
                text = "Check activity PendingIntent"
                setOnClickListener { launch() }
            })
        })
    }
    private fun target(value: String) = Intent(this, PendingIntentTargetActivity::class.java)
        .putExtra("value", value)
    private fun launch() {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val first = PendingIntent.getActivity(this, 41, target("initial"), flags)
        val updated = PendingIntent.getActivity(this, 41, target("updated"), flags)
        check(first == updated && updated.isActivity && updated.isImmutable)
        check(updated.creatorPackage == packageName && updated.creatorUid == applicationInfo.uid)
        val canceled = PendingIntent.getActivity(this, 42, target("canceled"), flags)
        canceled.cancel()
        check(PendingIntent.getActivity(this, 42, target("ignored"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) == null)
        check(runCatching { canceled.send() }.exceptionOrNull() is PendingIntent.CanceledException)
        startIntentSenderForResult(updated.intentSender, 71, target("sender must not replace"), 0, 0, 0)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        check(resultCode == RESULT_OK)
        when (requestCode) {
            71 -> {
                check(data?.getStringExtra("value") == "updated")
                oneShot = PendingIntent.getActivity(this, 43, target("one shot"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                startIntentSenderForResult(checkNotNull(oneShot).intentSender, 72, null, 0, 0, 0)
            }
            72 -> {
                check(data?.getStringExtra("value") == "one shot")
                check(runCatching { checkNotNull(oneShot).send() }.exceptionOrNull() is PendingIntent.CanceledException)
                checkNotNull(createPendingResult(73, Intent().putExtra("value", "pending result"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)).send(RESULT_OK)
            }
            73 -> {
                check(data?.getStringExtra("value") == "pending result")
                status.text = "Activity PendingIntent contracts verified"
                status.setBackgroundColor(Color.CYAN)
                Log.i("PendingIntentFixture", "stock activity record: identity, update, immutable fill-in, launch result, cancellation, one-shot and pending result verified")
            }
            else -> error("Unexpected result $requestCode")
        }
    }
}

class PendingIntentTargetActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_OK, Intent().putExtra("value", intent.getStringExtra("value")))
        finish()
    }
}
