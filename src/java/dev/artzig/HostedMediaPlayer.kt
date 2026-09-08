@file:Suppress("UNUSED_PARAMETER")

package dev.artzig

import android.media.*
import android.os.IBinder
import android.os.Parcel
import android.os.PersistableBundle
import android.view.Surface
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.WeakHashMap

/**
 * Pinned android_media_MediaPlayer.cpp and libmedia/mediaplayer.cpp with a null
 * IMediaPlayer remote. Direct C++ reuse needs MediaPlayerService, its Binder C++
 * protocol and AudioSystem client infrastructure. Keep that missing interaction
 * here: stock Java owns listeners, wake locks, subtitles and PlayerBase tracking.
 * This does not provide media playback; the existing MediaCodec backend is separate.
 */
object HostedMediaPlayer {
    private class Client(val listener: Any, var session: Int) {
        var idle = true
        var prepareSync = false
        var stream = AudioManager.STREAM_MUSIC
        var loop = false
        var left = 1f
        var right = 1f
        var send = 0f
        var attributes: ByteArray? = null
        var endpoint: Pair<String, Int>? = null
    }
    // Values retain only the WeakReference supplied by stock MediaPlayer, never its owner.
    private val clients = WeakHashMap<MediaPlayer, Client>()
    private val context = MediaPlayer::class.java.getDeclaredField("mNativeContext").apply { isAccessible = true }
    private val post = MediaPlayer::class.java.getDeclaredMethod("postEventFromNative",
        Any::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Any::class.java).apply { isAccessible = true }
    private fun client(player: MediaPlayer): Client = clients[player] ?: throw IllegalStateException()

