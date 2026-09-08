package dev.artzig

internal object StockAAudioSmoke {
    private external fun runNative()
    fun run() {
        runNative()
        println("stock-aaudio: PASS C ABI validation, PCM writes, state, timing, native callbacks and cleanup")
    }
}
