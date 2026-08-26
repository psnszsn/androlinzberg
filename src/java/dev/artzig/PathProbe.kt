package dev.artzig

import android.graphics.Path

class PathProbe {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val path = Path()
            try {
                println("fresh fillType=${path.fillType}")
            } catch (error: Throwable) {
                println("fresh fillType FAILED: $error")
            }
            path.fillType = Path.FillType.EVEN_ODD
            try {
                println("after set fillType=${path.fillType}")
            } catch (error: Throwable) {
                println("after set FAILED: $error")
            }
            path.moveTo(0f, 0f)
            path.lineTo(10f, 10f)
            path.reset()
            println("reset ok, isEmpty=${path.isEmpty}")
            println("kotlin stdlib=${listOf("java", "kotlin").joinToString(" -> ")}")
        }
    }
}
