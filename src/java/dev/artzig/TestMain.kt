// artzig: step-by-step native smoke to isolate the first crashing graphics op.
package dev.artzig
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

public final class TestMain {
    companion object {
        @JvmStatic public fun main(args: Array<String>) {
            System.out.println("1: new Paint")
            val p: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
            System.out.println("2: setColor")
            p.setColor(0xff112233.toInt())
            System.out.println("3: createBitmap")
            val b: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            System.out.println("4: new Canvas")
            val c: Canvas = Canvas(b)
            System.out.println("5: drawColor")
            c.drawColor(0xff1a1b26.toInt())
            System.out.println("6: setColor again")
            p.setColor(0xff445566.toInt())
            System.out.println("7: drawCircle")
            c.drawCircle(32f, 32f, 10f, p)
            System.out.println("8: getPixel " + Integer.toHexString(b.getPixel(32, 32)))
            PathProbe.main(args)
            System.out.println("done")
        }
    }
}
