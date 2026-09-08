package dev.artzig

import android.os.SystemProperties

/** A cached native property handle must observe Java/broker updates and support reentrant reads. */
internal object StockNativePropertiesSmoke {
    private external fun find(key: String): Long
    private external fun observe(handle: Long, key: String, expected: String): Long
    private external fun put(key: String, value: String?): Int

    fun run() {
        val key = "debug.azk.native.property"
        check(find("ro.azk.native.missing") == 0L)
        SystemProperties.set(key, "first")
        val handle = find(key)
        check(handle != 0L)
        val before = observe(handle, key, "first")
        check(before >= 0)
        SystemProperties.set(key, "other")
        val after = observe(handle, key, "other")
        check(after >= 0 && after != before)
        check(put(key, "native") == 0)
        check(SystemProperties.get(key) == "native")
        check(observe(handle, key, "native") >= 0)
        check(put(key, null) == 0)
        check(SystemProperties.get(key) == "")
        check(observe(handle, key, "") >= 0)
        check(put("sys.azk.native.denied", "x") == -1)
        check(put("ro.build.version.sdk", "1") == -1)
        val longKey = "ro.build.version.known_codenames"
        val longValue = SystemProperties.get(longKey)
        check(longValue.length > 91)
        check(observe(find(longKey), longKey, longValue) >= 0)
        println("stock-native-properties: PASS shared handles, updates, reentrant callbacks, long values and write permissions")
    }
}
