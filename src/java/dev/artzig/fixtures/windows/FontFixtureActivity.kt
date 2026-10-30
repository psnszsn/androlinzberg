package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Public text APIs over the system font map; layouts depend on these stock metrics. */
class FontFixtureActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Default font ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, 220))
            addView(Button(this@FontFixtureActivity).apply {
                text = "Check default font"
                setOnClickListener { verify() }
            })
        })
    }
    private fun paint(typeface: Typeface) = Paint().apply { textSize = 20f; this.typeface = typeface }
    private fun pixels(typeface: Typeface) = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawText("Conversations", 0f, 30f, paint(typeface))
    }
    private fun verify() {
        val sample = "Conversations"
        // Roboto's bounding box, which TextView's default font padding reserves.
        val metrics = paint(Typeface.DEFAULT).fontMetrics
        check(abs(metrics.top + 21.1f) < 0.5f && abs(metrics.bottom - 5.4f) < 0.5f) { "${metrics.top} ${metrics.bottom}" }
        // Stock families select these from one font's wght, ital and wdth axes.
        fun width(typeface: Typeface) = paint(typeface).measureText(sample)
        check(width(Typeface.create(Typeface.DEFAULT, 100, false)) < width(Typeface.create(Typeface.DEFAULT, 900, false)))
        check(!pixels(Typeface.DEFAULT).sameAs(pixels(Typeface.DEFAULT_BOLD)))
        check(!pixels(Typeface.DEFAULT).sameAs(pixels(Typeface.create(Typeface.DEFAULT, 400, true))))
        check(width(Typeface.create("sans-serif-condensed", Typeface.NORMAL)) < width(Typeface.DEFAULT))
        // Arabic is outside Roboto and comes from the host fallback chain.
        check(paint(Typeface.DEFAULT).hasGlyph("ب"))
        val line = TextView(this).apply {
            text = sample
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val unspecified = android.view.View.MeasureSpec.UNSPECIFIED
        line.measure(unspecified, unspecified)
        check(abs(line.measuredHeight - 27f * resources.displayMetrics.scaledDensity) < 2f) { line.measuredHeight }
        status.text = "Default font contracts verified"
        status.setBackgroundColor(Color.CYAN)
    }
}
