package dev.artzig.testing

import android.content.res.Configuration

import android.content.pm.ActivityInfo
import kotlin.math.max
import kotlin.math.min

class HostedWindowConfiguration internal constructor(
    val configuration: Configuration,
    val changes: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val density: Float,
    val densityDpi: Int,
    val landscape: Boolean,
)

/**
 * Calculates resource configurations for isolated compatibility fixtures.
 *
 * This follows AOSP Configuration.diff(), updateFrom(), resetScreenLayout(),
 * and reduceScreenLayout(). Production configuration is owned by the session
 * display service and stock WM; this utility is used only by smoke tests.
 */
object HostedConfigurationController {
    @JvmStatic
    fun calculate(
        current: Configuration,
        requestedOrientation: Int,
        width: Int,
        height: Int,
        scale120: Int,
    ): HostedWindowConfiguration {
        require(width > 0 && height > 0) { "window dimensions must be positive" }
        require(scale120 > 0) { "window scale must be positive" }

        val landscape = resolveLandscape(requestedOrientation, width, height)
        val displayWidth = if (landscape) max(width, height) else min(width, height)
        val displayHeight = if (landscape) min(width, height) else max(width, height)
        val density = scale120 / 120f
        val densityDpi = (160f * density).toInt()
        val widthDp = (displayWidth / density).toInt()
        val heightDp = (displayHeight / density).toInt()
        val smallestWidthDp = min(widthDp, heightDp)

        val next = Configuration(current).apply {
            this.densityDpi = densityDpi
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            smallestScreenWidthDp = smallestWidthDp
            compatScreenWidthDp = widthDp
            compatScreenHeightDp = heightDp
            compatSmallestScreenWidthDp = smallestWidthDp
            windowConfiguration.setBounds(android.graphics.Rect(0, 0, displayWidth, displayHeight))
            windowConfiguration.setAppBounds(0, 0, displayWidth, displayHeight)
            windowConfiguration.setMaxBounds(0, 0, displayWidth, displayHeight)
            orientation = if (landscape) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            screenLayout = Configuration.reduceScreenLayout(
                Configuration.resetScreenLayout(screenLayout),
                max(widthDp, heightDp),
                min(widthDp, heightDp),
            )
        }

        return HostedWindowConfiguration(
            configuration = next,
            changes = current.diff(next),
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            density = density,
            densityDpi = densityDpi,
            landscape = landscape,
        )
    }

    @JvmStatic
    fun resolveLandscape(requestedOrientation: Int, width: Int, height: Int): Boolean = when {
        ActivityInfo.isFixedOrientationLandscape(requestedOrientation) -> true
        ActivityInfo.isFixedOrientationPortrait(requestedOrientation) -> false
        else -> width >= height
    }
}
