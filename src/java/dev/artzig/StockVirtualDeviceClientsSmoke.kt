package dev.artzig

import android.app.ActivityThread
import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Parcel
import java.util.concurrent.Executor

/** Stock client behavior for the host's default device and unavailable camera service. */
internal object StockVirtualDeviceClientsSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.simpleName}, got $failure" }
        return failure
    }
    fun run() {
        val context = checkNotNull(ActivityThread.currentApplication())
        val manager = checkNotNull(context.getSystemService(VirtualDeviceManager::class.java))
        check(manager.virtualDevices.isEmpty())
        check(manager.getVirtualDevice(Context.DEVICE_ID_DEFAULT) == null)
        check(manager.getVirtualDevice(42) == null)
        check(!manager.isValidVirtualDeviceId(42))
        check(manager.getDeviceIdForDisplayId(-1) == Context.DEVICE_ID_DEFAULT)
        check(manager.getDeviceIdForDisplayId(42) == Context.DEVICE_ID_DEFAULT)
        check(manager.getDevicePolicy(42, VirtualDeviceParams.POLICY_TYPE_AUDIO) == VirtualDeviceParams.DEVICE_POLICY_DEFAULT)
        check(manager.getAudioPlaybackSessionId(42) == 0 && manager.getAudioRecordingSessionId(42) == 0)
        check(manager.getDisplayNameForPersistentDeviceId("missing") == null)
        check(manager.allPersistentDeviceIds.isEmpty())
        val listener = object : VirtualDeviceManager.VirtualDeviceListener {
            override fun onVirtualDeviceCreated(deviceId: Int) { error("Unexpected device") }
            override fun onVirtualDeviceClosed(deviceId: Int) { error("Unexpected device") }
        }
        val executor = Executor { it.run() }
        manager.registerVirtualDeviceListener(executor, listener)
        manager.registerVirtualDeviceListener(executor, listener)
        manager.unregisterVirtualDeviceListener(listener)
        manager.unregisterVirtualDeviceListener(listener)
        val defaults = VirtualDeviceParams.Builder().build()
        check(defaults.lockState == VirtualDeviceParams.LOCK_STATE_DEFAULT)
        check(VirtualDeviceParams.POLICY_TYPE_CLIPBOARD == 4)
        val params = VirtualDeviceParams.Builder().setName("Fixture device")
            .setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_CLIPBOARD, VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
            .setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_AUDIO, VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
            .setAudioPlaybackSessionId(27).setAudioRecordingSessionId(28).build()
        check(params.getDevicePolicy(VirtualDeviceParams.POLICY_TYPE_ACTIVITY) == VirtualDeviceParams.DEVICE_POLICY_DEFAULT)
        check(params.getDevicePolicy(VirtualDeviceParams.POLICY_TYPE_CLIPBOARD) == VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
        val parcel = Parcel.obtain()
        try {
            params.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            check(VirtualDeviceParams.CREATOR.createFromParcel(parcel) == params)
        } finally { parcel.recycle() }
        rejects<IllegalArgumentException> { VirtualDeviceParams.Builder().setAudioPlaybackSessionId(27).build() }
        rejects<IllegalArgumentException> { VirtualDeviceParams.Builder().setAudioRecordingSessionId(-1) }
        rejects<SecurityException> { manager.createVirtualDevice(4242, defaults) }

        val track = android.media.AudioTrack.Builder().build()
        try {
            check(track.write(shortArrayOf(), 0, 0, android.media.AudioTrack.WRITE_NON_BLOCKING) == 0)
            check(track.write(shortArrayOf(1), 0, 1, -1) == android.media.AudioTrack.ERROR_BAD_VALUE)
            check(track.write(shortArrayOf(1), Int.MAX_VALUE, 1, android.media.AudioTrack.WRITE_BLOCKING) == android.media.AudioTrack.ERROR_BAD_VALUE)
            check(track.write(floatArrayOf(0f), 0, 1, android.media.AudioTrack.WRITE_NON_BLOCKING) == android.media.AudioTrack.ERROR_INVALID_OPERATION)
        } finally { track.release() }
        check(track.write(shortArrayOf(1), 0, 1, android.media.AudioTrack.WRITE_BLOCKING) == android.media.AudioTrack.ERROR_INVALID_OPERATION)

        val cameras = checkNotNull(context.getSystemService(CameraManager::class.java))
        check(cameras.cameraIdList.isEmpty())
        check(cameras.concurrentCameraIds.isEmpty())
        val unavailable = rejects<CameraAccessException> { cameras.getCameraCharacteristics("missing") }
        check(unavailable.reason == CameraAccessException.CAMERA_DISCONNECTED)
        // Camera keys are stock value types even though no capture device is available.
        check(CameraCharacteristics.LENS_FACING.name == "android.lens.facing")
        check(CameraCharacteristics.Key("android.lens.facing", Int::class.javaPrimitiveType!!) == CameraCharacteristics.LENS_FACING)
        check(CameraCharacteristics.Key("android.lens.facing", Int::class.javaObjectType) != CameraCharacteristics.LENS_FACING)
        println("stock virtual-device and camera clients: PASS")
    }
}
