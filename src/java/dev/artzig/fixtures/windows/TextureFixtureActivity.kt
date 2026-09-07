package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Public TextureView contract: CPU producer, HWUI consumer, readback and ownership. */
class TextureFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply { text = "Waiting for texture" }
        content.addView(status)
        val texture = TextureView(this).apply { contentDescription = "Fixture texture pixels" }
        var expectedWidth = 160
        var expectedHeight = 100
        var expectedColor = Color.CYAN
        var generation = 0
        var verifiedGeneration = -1
        var available = 0
        var resized = 0
        var destroyed = 0
        fun draw() {
            val canvas = checkNotNull(texture.lockCanvas())
            try {
                check(canvas.width == expectedWidth && canvas.height == expectedHeight)
                canvas.drawColor(expectedColor)
            } finally { texture.unlockCanvasAndPost(canvas) }
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                check(++available == 1)
                check(texture.isAvailable && texture.surfaceTexture === surface)
                check(width == expectedWidth && height == expectedHeight)
                draw()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                check(available == 1 && ++resized == 1)
                check(width == expectedWidth && height == expectedHeight)
                draw()
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // getBitmap must run outside drawing; it waits for the HWUI layer's readback.
                val frameGeneration = generation
                texture.post {
                    if (!texture.isAvailable || generation != frameGeneration ||
                        verifiedGeneration == frameGeneration) return@post
                    val bitmap = checkNotNull(texture.bitmap)
                    try {
                        check(bitmap.width == expectedWidth && bitmap.height == expectedHeight)
                        check(bitmap.getPixel(2, 2) == expectedColor)
                        check(bitmap.getPixel(bitmap.width - 3, bitmap.height - 3) == expectedColor)
                    } finally { bitmap.recycle() }
                    verifiedGeneration = frameGeneration
                    status.text = if (generation == 0) "Texture cyan frame verified" else "Texture resized magenta frame verified"
                    Log.i("TextureFixture", "frame verified generation=$generation ${expectedWidth}x$expectedHeight")
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                check(++destroyed == 1)
                content.post {
                    check(surface.isReleased && !texture.isAvailable && texture.surfaceTexture == null)
                    status.text = "Texture released"
                    Log.i("TextureFixture", "surface released after detach")
                }
                return true
            }
        }
        content.addView(texture, LinearLayout.LayoutParams(expectedWidth, expectedHeight))
        content.addView(Button(this).apply {
            text = "Resize texture"
            setOnClickListener {
                check(verifiedGeneration == 0)
                generation++
                expectedWidth = 220
                expectedHeight = 140
                expectedColor = Color.MAGENTA
                texture.layoutParams = LinearLayout.LayoutParams(expectedWidth, expectedHeight)
            }
        })
        content.addView(Button(this).apply {
            text = "Remove texture"
            setOnClickListener {
                check(verifiedGeneration == 1)
                content.removeView(texture)
            }
        })
        setContentView(content)
    }
}
