// artzig M2 smoke: real Resources stack over androidfw — framework-res.apk.
package dev.artzig

import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue

public final class ResSmoke {
    private constructor () {}

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            val path: String = System.getenv("ARTZIG_FRAMEWORK_RES")
            System.out.println("1: ApkAssets.loadFromPath " + path)
            val apk: ApkAssets = ApkAssets.loadFromPath(path, ApkAssets.PROPERTY_SYSTEM)

            System.out.println("2: AssetManager (system assets from " + path + ")")
            AssetManager.createSystemAssetsInZygoteLocked(true, path)
            val am: AssetManager = AssetManager.Builder().addApkAssets(apk).build()

            System.out.println("3: Resources")
            val dm: DisplayMetrics = DisplayMetrics()
            dm.setToDefaults()
            val config: Configuration = Configuration()
            config.setToDefaults()
            val res: Resources = android.content.res.HostedResources.create(am, dm, config)

            System.out.println("4: getText(android.R.string.ok) = " + res.getText(android.R.string.ok))
            System.out.println("5: getText(android.R.string.cancel) = " + res.getText(android.R.string.cancel))

            val tv: TypedValue = TypedValue()
            res.getValue(android.R.color.white, tv, true)
            System.out.println("6: color white = 0x" + Integer.toHexString(tv.data))

            System.out.println("7: theme + styled attrs")
            val theme: Resources.Theme = res.newTheme()
            theme.applyStyle(android.R.style.Theme_DeviceDefault, true)
            val ta: TypedArray = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
            System.out.println("8: colorBackground = 0x" + Integer.toHexString(ta.getColor(0, 0xdead)))
            ta.recycle()

            System.out.println("9: drawable (ColorDrawable)")
            val d: Drawable = res.getDrawable(android.R.drawable.screen_background_dark, theme)
            System.out.println("10: drawable = " + d)

            System.out.println("11: hosted configuration update")
            val updatedMetrics: DisplayMetrics = DisplayMetrics()
            updatedMetrics.setToDefaults()
            updatedMetrics.densityDpi = 320.also { updatedMetrics.noncompatDensityDpi = it }
            updatedMetrics.density = 2f.also { updatedMetrics.noncompatDensity = it }
            updatedMetrics.noncompatScaledDensity = updatedMetrics.density
            val updatedConfig: Configuration = Configuration(config)
            updatedConfig.densityDpi = updatedMetrics.densityDpi
            android.content.res.HostedResources.update(res, updatedConfig, updatedMetrics)
            check(res.getConfiguration().densityDpi == 320)
            check(res.getDisplayMetrics().density == 2f)

            System.out.println("done")
        }
    }
}
