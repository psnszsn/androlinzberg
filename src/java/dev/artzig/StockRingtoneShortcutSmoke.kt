package dev.artzig

import android.app.ActivityThread
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import com.android.internal.accessibility.AccessibilityShortcutController
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

internal object StockRingtoneShortcutSmoke {
    fun run(drain: () -> Unit) {
        val context = ActivityThread.currentApplication() ?: ActivityThread.currentActivityThread().systemContext
        player(drain)
        playbackReports()
        ringtoneUris(context)
        shortcutSettings(context)
        println("stock-ringtone-shortcut: PASS media client errors, cleanup, URI restore and shortcut settings")
    }
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
    }
    private fun player(drain: () -> Unit) {
        val service = IAudioService.Stub.asInterface(ServiceManager.getService(Context.AUDIO_SERVICE))
        val first = MediaPlayer()
        val second = MediaPlayer()
        try {
            check(first.audioSessionId > 0 && first.audioSessionId and 7 == 1)
            check(first.audioSessionId != second.audioSessionId)
            val ids = (1..32).map { AudioSystem.newAudioPlayerId() } + (1..32).map { AudioSystem.newAudioRecorderId() }
            check(ids.toSet().size == ids.size && ids.all { it > 0 && it and 7 == 7 })
            check(!first.isPlaying && first.videoWidth == 0 && first.videoHeight == 0)
            first.isLooping = true
            check(!first.isLooping) // No remote player: the cached request is not active looping.
            first.setVolume(0.25f, 0.75f)
            first.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            first.setAudioSessionId(second.audioSessionId)
            check(first.audioSessionId == second.audioSessionId)
            check(first.metrics == null && first.routedDevices.isEmpty())
            val prepareDrm = MediaPlayer::class.java.getDeclaredMethod("_prepareDrm",
                ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
            val releaseDrm = MediaPlayer::class.java.getDeclaredMethod("_releaseDrm").apply { isAccessible = true }
            val drmFailure = runCatching { prepareDrm.invoke(first, ByteArray(16), byteArrayOf(1)) }
                .exceptionOrNull() as java.lang.reflect.InvocationTargetException
            check((drmFailure.cause as MediaDrm.MediaDrmStateException).errorCode == -19)
            releaseDrm.invoke(first)
            check(!AudioManager.isHapticPlaybackSupported())
            rejects<IllegalStateException> { first.prepareAsync() }
            rejects<IllegalStateException> { first.playbackParams }
            rejects<IllegalStateException> { first.setSyncParams(SyncParams().allowDefaults()) }
            rejects<IllegalArgumentException> { first.setNextMediaPlayer(first) }
            rejects<IllegalArgumentException> { first.setNextMediaPlayer(second) }
            rejects<IllegalArgumentException> { first.setDataSource("https://media.invalid/absent") }
            val file = Files.createTempFile("stock-media-player-", ".bin").toFile()
            try {
                file.writeBytes(byteArrayOf(1, 2, 3))
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val nativeSource = MediaPlayer::class.java.getDeclaredMethod("_setDataSource",
                        java.io.FileDescriptor::class.java, Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType).apply { isAccessible = true }
                    check((runCatching { nativeSource.invoke(first, fd.fileDescriptor, 0L, 3L) }
                        .exceptionOrNull() as java.lang.reflect.InvocationTargetException).cause is IOException)
                    // Pinned public overload catches and logs the native IOException.
                    first.setDataSource(fd.fileDescriptor, 0, 3)
                    check(fd.statSize == 3L) // The failed service request did not consume the fd.
                }
            } finally { check(file.delete()) }
            val errors = ArrayList<Pair<Int, Int>>()
            first.setOnErrorListener { _, what, extra -> errors.add(what to extra); true }
            first.pause()
            check(errors.isEmpty())
            drain()
            check(errors == listOf(-38 to 0))
            first.pause()
            drain()
            check(errors.size == 1) // Disconnected ERROR state suppresses subsequent callbacks.
            second.setOnErrorListener { _, _, _ -> error("Callback after release") }
            second.pause()
            second.release()
            drain()
            check((runCatching { releaseDrm.invoke(second) }.exceptionOrNull()
                as java.lang.reflect.InvocationTargetException).cause is IllegalStateException)
            rejects<IllegalStateException> { second.isPlaying }
            rejects<IllegalStateException> { second.audioSessionId }
            check(service.ringtonePlayer == null)
        } finally {
            first.release()
            second.release()
            drain()
        }
    }
    private fun playbackReports() {
        var granted = true
        val enforcer = object : PermissionEnforcer() {
            override fun checkPermission(permission: String, pid: Int, uid: Int) =
                if (granted) android.permission.PermissionCheckerManager.PERMISSION_GRANTED
                else android.permission.PermissionCheckerManager.PERMISSION_HARD_DENIED
            override fun checkPermission(permission: String, source: AttributionSource) =
                checkPermission(permission, 0, source.uid)
        }
        val service = dev.artzig.system.HostedAudioService(enforcer)
        fun card(type: Int = AudioPlaybackConfiguration.PLAYER_TYPE_JAM_MEDIAPLAYER): PlayerBase.PlayerIdCard {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(type)
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build().writeToParcel(parcel, 0)
                parcel.writeStrongBinder(null)
                parcel.writeInt(41)
                parcel.setDataPosition(0)
                return PlayerBase.PlayerIdCard.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        val privateEvents = ArrayList<Pair<List<AudioPlaybackConfiguration>, Boolean>>()
        val publicEvents = ArrayList<Pair<List<AudioPlaybackConfiguration>, Boolean>>()
        fun callback(events: MutableList<Pair<List<AudioPlaybackConfiguration>, Boolean>>) =
            object : IPlaybackConfigDispatcher.Stub() {
                override fun dispatchPlaybackConfigChange(configs: List<AudioPlaybackConfiguration>, flush: Boolean) {
                    events.add(configs to flush)
                }
            }
        val privileged = callback(privateEvents)
        val public = callback(publicEvents)
        service.registerPlaybackCallback(privileged)
        granted = false
        service.registerPlaybackCallback(public)
        granted = true
        val first = service.trackPlayer(card())
        val second = service.trackPlayer(card())
        try {
            check(privateEvents.isEmpty() && publicEvents.isEmpty())
            val before = service.activePlaybackConfigurations
            check(before.size == 2 && before.all { it.clientUid == Process.myUid() && it.sessionId == 41 })
            service.playerEvent(first, AudioPlaybackConfiguration.PLAYER_STATE_STARTED, intArrayOf(7))
            check(privateEvents.single().first.size == 2 && !privateEvents.single().second)
            check(publicEvents.single().first.single().let {
                it.playerInterfaceId == first && it.clientUid == -1 && it.clientPid == -1 && it.sessionId == 0
            })
            check(before.none { it.isActive }) // Earlier snapshots are not mutated by subsequent reports.
            service.playerSessionId(first, 49)
            check(privateEvents.last().first.first { it.playerInterfaceId == first }.sessionId == 49)
            check(privateEvents.first().first.first { it.playerInterfaceId == first }.sessionId == 41)
            service.releasePlayer(first)
            check(privateEvents.last().second && privateEvents.last().first.single().playerInterfaceId == second)
            check(publicEvents.last().first.isEmpty() && !publicEvents.last().second)
            val count = privateEvents.size
            service.releasePlayer(second)
            check(service.activePlaybackConfigurations.isEmpty() && privateEvents.size == count)
        } finally {
            service.unregisterPlaybackCallback(privileged)
            service.unregisterPlaybackCallback(public)
            service.releasePlayer(first)
            service.releasePlayer(second)
        }
    }
    private fun ringtoneUris(context: Context) {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val input = base.buildUpon().appendPath("3").appendQueryParameter("title", "Chime").build()
        val resolved = ContentUris.withAppendedId(base, 7)
        val canonical = resolved.buildUpon().appendQueryParameter("title", "Current title").build()
        var direct = true
        var rows = 1
        var queried = 0
        var result: Cursor? = null
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun uncanonicalize(uri: Uri): Uri? = if (direct) resolved else null
            override fun canonicalize(uri: Uri): Uri { check(uri == resolved); return canonical }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
                check(uri == base && selection == "is_notification=1 AND title=?")
                check(args?.toList() == listOf("Chime"))
                queried++
                return MatrixCursor(arrayOf("_id")).apply { repeat(rows) { addRow(arrayOf(7L)) }; result = this }
            }
            override fun getType(uri: Uri) = "audio/ogg"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Unexpected update")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Unexpected delete")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY; exported = true })
        val resolver = object : ContentResolver(context) {
            override fun acquireProvider(c: Context, authority: String): IContentProvider? =
                if (authority == MediaStore.AUTHORITY) provider.getIContentProvider() else null
            override fun acquireExistingProvider(c: Context, authority: String) = acquireProvider(c, authority)
            override fun acquireUnstableProvider(c: Context, authority: String) = acquireProvider(c, authority)
            override fun releaseProvider(provider: IContentProvider) = true
            override fun releaseUnstableProvider(provider: IContentProvider) = true
            override fun unstableProviderDied(provider: IContentProvider) = error("Unexpected death")
        }
        fun restore(): Uri? = RingtoneManager.getRingtoneUriForRestore(resolver, input.toString(), RingtoneManager.TYPE_NOTIFICATION)
        check(RingtoneManager.getRingtoneUriForRestore(resolver, null, RingtoneManager.TYPE_NOTIFICATION) == null)
        check(restore() == canonical && queried == 0)
        direct = false
        check(restore() == canonical && queried == 1 && result!!.isClosed)
        rows = 0
        rejects<FileNotFoundException> { restore() }
        check(result!!.isClosed)
        rows = 2
        rejects<FileNotFoundException> { restore() }
        check(result!!.isClosed)
        rejects<IllegalArgumentException> { RingtoneManager.getRingtoneUriForRestore(resolver, input.toString(), 99) }
        for (type in listOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)) {
            check(RingtoneManager.getDefaultType(RingtoneManager.getDefaultUri(type)) == type)
        }
        check(!RingtoneManager.isDefault(input))
    }
    private fun shortcutSettings(context: Context) {
        val resolver = context.contentResolver
        val keys = listOf(Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
            Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN)
        val saved = keys.associateWith { Settings.Secure.getStringForUser(resolver, it, 0) }
        try {
            check(Settings.Secure.putStringForUser(resolver, keys[0], "", 0))
            check(Settings.Secure.putStringForUser(resolver, keys[1], "0", 0))
            check(Settings.Secure.putStringForUser(resolver, keys[2], "0", 0))
            val controller = AccessibilityShortcutController(context, Handler(Looper.getMainLooper()), 0)
            check(!controller.isAccessibilityShortcutAvailable(false))
            check(Settings.Secure.putStringForUser(resolver, keys[0],
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString(), 0))
            controller.onSettingsChanged()
            check(controller.isAccessibilityShortcutAvailable(false) && !controller.isAccessibilityShortcutAvailable(true))
            check(Settings.Secure.putStringForUser(resolver, keys[1], "1", 0))
            controller.onSettingsChanged()
            check(controller.isAccessibilityShortcutAvailable(true))
            check(Settings.Secure.putStringForUser(resolver, keys[0], "", 0))
            controller.onSettingsChanged()
            check(!controller.isAccessibilityShortcutAvailable(false))
        } finally { for ((key, value) in saved) check(Settings.Secure.putStringForUser(resolver, key, value, 0)) }
    }
}
