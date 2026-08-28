/* artzig M2a smoke: Java drives the real android.graphics stack (AOSP Java →
 * real hwui native → nix skia); the launcher presents the Bitmap on Wayland. */
package dev.artzig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

public final class GraphicsSmoke {
    private constructor () {}

    companion object {
        @JvmStatic public fun draw(
            width: Int,
            height: Int,
        ): Bitmap {
            val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas: Canvas = Canvas(bitmap)

            canvas.drawColor(0xFF1a1b26.toInt())

            val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.setColor(0xFF7aa2f7.toInt())
            canvas.drawRoundRect(
                RectF(width * 0.08f, height * 0.08f, width * 0.92f, height * 0.4f),
                24f,
                24f,
                paint,
            )

            paint.setColor(0xFF9ece6a.toInt())
            canvas.drawCircle(width * 0.3f, height * 0.68f, height * 0.18f, paint)

            paint.setColor(Color.argb(255, 247, 118, 142))
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(8f)
            val path: Path = Path()
            path.moveTo(width * 0.5f, height * 0.5f)
            path.cubicTo(
                width * 0.6f,
                height * 0.9f,
                width * 0.8f,
                height * 0.45f,
                width * 0.92f,
                height * 0.85f,
            )
            canvas.drawPath(path, paint)

            // exercise Matrix + save/restore through the real native stack
            canvas.save()
            val m: Matrix = Matrix()
            m.setRotate(30f, width * 0.75f, height * 0.68f)
            canvas.concat(m)
            paint.setStyle(Paint.Style.FILL)
            paint.setColor(0xFFe0af68.toInt())
            canvas.drawRect(width * 0.68f, height * 0.6f, width * 0.82f, height * 0.76f, paint)
            canvas.restore()

            return bitmap
        }
    }
}
