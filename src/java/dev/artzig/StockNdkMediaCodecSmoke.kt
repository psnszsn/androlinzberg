package dev.artzig

internal object StockNdkMediaCodecSmoke {
    private external fun runNative()
    fun run() {
        runNative()
        println("stock-ndk-mediacodec: PASS C ABI formats, synchronous/asynchronous MPEG decoding, callbacks and cleanup")
    }
}
