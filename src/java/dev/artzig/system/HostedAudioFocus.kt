package dev.artzig.system

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioSystem
import android.media.IAudioFocusDispatcher
import android.os.Binder
import android.os.IBinder
import android.os.UserHandle
import com.android.server.audio.FocusRequester
import com.android.server.audio.HardeningEnforcer
import com.android.server.audio.MediaFocusControl
import com.android.server.audio.PlayerFocusEnforcer
import com.android.server.utils.EventLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pinned AudioService.requestAudioFocus/abandonAudioFocus permission boundary. AudioService
 * itself requires the device broker and native audio policy engine. The unmodified
 * MediaFocusControl, FocusRequester and HardeningEnforcer own focus ordering, Binder death,
 * delayed grants, AppOps and SDK policy here. No telephony mode reset or registered external
 * audio policy exists in this host; those operations remain at their service boundaries.
 */
internal class HostedAudioFocus(private val context: Context, playback: HostedPlaybackRegistry) {
    private val control = FocusControl(context, playback)
    private val hardening = FocusHardening(context)
    private fun permitted(permission: String) =
        context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun validUsage(attributes: AudioAttributes): Boolean {
        val usage = attributes.systemUsage
        if (!AudioAttributes.isSystemUsage(usage)) return true
        return usage == AudioAttributes.USAGE_CALL_ASSISTANT &&
            ((attributes.allFlags and AudioAttributes.FLAG_CALL_REDIRECTION != 0 &&
                permitted(Manifest.permission.CALL_AUDIO_INTERCEPTION)) ||
                permitted(Manifest.permission.MODIFY_AUDIO_ROUTING))
    }

    fun request(attributes: AudioAttributes?, gain: Int, token: IBinder?,
        dispatcher: IAudioFocusDispatcher?, client: String?, packageName: String?,
        attributionTag: String?, flags: Int, sdk: Int): Int {
        require(flags and AudioManager.AUDIOFOCUS_FLAG_TEST == 0) { "Invalid test flag" }
        if (attributes != null && !validUsage(attributes)) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        if (flags and AudioManager.AUDIOFOCUS_FLAG_LOCK != 0) {
            // Only Telecom or a registered external AudioPolicy may lock focus. No external
            // AudioPolicy is registered by the hosted service.
            if (client != AudioSystem.IN_VOICE_COMM_FOCUS_ID ||
                !permitted(Manifest.permission.MODIFY_PHONE_STATE)) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (attributes == null || client == null || packageName == null) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val uid = Binder.getCallingUid()
        val privileged = uid < UserHandle.AID_APP_START ||
            permitted(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED) ||
            permitted(Manifest.permission.MODIFY_AUDIO_ROUTING)
        val identity = Binder.clearCallingIdentity()
        try {
            if (!privileged && hardening.blocks(uid, client, gain, packageName, attributionTag, sdk))
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } finally { Binder.restoreCallingIdentity(identity) }
        // AudioService's registered accessibility UID set starts empty on this host.
        val forceDuck = uid == 0 && attributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY &&
            gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK &&
            attributes.bundle?.getBoolean(AudioFocusRequest.KEY_ACCESSIBILITY_FORCE_FOCUS_DUCKING) == true
        return control.request(attributes, gain, token, dispatcher, client, packageName, flags, sdk, forceDuck, privileged)
    }

    fun abandon(dispatcher: IAudioFocusDispatcher?, client: String?, attributes: AudioAttributes?, packageName: String?): Int {
        if (attributes != null && !validUsage(attributes)) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return control.abandon(dispatcher, client, attributes, packageName)
    }
    fun unregister(client: String?) = control.unregister(client)
    fun current(): Int = control.current()
    fun setMultiFocus(enabled: Boolean) = control.updateMultiAudioFocus(enabled)

    private class FocusHardening(context: Context) : HardeningEnforcer(context, false,
        AtomicBoolean(false), context.getSystemService(AppOpsManager::class.java), context.packageManager,
        EventLogger(20, "Hosted audio hardening")) {
        fun blocks(uid: Int, client: String, gain: Int, packageName: String, attributionTag: String?, sdk: Int) =
            blockFocusMethod(uid, METHOD_AUDIO_MANAGER_REQUEST_AUDIO_FOCUS, client, gain, packageName, attributionTag, sdk)
    }
    private class FocusControl(context: Context, playback: HostedPlaybackRegistry) :
        MediaFocusControl(context, FocusPlayers(playback)) {
        fun request(attributes: AudioAttributes, gain: Int, token: IBinder?, dispatcher: IAudioFocusDispatcher?,
            client: String, packageName: String, flags: Int, sdk: Int, forceDuck: Boolean, privileged: Boolean) =
            requestAudioFocus(attributes, gain, token, dispatcher, client, packageName, flags, sdk, forceDuck, -1, privileged)
        fun abandon(dispatcher: IAudioFocusDispatcher?, client: String?, attributes: AudioAttributes?, packageName: String?) =
            abandonAudioFocus(dispatcher, client, attributes, packageName)
        fun unregister(client: String?) = unregisterAudioFocusClient(client)
        fun current() = getCurrentAudioFocus()
    }
    private class FocusPlayers(private val playback: HostedPlaybackRegistry) : PlayerFocusEnforcer {
        // Returning false makes the original FocusRequester deliver app focus-loss callbacks.
        // No players acquire a server volume ramp, so there are no ramps to restore or forget.
        override fun duckPlayers(winner: FocusRequester, loser: FocusRequester, forceDuck: Boolean) = false
        override fun fadeOutPlayers(winner: FocusRequester, loser: FocusRequester) = false
        override fun restoreVShapedPlayers(winner: FocusRequester) {}
        override fun forgetUid(uid: Int) {}
        override fun shouldEnforceFade() = false
        override fun getFadeOutDurationMillis(attributes: AudioAttributes) = 0L
        override fun getFadeInDelayForOffendersMillis(attributes: AudioAttributes) = 0L
        override fun mutePlayersForCall(usages: IntArray) = playback.muteForCall(usages)
        override fun unmutePlayersForCall() = playback.unmuteForCall()
    }
}
