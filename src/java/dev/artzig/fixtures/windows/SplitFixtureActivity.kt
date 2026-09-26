package dev.artzig.fixtures.windows

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** This class and its asset exist only in the feature APK, never in the base. */
class SplitFixtureActivity : Activity() {
    private external fun nativeValue(): Int
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(applicationInfo.splitNames.contentEquals(arrayOf("feature")))
        check(checkNotNull(applicationInfo.splitSourceDirs).size == 1)
        System.loadLibrary("xapk_fixture")
        check(nativeValue() == 73)
        val asset = assets.open("split.txt").bufferedReader().use { it.readText().trim() }
        check(asset == "Asset from feature split")
        val expansion = File(obbDir, "main.1.dev.artzig.fixtures.xapk.obb").readText().trim()
        check(expansion == "Expansion data from XAPK")
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.CYAN)
            for (label in arrayOf("Split code loaded", "Split native library loaded", asset, expansion)) {
                addView(TextView(this@SplitFixtureActivity).apply { text = label; textSize = 20f })
            }
        })
    }
}
