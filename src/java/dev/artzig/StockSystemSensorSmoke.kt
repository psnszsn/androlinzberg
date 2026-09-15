package dev.artzig

import android.app.ActivityThread
import android.content.Context
import android.hardware.HardwareBuffer
import android.hardware.Sensor
import android.hardware.SensorAdditionalInfo
import android.hardware.SensorEventCallback
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SystemSensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Looper
import android.os.MemoryFile
import java.io.UncheckedIOException
import java.lang.reflect.InvocationTargetException

/** Original JNI/libsensor contracts against the system service with no sensor HAL. */
internal object StockSystemSensorSmoke {
    private external fun nativeVerifyNdk(packageName: String): Int
    private inline fun <reified T : Throwable> rejects(action: () -> Any?): T {
        var failure = runCatching(action).exceptionOrNull()
        if (failure is InvocationTargetException) failure = failure.cause
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
        return failure
    }
    fun run(drainMainQueue: () -> Unit) {
        val context = checkNotNull(ActivityThread.currentApplication())
        check(android.os.ServiceManager.checkService("sensorservice") != null)
        val manager = checkNotNull(context.getSystemService(SensorManager::class.java))
        check(manager.javaClass == SystemSensorManager::class.java)
        check(manager === context.getSystemService(Context.SENSOR_SERVICE))
        check(manager.getSensorList(Sensor.TYPE_ALL).isEmpty())
        check(manager.getSensorList(Sensor.TYPE_ACCELEROMETER).isEmpty())
        check(manager.getSensorList(Sensor.TYPE_ALL) === manager.getSensorList(Sensor.TYPE_ALL))
        check(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null)
        check(manager.getSensorByHandle(123) == null)
        check(manager.getDynamicSensorList(Sensor.TYPE_ALL).isEmpty())
        check(!manager.isDynamicSensorDiscoverySupported)
        val listener = object : SensorEventCallback() {}
        check(!manager.registerListener(listener, null, SensorManager.SENSOR_DELAY_NORMAL))
        check(!manager.registerListener(null as SensorEventListener?, null, SensorManager.SENSOR_DELAY_NORMAL))
        check(!manager.flush(listener))
        rejects<IllegalArgumentException> { manager.flush(null) }
        val trigger = object : TriggerEventListener() { override fun onTrigger(event: TriggerEvent) {} }
        rejects<IllegalArgumentException> { manager.requestTriggerSensor(trigger, null) }
        check(!manager.cancelTriggerSensor(trigger, null))
        manager.unregisterListener(listener)
        check(!manager.initDataInjection(true))
        check(manager.initDataInjection(false))
        for (mode in listOf(SensorManager.DATA_INJECTION, SensorManager.REPLAY_DATA_INJECTION,
            SensorManager.HAL_BYPASS_REPLAY_DATA_INJECTION)) {
            check(!manager.initDataInjection(true, mode))
            check(manager.initDataInjection(false, mode))
        }
        rejects<NullPointerException> { manager.setOperationParameter(null) }
        check(!manager.setOperationParameter(SensorAdditionalInfo.createLocalGeomagneticField(30f, 1f, 0f)))
        var callbacks = 0
        val dynamic = object : SensorManager.DynamicSensorCallback() {
            override fun onDynamicSensorConnected(sensor: Sensor) { callbacks++ }
            override fun onDynamicSensorDisconnected(sensor: Sensor) { callbacks++ }
        }
        rejects<IllegalArgumentException> { manager.registerDynamicSensorCallback(null) }
        manager.registerDynamicSensorCallback(dynamic)
        manager.registerDynamicSensorCallback(dynamic)
        drainMainQueue()
        check(callbacks == 0)
        repeat(2) { manager.unregisterDynamicSensorCallback(dynamic) }
        manager.unregisterDynamicSensorCallback(null)
        val create = SensorManager::class.java.getMethod("createDirectChannel", MemoryFile::class.java)
        rejects<NullPointerException> { create.invoke(manager, null) }
        val createHardware = SensorManager::class.java.getMethod("createDirectChannel", HardwareBuffer::class.java)
        rejects<NullPointerException> { createHardware.invoke(manager, null) }
        val small = MemoryFile("sensor-small", 1)
        try { rejects<IllegalArgumentException> { manager.createDirectChannel(small) } } finally { small.close() }
        val memory = MemoryFile("sensor-channel", 104)
        try {
            val failure = rejects<UncheckedIOException> { manager.createDirectChannel(memory) }
            check(failure.cause?.message == "create MemoryFile direct channel failed -12")
            check(memory.fileDescriptor.valid())
            memory.writeBytes(byteArrayOf(17), 0, 0, 1)
            val result = ByteArray(1)
            check(memory.readBytes(result, 0, 0, 1) == 1 && result[0] == 17.toByte())
        } finally { memory.close() }
        rejects<IllegalStateException> { manager.createDirectChannel(memory) }
        // The public API cannot obtain a physical sensor from the empty inventory.
        // A real BitTube queue can be created, but a fabricated sensor cannot be enabled.
        val sensor = Sensor::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        check(!manager.registerListener(listener, sensor, -1))
        repeat(3) { check(!manager.registerListener(listener, sensor, 20_000)) }
        check(!manager.flush(listener))
        manager.unregisterListener(listener)
        // No sensor-service writes occur on an empty result, including caller-owned lists.
        val nativeManager = SystemSensorManager::class.java.getDeclaredField("mNativeInstance").apply { isAccessible = true }.getLong(manager)
        val nativeDynamic = SystemSensorManager::class.java.getDeclaredMethod("nativeGetDynamicSensors",
            java.lang.Long.TYPE, java.util.List::class.java).apply { isAccessible = true }
        val supplied = arrayListOf(sensor)
        nativeDynamic.invoke(null, nativeManager, supplied)
        check(supplied.size == 1 && supplied[0] === sensor)
        check(SystemSensorManager(context, Looper.getMainLooper()).getSensorList(Sensor.TYPE_ALL).isEmpty())
        val nativeResult = nativeVerifyNdk(context.packageName)
        check(nativeResult == 0) { "NDK sensor contract failed at check $nativeResult" }
        System.err.println("stock-system-sensors: PASS original JNI/Binder discovery, validation, callbacks, injection, unsupported sensor enable and borrowed channels")
    }
}
