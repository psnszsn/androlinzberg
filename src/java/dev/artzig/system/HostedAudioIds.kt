package dev.artzig.system

import android.os.ServiceManager

/** AudioFlinger.newAudioUniqueId/nextUniqueId without the audio daemon. */
internal class HostedAudioIdService : IHostedAudioIds.Stub() {
    private val counters = IntArray(8) { 8 }
    @Synchronized override fun nextId(use: Int): Int {
        if (use !in counters.indices) return 0
        val value = counters[use]
        // Keep session and player IDs positive and fail allocation after exhaustion.
        if (value <= 0) return 0
        counters[use] = value + 8
        return value or use
    }
}

object HostedAudioIds {
    const val SERVICE = "artzig.audio_ids"
    @JvmStatic fun nextId(use: Int): Int {
        val binder = ServiceManager.getService(SERVICE) ?: return 0
        return IHostedAudioIds.Stub.asInterface(binder).nextId(use)
    }
}
