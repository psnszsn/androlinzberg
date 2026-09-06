package dev.artzig

import android.hardware.HardwareBuffer

/** Reads a pixel through the stock lazy AHardwareBuffer image generator. */
internal object StockReadbackSmoke {
    @JvmStatic external fun pixel(buffer: HardwareBuffer): Int
}
