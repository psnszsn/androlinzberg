package dev.artzig

internal object StockFenceSmoke {
    @JvmStatic private external fun nativeTest()

    fun run() {
        nativeTest()
        System.err.println("stock-fence: native waits, shared ownership and Parcel descriptor lifetime passed")
    }
}
