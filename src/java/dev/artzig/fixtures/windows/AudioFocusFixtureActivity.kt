package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Public audio-focus clients on a visible task, with real Binder/Handler delivery. */
class AudioFocusFixtureActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private val requests = ArrayList<AudioFocusRequest>()
    private val events = ArrayList<Int>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        audio = getSystemService(AudioManager::class.java)
        status = TextView(this).apply { text = "Audio focus ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, 60))
            addView(Button(this@AudioFocusFixtureActivity).apply {
                text = "Check audio focus"
                setOnClickListener { isEnabled = false; verifyFocus() }
            })
        })
    }

    private fun verifyFocus() {
        // MediaRouter initializes display discovery even for the local speaker.
        val router = checkNotNull(getSystemService(android.media.MediaRouter::class.java))
        check(router.defaultRoute.volumeHandling == android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
        check(router.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO) === router.defaultRoute)
        val wifi = getSystemService(android.hardware.display.DisplayManager::class.java).wifiDisplayStatus
        check(wifi.featureState == android.hardware.display.WifiDisplayStatus.FEATURE_STATE_UNAVAILABLE)
        check(wifi.displays.isEmpty() && wifi.activeDisplay == null)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val first = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                check(Looper.myLooper() === Looper.getMainLooper())
                events.add(change)
                check(events == listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN).take(events.size))
            }, handler).build()
        val second = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ error("Unexpected transient-owner callback: $it") }, handler).build()
        requests.add(first)
        requests.add(second)
        check(audio.requestAudioFocus(first) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events.isEmpty())
        check(audio.requestAudioFocus(second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        awaitEvents(listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            check(audio.abandonAudioFocusRequest(second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            requests.remove(second)
            awaitEvents(listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN)) {
                check(audio.abandonAudioFocusRequest(first) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                requests.remove(first)
                status.text = "Audio focus callbacks verified"
                status.setBackgroundColor(Color.CYAN)
                Log.i("AudioFocusFixture", "visible task: granted, transient loss, regain, abandonment; main-thread callback order verified")
            }
        }
    }

    private fun awaitEvents(expected: List<Int>, complete: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 5000
        handler.post(object : Runnable {
            override fun run() {
                if (events.size >= expected.size) {
                    check(events == expected) { "Audio focus events: $events; expected $expected" }
                    complete()
                } else {
                    check(SystemClock.uptimeMillis() < deadline) { "Audio focus callback timeout: $events" }
                    handler.postDelayed(this, 10)
                }
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        for (request in requests) audio.abandonAudioFocusRequest(request)
        requests.clear()
        super.onDestroy()
    }
}
