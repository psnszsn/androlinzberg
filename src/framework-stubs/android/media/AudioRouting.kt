package android.media

import android.os.Handler

/**
 * Hosted adaptation of AOSP AudioRouting.
 *
 * The vendored interface depends on media feature-flag sources that are outside the curated
 * framework closure. This keeps its public routing contract at the host audio seam; concrete
 * hosted tracks provide the fixed-route behavior.
 */
interface AudioRouting {
    fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean

    fun getPreferredDevice(): AudioDeviceInfo?

    fun getRoutedDevice(): AudioDeviceInfo?

    fun getRoutedDevices(): List<AudioDeviceInfo> = listOfNotNull(getRoutedDevice())

    fun addOnRoutingChangedListener(listener: OnRoutingChangedListener?, handler: Handler?)

    fun removeOnRoutingChangedListener(listener: OnRoutingChangedListener?)

    fun interface OnRoutingChangedListener {
        fun onRoutingChanged(router: AudioRouting)
    }
}
