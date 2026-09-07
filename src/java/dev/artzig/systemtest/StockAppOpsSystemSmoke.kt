package dev.artzig.systemtest

import android.app.AppOpsManager
import android.content.Context
import android.hardware.SensorPrivacyManager
import android.os.Binder
import android.os.Process
import android.os.ServiceManager
import com.android.internal.app.IAppOpsActiveCallback
import com.android.internal.app.IAppOpsCallback
import com.android.internal.app.IAppOpsNotedCallback
import com.android.internal.app.IAppOpsService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The platform-signed B fixture exercises policy management; A still tests denial. */
object StockAppOpsSystemSmoke {
    fun run(context: Context) {
        val service = IAppOpsService.Stub.asInterface(ServiceManager.getService(Context.APP_OPS_SERVICE))
        val uid = Process.myUid()
        val pkg = context.packageName
        val op = AppOpsManager.OP_VIBRATE
        fun mode() = service.checkOperation(op, uid, pkg)
        check(mode() == AppOpsManager.MODE_ALLOWED)
        check(service.checkOperation(AppOpsManager.OP_MOCK_LOCATION, uid, pkg) == AppOpsManager.opToDefaultMode(AppOpsManager.OP_MOCK_LOCATION))
        check(runCatching { service.checkOperation(AppOpsManager._NUM_OP, uid, pkg) }.exceptionOrNull() is IllegalArgumentException)
        val modes = LinkedBlockingQueue<Int>()
        val watcher = object : IAppOpsCallback.Stub() {
            override fun opChanged(code: Int, changedUid: Int, packageName: String?, persistentDeviceId: String?) {
                check(code == op && changedUid == uid && packageName == pkg)
                check(persistentDeviceId == android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)
                modes.add(code)
            }
        }
        val active = LinkedBlockingQueue<Boolean>()
        val activeWatcher = object : IAppOpsActiveCallback.Stub() {
            override fun opActiveChanged(code: Int, changedUid: Int, packageName: String?, attributionTag: String?,
                deviceId: Int, isActive: Boolean, flags: Int, chain: Int) {
                check(code == op && changedUid == uid && packageName == pkg && deviceId == Context.DEVICE_ID_DEFAULT)
                active.add(isActive)
            }
        }
        val noted = LinkedBlockingQueue<Int>()
        val noteWatcher = object : IAppOpsNotedCallback.Stub() {
            override fun opNoted(code: Int, changedUid: Int, packageName: String?, attributionTag: String?,
                deviceId: Int, flags: Int, mode: Int) {
                check(code == op && changedUid == uid && packageName == pkg)
                noted.add(mode)
            }
        }
        fun changed() { check(modes.poll(5, TimeUnit.SECONDS) == op) { "missing AppOps mode callback" } }
        val token = Binder()
        service.startWatchingMode(op, pkg, watcher)
        service.startWatchingMode(op, pkg, watcher) // A repeated registration must not duplicate callbacks.
        service.startWatchingActive(intArrayOf(op), activeWatcher)
        service.startWatchingNoted(intArrayOf(op), noteWatcher)
        try {
            service.setMode(op, uid, pkg, AppOpsManager.MODE_IGNORED)
            changed()
            check(mode() == AppOpsManager.MODE_IGNORED)
            check(modes.poll(150, TimeUnit.MILLISECONDS) == null) { "duplicate registration notified twice" }
            service.setMode(op, uid, pkg, AppOpsManager.MODE_IGNORED)
            check(modes.poll(150, TimeUnit.MILLISECONDS) == null) { "unchanged mode notified a watcher" }
            check(service.noteOperation(op, uid, pkg, null, false, null, false).opMode == AppOpsManager.MODE_IGNORED)
            check(noted.poll(5, TimeUnit.SECONDS) == AppOpsManager.MODE_IGNORED)
            service.setUidMode(op, uid, AppOpsManager.MODE_ERRORED)
            changed()
            check(mode() == AppOpsManager.MODE_ERRORED) // UID mode takes precedence over the package.
            service.setUidMode(op, uid, AppOpsManager.MODE_ALLOWED)
            changed()
            check(mode() == AppOpsManager.MODE_IGNORED) // Restoring the default removes the UID override.
            service.resetAllModes(0, pkg)
            // Pinned resetAllModes reports both its reset batch and the checking
            // store's mode change. Neither is an unchanged setMode notification.
            changed()
            changed()
            check(mode() == AppOpsManager.MODE_ALLOWED)
            check(service.noteOperation(op, uid, pkg, null, false, null, false).opMode == AppOpsManager.MODE_ALLOWED)
            check(noted.poll(5, TimeUnit.SECONDS) == AppOpsManager.MODE_ALLOWED)
            fun start() = service.startOperation(token, op, uid, pkg, null, false, false, null, false, 0, -1)
            check(start().opMode == AppOpsManager.MODE_ALLOWED)
            check(active.poll(5, TimeUnit.SECONDS) == true)
            check(start().opMode == AppOpsManager.MODE_ALLOWED)
            check(service.isOperationActive(op, uid, pkg))
            service.finishOperation(token, op, uid, pkg, null)
            check(service.isOperationActive(op, uid, pkg))
            check(active.poll(150, TimeUnit.MILLISECONDS) == null) { "nested start/finish changed activity" }
            service.finishOperation(token, op, uid, pkg, null)
            check(active.poll(5, TimeUnit.SECONDS) == false)
            check(!service.isOperationActive(op, uid, pkg))
            val entries = service.getOpsForPackage(uid, pkg, intArrayOf(op))
            check(entries.size == 1 && entries.single().ops.single().op == op)
            check(entries.single().ops.single().getLastAccessTime(AppOpsManager.OP_FLAGS_ALL) > 0)
        } finally {
            service.stopWatchingMode(watcher)
            service.stopWatchingActive(activeWatcher)
            service.stopWatchingNoted(noteWatcher)
            service.setUidMode(op, uid, AppOpsManager.MODE_ALLOWED)
            service.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED)
        }
        testRestrictions(context, service)
        testPrivacy(context)
        testModePersistence(context)
        System.err.println("stock-appops-system: PASS modes, callbacks, nested accounting, privacy and persistence")
    }

    private fun testRestrictions(context: Context, service: IAppOpsService) {
        val token = Binder()
        val uid = Process.myUid()
        val pkg = context.packageName
        val op = AppOpsManager.OP_VIBRATE
        try {
            service.setUserRestriction(op, true, token, 0, null)
            check(service.checkOperation(op, uid, pkg) == AppOpsManager.MODE_IGNORED)
            val excluded = android.os.PackageTagsList.Builder().add(pkg).build()
            service.setUserRestriction(op, true, token, 0, excluded)
            check(service.checkOperation(op, uid, pkg) == AppOpsManager.MODE_ALLOWED)
            check(service.noteOperation(op, uid, pkg, null, false, null, false).opMode == AppOpsManager.MODE_ALLOWED)
            val audioOp = AppOpsManager.OP_PLAY_AUDIO
            val usage = android.media.AudioAttributes.USAGE_ALARM
            service.setAudioRestriction(audioOp, usage, uid, AppOpsManager.MODE_IGNORED, null)
            check(service.checkAudioOperation(audioOp, usage, uid, pkg) == AppOpsManager.MODE_IGNORED)
            check(service.checkOperation(audioOp, uid, pkg) == AppOpsManager.MODE_ALLOWED)
            service.setAudioRestriction(audioOp, usage, uid, AppOpsManager.MODE_IGNORED, arrayOf("  $pkg  "))
            check(service.checkAudioOperation(audioOp, usage, uid, pkg) == AppOpsManager.MODE_ALLOWED)
        } finally {
            service.setUserRestriction(op, false, token, 0, null)
            service.setAudioRestriction(AppOpsManager.OP_PLAY_AUDIO, android.media.AudioAttributes.USAGE_ALARM,
                uid, AppOpsManager.MODE_ALLOWED, null)
        }
    }

    private fun testPrivacy(context: Context) {
        val client = checkNotNull(context.getSystemService(SensorPrivacyManager::class.java))
        check(client === context.getSystemService(Context.SENSOR_PRIVACY_SERVICE))
        check(client.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA))
        val remote = android.hardware.ISensorPrivacyManager.Stub.asInterface(ServiceManager.getService(Context.SENSOR_PRIVACY_SERVICE))
        check(runCatching { remote.isSensorPrivacyEnabled }.exceptionOrNull() is SecurityException)
        check(runCatching { remote.setSensorPrivacy(true) }.exceptionOrNull() is SecurityException)
        // These are internal|role|installer permissions, not signature grants.
        // Exercise the policy seam with an authorized context and an in-memory
        // restriction backend; Binder user/audio restrictions are tested above.
        val allowedContext = object : android.content.ContextWrapper(context) {
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
                check(permission in setOf(android.Manifest.permission.MANAGE_SENSOR_PRIVACY,
                    android.Manifest.permission.OBSERVE_SENSOR_PRIVACY))
            }
        }
        val thread = android.os.HandlerThread("privacy-policy-smoke").apply { start() }
        val restrictions = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        val privacy = dev.artzig.system.HostedSensorPrivacyService(allowedContext, android.os.Handler(thread.looper)) {
            op, restricted, _ -> restrictions[op] = restricted
        }
        val camera = SensorPrivacyManager.Sensors.CAMERA
        check(!privacy.supportsSensorToggle(SensorPrivacyManager.TOGGLE_TYPE_HARDWARE, camera))
        val changes = LinkedBlockingQueue<Boolean>()
        val listener = object : android.hardware.ISensorPrivacyListener.Stub() {
            override fun onSensorPrivacyChanged(type: Int, sensor: Int, enabled: Boolean) {
                check(type == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE && sensor == camera)
                changes.add(enabled)
            }
            override fun onSensorPrivacyStateChanged(type: Int, sensor: Int, state: Int) {}
        }
        check(runCatching { privacy.addSensorPrivacyListener(null) }.exceptionOrNull() is NullPointerException)
        check(runCatching { privacy.removeSensorPrivacyListener(null) }.exceptionOrNull() is NullPointerException)
        check(runCatching { privacy.addToggleSensorPrivacyListener(null) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { privacy.removeToggleSensorPrivacyListener(null) }.exceptionOrNull() is IllegalArgumentException)
        privacy.addToggleSensorPrivacyListener(listener)
        try {
            privacy.setToggleSensorPrivacy(0, SensorPrivacyManager.Sources.OTHER, camera, true)
            check(changes.poll(5, TimeUnit.SECONDS) == true)
            check(privacy.isCombinedToggleSensorPrivacyEnabled(camera))
            check(restrictions[AppOpsManager.OP_CAMERA] == true && restrictions[AppOpsManager.OP_PHONE_CALL_CAMERA] == true)
            privacy.setToggleSensorPrivacy(0, SensorPrivacyManager.Sources.OTHER, camera, false)
            check(changes.poll(5, TimeUnit.SECONDS) == false)
            check(restrictions[AppOpsManager.OP_CAMERA] == false && restrictions[AppOpsManager.OP_PHONE_CALL_CAMERA] == false)
        } finally {
            privacy.removeToggleSensorPrivacyListener(listener)
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive)
        }
    }

    private fun testModePersistence(context: Context) {
        // The real legacy store has a package-private constructor. Reflection is test-only;
        // production construction belongs to HostedAppOpsBootstrap in its server package.
        val type = com.android.server.appop.AppOpsCheckingServiceImpl::class.java
        val constructor = type.getDeclaredConstructor(java.io.File::class.java, Any::class.java,
            android.os.Handler::class.java, Context::class.java, android.util.SparseArray::class.java)
        constructor.isAccessible = true
        val directory = java.nio.file.Files.createTempDirectory("appops-state-smoke").toFile()
        val file = java.io.File(directory, "modes.xml")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun create() = constructor.newInstance(file, Any(), handler, context, android.util.SparseArray<IntArray>()) as
            com.android.server.appop.AppOpsCheckingServiceInterface
        val first = create()
        val second = create()
        val uid = Process.myUid()
        val device = android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
        val op = AppOpsManager.OP_VIBRATE
        try {
            check(first.getUidMode(uid, device, op) == AppOpsManager.opToDefaultMode(op))
            check(first.setUidMode(uid, device, op, AppOpsManager.MODE_IGNORED))
            check(!first.setUidMode(uid, device, op, AppOpsManager.MODE_IGNORED))
            first.setPackageMode(context.packageName, op, AppOpsManager.MODE_ERRORED, 0)
            first.getNonDefaultUidModes(uid, device).clear() // Snapshots must not mutate the store.
            check(first.getUidMode(uid, device, op) == AppOpsManager.MODE_IGNORED)
            first.writeState()
            second.readState()
            check(second.getUidMode(uid, device, op) == AppOpsManager.MODE_IGNORED)
            check(second.getPackageMode(context.packageName, op, 0) == AppOpsManager.MODE_ERRORED)
            second.setUidMode(uid, device, op, AppOpsManager.opToDefaultMode(op))
            check(second.getNonDefaultUidModes(uid, device).size() == 0)
            check(second.removePackage(context.packageName, 0))
            check(second.getPackageMode(context.packageName, op, 0) == AppOpsManager.opToDefaultMode(op))
        } finally { first.shutdown(); second.shutdown(); directory.deleteRecursively() }
    }

}
