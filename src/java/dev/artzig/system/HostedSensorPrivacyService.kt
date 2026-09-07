package dev.artzig.system

import android.Manifest
import android.app.AppOpsManager
import android.app.AppOpsManagerInternal
import android.content.Context
import android.hardware.ISensorPrivacyListener
import android.hardware.ISensorPrivacyManager
import android.hardware.SensorPrivacyManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle
import com.android.server.LocalServices

/**
 * SensorPrivacyService policy seam for one hosted user. Software toggles use the
 * original AppOpsManagerInternal global restrictions and callback ordering. There
 * are no hardware privacy switches, keyguard, automotive allowlist mode or SystemUI
 * reminder activity. State belongs to the broker session.
 */
class HostedSensorPrivacyService(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val restrict: (Int, Boolean, IBinder) -> Unit = { op, enabled, token ->
        LocalServices.getService(AppOpsManagerInternal::class.java).setGlobalRestriction(op, enabled, token)
    },
) : ISensorPrivacyManager.Stub() {
    private val lock = Any()
    private val restrictionToken = Binder()
    private var allSensors = false
    private val states = mutableMapOf<Int, Boolean>()
    private val allListeners = RemoteCallbackList<ISensorPrivacyListener>()
    private val toggleListeners = RemoteCallbackList<ISensorPrivacyListener>()
    private var cameraAllowlist: List<String> = emptyList()
    private fun observe() = context.enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_SENSOR_PRIVACY, "observe sensor privacy")
    private fun manage() = context.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY, "change sensor privacy")
    private fun user(id: Int) { require(id == 0 || id == UserHandle.USER_CURRENT) { "Unknown user $id" } }
    private fun sensor(id: Int) { require(id == SensorPrivacyManager.Sensors.MICROPHONE || id == SensorPrivacyManager.Sensors.CAMERA) { "Unknown sensor $id" } }
    override fun supportsSensorToggle(toggleType: Int, sensor: Int): Boolean {
        sensor(sensor)
        require(toggleType == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE || toggleType == SensorPrivacyManager.TOGGLE_TYPE_HARDWARE)
        return toggleType == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE
    }
    override fun isSensorPrivacyEnabled(): Boolean { observe(); return synchronized(lock) { allSensors } }
    override fun isToggleSensorPrivacyEnabled(toggleType: Int, sensor: Int): Boolean {
        observe(); val supported = supportsSensorToggle(toggleType, sensor)
        return supported && synchronized(lock) { states[sensor] == true }
    }
    override fun isCombinedToggleSensorPrivacyEnabled(sensor: Int): Boolean =
        isToggleSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, sensor)
    override fun getToggleSensorPrivacyState(toggleType: Int, sensor: Int): Int =
        if (isToggleSensorPrivacyEnabled(toggleType, sensor)) SensorPrivacyManager.StateTypes.ENABLED else SensorPrivacyManager.StateTypes.DISABLED
    override fun isCameraPrivacyEnabled(packageName: String?): Boolean =
        isCombinedToggleSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA)
    override fun setSensorPrivacy(enable: Boolean) {
        manage()
        synchronized(lock) {
            if (allSensors == enable) return
            allSensors = enable
            handler.post { notify(allListeners, -1, -1, enable) }
        }
    }
    override fun setToggleSensorPrivacy(userId: Int, source: Int, sensor: Int, enable: Boolean) {
        manage(); user(userId); sensor(sensor)
        synchronized(lock) {
            if ((states[sensor] == true) == enable) return
            states[sensor] = enable
            handler.post {
                val ops = if (sensor == SensorPrivacyManager.Sensors.MICROPHONE) intArrayOf(
                    AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO,
                    AppOpsManager.OP_PHONE_CALL_MICROPHONE, AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO)
                    else intArrayOf(AppOpsManager.OP_CAMERA, AppOpsManager.OP_PHONE_CALL_CAMERA)
                for (op in ops) restrict(op, enable, restrictionToken)
                if (sensor == SensorPrivacyManager.Sensors.MICROPHONE) {
                    val allowed = android.provider.Settings.Global.getInt(context.contentResolver,
                        android.provider.Settings.Global.RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO_ENABLED, 1) == 1
                    restrict(AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO,
                        enable && !allowed, restrictionToken)
                }
                notify(toggleListeners, SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, sensor, enable)
            }
        }
    }
    override fun setToggleSensorPrivacyForProfileGroup(userId: Int, source: Int, sensor: Int, enable: Boolean) =
        setToggleSensorPrivacy(userId, source, sensor, enable)
    override fun setToggleSensorPrivacyState(userId: Int, source: Int, sensor: Int, state: Int) {
        manage()
        if (state != SensorPrivacyManager.StateTypes.ENABLED && state != SensorPrivacyManager.StateTypes.DISABLED)
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("SensorPrivacyService", "setToggleSensorPrivacyState", "automotive allowlist mode is not hosted")
        setToggleSensorPrivacy(userId, source, sensor, state == SensorPrivacyManager.StateTypes.ENABLED)
    }
    override fun setToggleSensorPrivacyStateForProfileGroup(userId: Int, source: Int, sensor: Int, state: Int) =
        setToggleSensorPrivacyState(userId, source, sensor, state)
    override fun getCameraPrivacyAllowlist(): List<String> { observe(); return synchronized(lock) { cameraAllowlist.toList() } }
    override fun setCameraPrivacyAllowlist(allowlist: List<String>?) {
        manage(); synchronized(lock) { cameraAllowlist = ArrayList(java.util.Objects.requireNonNull(allowlist)) }
    }
    override fun addSensorPrivacyListener(listener: ISensorPrivacyListener?) {
        observe()
        if (listener == null) throw NullPointerException("listener cannot be null")
        allListeners.register(listener)
    }
    override fun removeSensorPrivacyListener(listener: ISensorPrivacyListener?) {
        observe()
        if (listener == null) throw NullPointerException("listener cannot be null")
        allListeners.unregister(listener)
    }
    override fun addToggleSensorPrivacyListener(listener: ISensorPrivacyListener?) {
        observe()
        if (listener == null) throw IllegalArgumentException("listener cannot be null")
        toggleListeners.register(listener)
    }
    override fun removeToggleSensorPrivacyListener(listener: ISensorPrivacyListener?) {
        observe()
        if (listener == null) throw IllegalArgumentException("listener cannot be null")
        toggleListeners.unregister(listener)
    }
    override fun requiresAuthentication(): Boolean { observe(); return false } // No hosted keyguard.
    override fun suppressToggleSensorPrivacyReminders(userId: Int, sensor: Int, token: IBinder?, suppress: Boolean) {
        manage(); user(userId); sensor(sensor)
        java.util.Objects.requireNonNull(token)
        // No reminder activity exists, so suppression cannot change an observation.
    }
    override fun showSensorUseDialog(sensor: Int) {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) throw SecurityException("Can only be called by the system uid")
        if (isCombinedToggleSensorPrivacyEnabled(sensor)) throw dev.artzig.ArtzigTestDiagnostics.unsupported(
            "SensorPrivacyService", "showSensorUseDialog", "SystemUI sensor-use reminder activity is not hosted")
    }
    private fun notify(listeners: RemoteCallbackList<ISensorPrivacyListener>, type: Int, sensor: Int, enabled: Boolean) {
        val count = listeners.beginBroadcast()
        try {
            for (index in 0 until count) try {
                listeners.getBroadcastItem(index).onSensorPrivacyChanged(type, sensor, enabled)
            } catch (_: RemoteException) { /* RemoteCallbackList owns death cleanup. */ }
        } finally { listeners.finishBroadcast() }
    }
}
