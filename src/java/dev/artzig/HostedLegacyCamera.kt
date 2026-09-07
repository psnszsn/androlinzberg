package dev.artzig

import android.content.AttributionSourceState
import android.hardware.Camera
import android.hardware.ICameraClient
import android.hardware.ICameraService
import com.android.internal.camera.flags.Flags
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemProperties
import android.system.OsConstants
import dalvik.system.VMRuntime

/**
 * CameraBase's service discovery/queries and android_hardware_Camera's attribution and
 * connection-failure paths. The native camera capture/producer stack is absent here;
 * the generated service interface shares the existing hosted Binder transport.
 */
internal object HostedLegacyCamera {
    private fun attribution(parcel: Parcel, useContext: Boolean): AttributionSourceState {
        val state = try { AttributionSourceState.CREATOR.createFromParcel(parcel) }
        catch (error: RuntimeException) {
            throw RuntimeException("Fail to unparcel AttributionSourceState", error)
        }
        if (!(useContext && Flags.dataDeliveryPermissionChecks())) {
            state.uid = ICameraService.USE_CALLING_UID
            state.pid = ICameraService.USE_CALLING_PID
        }
        return state
    }

    private fun service(): ICameraService? {
        if (SystemProperties.getBoolean("config.disable_cameraservice", false)) return null
        // Hosted processes have no cameraserver to start or wait for.
        return ICameraService.Stub.asInterface(ServiceManager.getService("media.camera"))
    }

    private fun count(service: ICameraService?, state: AttributionSourceState, policy: Int): Int =
        try { service?.getNumberOfCameras(ICameraService.CAMERA_TYPE_BACKWARD_COMPATIBLE, state, policy) ?: 0 }
        catch (_: RemoteException) { 0 }
        catch (_: RuntimeException) { 0 }

    @JvmStatic fun count(parcel: Parcel, policy: Int): Int {
        val state = attribution(parcel, false)
        return count(service(), state, policy)
    }

    @JvmStatic fun info(id: Int, rotation: Int, parcel: Parcel, policy: Int, out: Camera.CameraInfo?) {
        val state = attribution(parcel, false)
        val service = service()
        if (id >= count(service, state, policy) || id < 0) throw RuntimeException("Unknown camera ID")
        val info = try { checkNotNull(service).getCameraInfo(id, rotation, state, policy) }
        catch (error: RemoteException) { throw RuntimeException("Fail to get camera info", error) }
        catch (error: RuntimeException) { throw RuntimeException("Fail to get camera info", error) }
        checkNotNull(out).facing = info.info.facing
        out.orientation = info.info.orientation
        out.canDisableShutterSound = SystemProperties.get("ro.camera.sound.forced", "0") == "0"
    }

    @JvmStatic fun connect(id: Int, rotation: Int, slowJpeg: Boolean, parcel: Parcel, policy: Int): Int {
        val state = attribution(parcel, true)
        val service = service() ?: return -OsConstants.EACCES
        val camera = try {
            service.connect(object : ICameraClient.Stub() {}, id,
                VMRuntime.getRuntime().targetSdkVersion, rotation, slowJpeg, state, policy)
        } catch (_: RemoteException) { return -OsConstants.EACCES }
        catch (_: RuntimeException) { return -OsConstants.EACCES }
        if (camera == null) return -OsConstants.EACCES
        try { camera.disconnect() } catch (_: RemoteException) { }
        throw ArtzigTestDiagnostics.unsupported("android.hardware.Camera", "native_setup",
            "Legacy camera capture and preview producers are not connected to the hosted window backend")
    }
}
