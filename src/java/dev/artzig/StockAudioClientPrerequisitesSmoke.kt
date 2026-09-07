package dev.artzig

import android.hardware.hdmi.*
import android.media.AudioMetadata
import android.media.AudioMix
import android.media.AudioMixMatchCriterion
import android.media.AudioMixMatchCriterionValue
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import dev.artzig.system.unsupported
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** Observable contracts of the shared stock audio/HDMI client closure. */
object StockAudioClientPrerequisitesSmoke {
    @JvmStatic fun run() {
        metadata()
        mixTransport()
        hdmiClient()
    }

    private fun metadata() {
        val rate = AudioMetadata.Format.KEY_SAMPLE_RATE
        val mime = AudioMetadata.Format.KEY_MIME
        val metadata = AudioMetadata.BaseMap()
        check(metadata.set(rate, 48000) == null)
        check(metadata.set(rate, 44100) == 48000)
        metadata.set(mime, "audio/raw")
        metadata.set(AudioMetadata.Format.KEY_ATMOS_PRESENT, true)
        val sameName = AudioMetadata.createKey(rate.name, Int::class.javaObjectType)
        check(metadata.get(sameName) == 44100)
        check(metadata.get(AudioMetadata.createKey(rate.name, String::class.java)) == null)
        val snapshot = metadata.dup()
        metadata.set(rate, 48000)
        check(snapshot.get(rate) == 44100)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val wire = checkNotNull(AudioMetadata.toByteBuffer(metadata, order))
            val restored = checkNotNull(AudioMetadata.fromByteBuffer(wire.order(order)))
            check(restored.get(rate) == 48000 && restored.get(mime) == "audio/raw")
            check(restored.get(AudioMetadata.Format.KEY_ATMOS_PRESENT) == true)
            check(restored.remove(rate) == 48000 && !restored.containsKey(rate))
        }
        check(AudioMetadata.fromByteBuffer(ByteBuffer.allocate(3)) == null)
        check(runCatching {
            AudioMetadata.BaseMap::class.java.getMethod("set", AudioMetadata.Key::class.java, Any::class.java)
                .invoke(metadata, mime, null)
        }.exceptionOrNull()?.cause is NullPointerException)
    }

    private fun mixTransport() {
        val token = Binder()
        val mix = AudioMix().apply {
            criteria = arrayOf(AudioMixMatchCriterion().apply {
                invert = true
                value = AudioMixMatchCriterionValue.uid(10123)
            })
            mToken = token
            mVirtualDeviceId = 23
            allowPrivilegedMediaPlaybackCapture = true
        }
        val restored = copy(mix, AudioMix.CREATOR)
        check(restored.mToken === token && restored.mVirtualDeviceId == 23)
        check(restored.allowPrivilegedMediaPlaybackCapture)
        check(restored.criteria.single().invert && restored.criteria.single().value.uid == 10123)
        mix.criteria.single().value = AudioMixMatchCriterionValue.userId(7)
        check(restored.criteria.single().value.uid == 10123)
        check(runCatching { restored.criteria.single().value.userId }.exceptionOrNull() is IllegalStateException)
    }

    private fun hdmiClient() {
        val listeners = ArrayList<IHdmiHotplugEventListener>()
        var soundbarMode = HdmiControlManager.SOUNDBAR_MODE_DISABLED
        val port = HdmiPortInfo.Builder(2, HdmiPortInfo.PORT_OUTPUT, 0x2000)
            .setCecSupported(true).build()
        // An in-memory service fixture exercises stock clients over generated parcels.
        // The production host does not advertise a CEC controller.
        val service = object : IHdmiControlService.Stub(), IHdmiControlService by unsupported<IHdmiControlService>() {
            override fun asBinder(): IBinder = this
            override fun getSupportedTypes() = intArrayOf(HdmiDeviceInfo.DEVICE_PLAYBACK)
            override fun getCecSettingIntValue(name: String): Int {
                check(name == HdmiControlManager.CEC_SETTING_NAME_SOUNDBAR_MODE)
                return soundbarMode
            }
            override fun addHotplugEventListener(listener: IHdmiHotplugEventListener) { listeners.add(listener) }
            override fun removeHotplugEventListener(listener: IHdmiHotplugEventListener) {
                check(listeners.removeAll { it.asBinder() === listener.asBinder() })
            }
            override fun getPortInfo(): List<HdmiPortInfo> = listOf(port)
            override fun oneTouchPlay(callback: IHdmiControlCallback) { callback.onComplete(HdmiControlManager.RESULT_TIMEOUT) }
            override fun queryDisplayStatus(callback: IHdmiControlCallback) {
                callback.onComplete(HdmiControlManager.POWER_STATUS_STANDBY)
            }
        }
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.asBinder().transact(code, data, reply, flags)
        }
        val manager = HdmiControlManager(IHdmiControlService.Stub.asInterface(remote))
        check(manager.tvClient == null && manager.audioSystemClient == null)
        soundbarMode = HdmiControlManager.SOUNDBAR_MODE_ENABLED
        check(manager.audioSystemClient != null)
        soundbarMode = HdmiControlManager.SOUNDBAR_MODE_DISABLED
        check(manager.audioSystemClient == null)
        val playback = checkNotNull(manager.playbackClient)
        val results = ArrayList<Int>()
        playback.oneTouchPlay { results.add(it) }
        playback.queryDisplayStatus { results.add(it) }
        check(results == listOf(HdmiControlManager.RESULT_TIMEOUT, HdmiControlManager.POWER_STATUS_STANDBY))

        val pending = ArrayList<Runnable>()
        val events = ArrayList<HdmiHotplugEvent>()
        val listener = HdmiControlManager.HotplugEventListener { events.add(it) }
        manager.addHotplugEventListener(Executor { pending.add(it) }, listener)
        listeners.toList().forEach { it.onReceived(HdmiHotplugEvent(2, true)) }
        check(events.isEmpty() && manager.physicalAddress == 0x2000)
        pending.toList().forEach { it.run() }
        check(events.single().port == 2 && events.single().isConnected)
        manager.removeHotplugEventListener(listener)
        check(listeners.size == 1) // The manager retains its internal port observer.
        listeners.single().onReceived(HdmiHotplugEvent(2, false))
        check(manager.physicalAddress == 0xffff)
        check(copy(port, HdmiPortInfo.CREATOR) == port)
    }

    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
