/* artzig host adaptation of android.hardware.SystemSensorManager: the AOSP class binds the
 * sensor service through libsensor JNI (nativeCreate/nativeGetSensorAtIndex/nativeEnableSensor
 * and the SensorEventQueue natives), none of which exists on this desktop host. The real
 * abstract SensorManager is compiled unchanged (registerListener validation, the legacy
 * SensorListener adapter, rotation and orientation helpers, the dynamic sensor callback
 * bookkeeping); this subclass answers as a device with no sensors: empty lists, every
 * registration refused as SystemSensorManager refuses an unknown sensor, no direct
 * channels, no data injection. */
package android.hardware;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.MemoryFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SystemSensorManager extends SensorManager {
    private final Context mContext;
    private final Looper mMainLooper;
    private final List<Sensor> mFullSensorsList = new ArrayList<>();
    private final ArrayList<DynamicSensorCallback> mDynamicSensorCallbacks = new ArrayList<>();

    public SystemSensorManager(Context context, Looper mainLooper) {
        mContext = context;
        mMainLooper = mainLooper;
    }

    @Override
    protected List<Sensor> getFullSensorList() {
        return Collections.unmodifiableList(mFullSensorsList);
    }

    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        return Collections.emptyList();
    }

    /** SystemSensorManager.registerListenerImpl: an unknown sensor (there are none) is refused. */
    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delayUs, Handler handler, int maxReportLatencyUs, int reservedFlags) {
        if (listener == null || sensor == null) {
            throw new IllegalArgumentException("sensor or listener is null");
        }
        return false;
    }

    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
    }

    @Override
    protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        if (sensor == null) throw new IllegalArgumentException("sensor cannot be null");
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        return false;
    }

    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
        return false;
    }

    @Override
    protected boolean flushImpl(SensorEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        return false;
    }

    @Override
    protected boolean initDataInjectionImpl(boolean enable, int mode) {
        return false;
    }

    @Override
    protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
            long timestamp) {
        return false;
    }

    @Override
    protected void registerDynamicSensorCallbackImpl(DynamicSensorCallback callback,
            Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        synchronized (mDynamicSensorCallbacks) {
            if (!mDynamicSensorCallbacks.contains(callback)) mDynamicSensorCallbacks.add(callback);
        }
    }

    @Override
    protected void unregisterDynamicSensorCallbackImpl(DynamicSensorCallback callback) {
        synchronized (mDynamicSensorCallbacks) {
            mDynamicSensorCallbacks.remove(callback);
        }
    }

    @Override
    protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
        return false;
    }

    /** No sensor supports direct channels on this host (SensorManager reports it per sensor). */
    @Override
    protected SensorDirectChannel createDirectChannelImpl(MemoryFile memoryFile,
            android.hardware.HardwareBuffer hardwareBuffer) {
        throw new UncheckedIOException(new java.io.IOException("direct channels are not supported"));
    }

    @Override
    protected void destroyDirectChannelImpl(SensorDirectChannel channel) {
    }

    @Override
    protected int configureDirectChannelImpl(SensorDirectChannel channel, Sensor s, int rate) {
        return 0;
    }

    /** SensorManager.createDirectChannel's documented failure: UncheckedIOException. */
    private static final class UncheckedIOException extends java.io.UncheckedIOException {
        UncheckedIOException(java.io.IOException cause) {
            super(cause);
        }
    }
}