    @JvmStatic fun init() { check(context.type == Long::class.javaPrimitiveType) }
    @JvmStatic @Synchronized fun setup(player: MediaPlayer, listener: Any, attribution: Parcel, session: Int) {
        clients[player] = Client(listener, if (session == 0) AudioSystem.newAudioSessionId() else session)
        context.setLong(player, 1) // Allocated-client marker, not a native pointer.
    }
    @JvmStatic @Synchronized fun release(player: MediaPlayer) {
        clients.remove(player)
        context.setLong(player, 0)
    }
    @JvmStatic @Synchronized fun setUri(player: MediaPlayer, http: IBinder?, path: String?, keys: Array<String?>?, values: Array<String?>?) {
        client(player)
        require(path != null)
        require((keys == null) == (values == null) && keys?.size == values?.size) {
            "keys and values arrays have different length"
        }
        keys?.forEach { java.util.Objects.requireNonNull(it) }
        values?.forEach { java.util.Objects.requireNonNull(it) }
        // setDataSource(url) returns BAD_VALUE when media.player is absent.
        throw IllegalArgumentException()
    }
    @JvmStatic @Synchronized fun setFd(player: MediaPlayer, fd: FileDescriptor?, offset: Long, length: Long) {
        client(player); require(fd != null)
        // The absent service never reads, closes or validates the borrowed descriptor.
        throw IOException("setDataSourceFD failed.: status=0x80000000")
    }
    @JvmStatic @Synchronized fun setCallback(player: MediaPlayer, source: MediaDataSource?) {
        client(player); require(source != null)
        throw RuntimeException("setDataSourceCallback failed.: status=0x80000000")
    }
    @JvmStatic @Synchronized fun setSurface(player: MediaPlayer, surface: Surface?) {
        client(player)
        if (surface != null) {
            require(surface.isValid) { "The surface has been released" }
            throw ArtzigTestDiagnostics.unsupported("MediaPlayer", "setSurface",
                "host Surface has no MediaPlayerService buffer producer")
        }
        // Clearing the producer before initialization succeeds; the native NO_INIT is ignored.
    }
    @JvmStatic @Synchronized fun prepare(player: MediaPlayer, request: Parcel): Int {
        val state = client(player)
        if (state.prepareSync) throw IOException("Prepare failed.: status=0xFFFFFF8E")
        state.prepareSync = true
        throw IllegalStateException()
    }
    @JvmStatic @Synchronized fun prepareAsync(player: MediaPlayer, request: Parcel): Int {
        client(player); throw IllegalStateException()
    }
    private fun error(player: MediaPlayer, status: Int = -38) {
        val state = client(player)
        // MediaPlayer::notify only accepts an idle error without a connected remote.
        if (!state.idle) return
        state.idle = false
        if (state.prepareSync) { state.prepareSync = false; return }
        post.invoke(null, state.listener, 100 /* MEDIA_ERROR */, status, 0, null)
    }
    @JvmStatic @Synchronized fun start(player: MediaPlayer) = error(player)
    @JvmStatic @Synchronized fun stop(player: MediaPlayer) = error(player)
    @JvmStatic @Synchronized fun pause(player: MediaPlayer) = error(player)
    @JvmStatic @Synchronized fun seek(player: MediaPlayer, time: Long, mode: Int) = error(player)
    @JvmStatic @Synchronized fun notifyAt(player: MediaPlayer, time: Long) = error(player)
    @JvmStatic @Synchronized fun isPlaying(player: MediaPlayer): Boolean { client(player); return false }
    @JvmStatic @Synchronized fun width(player: MediaPlayer): Int { client(player); return 0 }
    @JvmStatic @Synchronized fun height(player: MediaPlayer): Int { client(player); return 0 }
    // The pinned JNI leaves these integers uninitialized on INVALID_OPERATION.
    // Deliver the same error event and return a deterministic zero for that invalid call.
    @JvmStatic @Synchronized fun position(player: MediaPlayer): Int { error(player); return 0 }
    @JvmStatic @Synchronized fun duration(player: MediaPlayer): Int { error(player); return 0 }
    @JvmStatic @Synchronized fun metrics(player: MediaPlayer): PersistableBundle? { client(player); return null }
    @JvmStatic @Synchronized fun setPlayback(player: MediaPlayer, params: PlaybackParams?) {
        client(player); java.util.Objects.requireNonNull(params); throw IllegalStateException()
    }
    @JvmStatic @Synchronized fun getPlayback(player: MediaPlayer): PlaybackParams { client(player); throw IllegalStateException() }
    @JvmStatic @Synchronized fun setSync(player: MediaPlayer, params: SyncParams?) {
        client(player); java.util.Objects.requireNonNull(params); throw IllegalStateException()
    }
    @JvmStatic @Synchronized fun getSync(player: MediaPlayer): SyncParams { client(player); throw IllegalStateException() }
    @JvmStatic @Synchronized fun reset(player: MediaPlayer) {
        val state = client(player)
        state.loop = false
        if (state.idle) return
        state.prepareSync = false
        state.endpoint = null
        // reset_l/clear_l do not change ERROR to IDLE when mPlayer is null.
    }
    @JvmStatic @Synchronized fun setStream(player: MediaPlayer, stream: Int) { client(player).stream = stream }
    @JvmStatic @Synchronized fun getStream(player: MediaPlayer): Int = client(player).stream
    @JvmStatic @Synchronized fun setParameter(player: MediaPlayer, key: Int, request: Parcel): Boolean {
        val state = client(player)
        if (key != 1400 /* KEY_PARAMETER_AUDIO_ATTRIBUTES */) return false
        state.attributes = request.marshall()
        return true
    }
    @JvmStatic @Synchronized fun setLooping(player: MediaPlayer, loop: Boolean) { client(player).loop = loop }
    @JvmStatic @Synchronized fun isLooping(player: MediaPlayer): Boolean { client(player); return false }
    @JvmStatic @Synchronized fun setVolume(player: MediaPlayer, left: Float, right: Float) {
        client(player).apply { this.left = left; this.right = right }
    }
    @JvmStatic @Synchronized fun invoke(player: MediaPlayer, request: Parcel, reply: Parcel): Int { client(player); return -38 }
    @JvmStatic @Synchronized fun setFilter(player: MediaPlayer, request: Parcel?): Int {
        client(player); if (request == null) throw RuntimeException("Filter is null"); return -19
    }
    @JvmStatic @Synchronized fun getMetadata(player: MediaPlayer, update: Boolean, filter: Boolean, reply: Parcel?): Boolean {
        client(player); if (reply == null) throw RuntimeException("Reply parcel is null")
        reply.setDataSize(0); reply.setDataPosition(0)
        return false
    }
    @JvmStatic @Synchronized fun setSession(player: MediaPlayer, id: Int) {
        val state = client(player)
        if (!state.idle) error(player) else if (id < 0) error(player, -22) else state.session = id
    }
    @JvmStatic @Synchronized fun getSession(player: MediaPlayer): Int = client(player).session
    @JvmStatic @Synchronized fun setSend(player: MediaPlayer, level: Float) { client(player).send = level }
    @JvmStatic @Synchronized fun attachEffect(player: MediaPlayer, effect: Int) = error(player)
    @JvmStatic fun battery(reply: Parcel): Int = throw RuntimeException("cannot get MediaPlayerService")
    @JvmStatic @Synchronized fun retransmit(player: MediaPlayer, address: String?, port: Int): Int {
        val state = client(player)
        check(state.idle)
        if (address == null) { state.endpoint = null; return 0 }
        if (port > 0xffff || runCatching { InetAddress.parseNumericAddress(address) }.getOrNull() !is Inet4Address) return -22
        state.endpoint = address to (port and 0xffff)
        return 0
    }
    @JvmStatic @Synchronized fun next(player: MediaPlayer, next: MediaPlayer?) {
        client(player)
        if (next != null) client(next)
        require(next !== player) { "Next player can't be self" }
        throw IllegalArgumentException("setNextMediaPlayer failed.: status=0xFFFFFFED")
    }
    @JvmStatic @Synchronized fun shape(player: MediaPlayer, config: VolumeShaper.Configuration?, operation: VolumeShaper.Operation?): Int =
        if (clients.containsKey(player)) -19 else -38
    @JvmStatic fun shapeState(player: MediaPlayer, id: Int): VolumeShaper.State? = null
    @JvmStatic @Synchronized fun prepareDrm(player: MediaPlayer, uuid: ByteArray?, session: ByteArray?) {
        client(player); require(uuid != null && uuid.size == 16) { "invalid UUID size, expected 16 bytes" }
        java.util.Objects.requireNonNull(session)
        require(session!!.isNotEmpty()) { "empty drmSessionId" }
        throw MediaDrm.MediaDrmStateException(-19, "Failed to prepare DRM scheme: Unknown DRM Msg")
    }
    @JvmStatic @Synchronized fun releaseDrm(player: MediaPlayer) {
        client(player)
        // JNI only throws for INVALID_OPERATION; the absent remote returns NO_INIT.
    }
    @JvmStatic fun output(player: MediaPlayer, device: Int): Boolean = false
    @JvmStatic @Synchronized fun routes(player: MediaPlayer): IntArray? = if (clients.containsKey(player)) intArrayOf() else null
    @JvmStatic @Synchronized fun deviceCallback(player: MediaPlayer, enabled: Boolean) {
        if (clients.containsKey(player)) throw IllegalStateException()
    }
}
