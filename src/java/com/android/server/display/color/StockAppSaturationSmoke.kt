package com.android.server.display.color

import java.lang.ref.WeakReference
import kotlin.math.abs

/** Stock activity transform delivery, requester arbitration, and weak owner lifetime. */
object StockAppSaturationSmoke {
    fun run() {
        val service = AppSaturationController()
        val frames = ArrayList<FloatArray>()
        val controller = ColorDisplayService.ColorTransformController { matrix, translation ->
            check(matrix.size == 9 && translation.contentEquals(floatArrayOf(0f, 0f, 0f)))
            frames.add(matrix.clone())
        }
        val owner = WeakReference(controller)
        check(!service.setSaturationLevel("requester-a", "target", 0, 50))
        check(service.addColorTransformController("target", 0, owner))
        check(frames.size == 1)
        // AOSP's column-major luminance coefficients and half-saturation transform.
        val expected = floatArrayOf(.6155f, .1155f, .1155f, .3575f, .8575f, .3575f, .036f, .036f, .536f)
        check(frames.last().indices.all { abs(frames.last()[it] - expected[it]) < .00001f })
        check(service.setSaturationLevel("requester-b", "target", 0, 0))
        val gray = frames.last()
        check(gray[0] == gray[1] && gray[1] == gray[2])
        check(service.setSaturationLevel("requester-a", "target", 0, 100))
        check(frames.last().contentEquals(gray)) // the strongest remaining request wins
        check(!service.setSaturationLevel("requester-c", "target", 10, 0))
        check(!service.setSaturationLevel("requester-c", "other", 0, 0))
        check(frames.size == 3)
        check(service.setSaturationLevel("requester-b", "target", 0, 100))
        check(frames.last().contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        owner.clear()
        check(!service.setSaturationLevel("requester-a", "target", 0, 75))
        check(frames.size == 4)
        val next = WeakReference(controller)
        check(service.addColorTransformController("target", 0, next))
        check(frames.size == 5) // a new activity immediately receives retained package state
    }
}
