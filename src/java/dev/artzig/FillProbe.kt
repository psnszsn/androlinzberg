package dev.artzig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable

/** Visual probe: does even-odd path filling work (Canvas + VectorDrawable)? */
public final class FillProbe {
    companion object {
        private var sInit: Boolean = false
        private var sVector: Drawable? = null

        @JvmStatic public fun motion(
            action: Int,
            x: Float,
            y: Float,
        ) {}

        @JvmStatic public fun draw(
            width: Int,
            height: Int,
        ): Bitmap {
            if (!sInit) {
                sInit = true
                android.graphics.Typeface.loadPreinstalledSystemFontMap()
                try {
                    android.content.res.AssetManager
                        .createSystemAssetsInZygoteLocked(false, System.getenv("ARTZIG_FRAMEWORK_RES"))
                    val res: android.content.res.Resources =
                        android.content.res.Resources
                            .getSystem()
                    val id: Int = res.getIdentifier("ic_signal_cellular_alt_24px", "drawable", "android")
                    System.out.println("probe: vector id=0x" + Integer.toHexString(id))
                    if (id != 0) sVector = res.getDrawable(id, null)
                } catch (t: Throwable) {
                    System.out.println("probe: vector failed: " + t)
                }
            }
            val bm: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c: Canvas = Canvas(bm)
            c.drawColor(0xffffffff.toInt())

            // 1: Java Path, EVEN_ODD donut — must show a hole
            val p: Path = Path()
            p.setFillType(Path.FillType.EVEN_ODD)
            p.addRect(50f, 50f, 250f, 250f, Path.Direction.CW)
            p.addRect(100f, 100f, 200f, 200f, Path.Direction.CW)
            val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.setColor(0xff2266cc.toInt())
            c.drawPath(p, paint)
            System.out.println("probe: path fillType=" + p.getFillType())

            // 2: same rects, WINDING — solid square (control)
            val p2: Path = Path()
            p2.addRect(300f, 50f, 500f, 250f, Path.Direction.CW)
            p2.addRect(350f, 100f, 450f, 200f, Path.Direction.CW)
            paint.setColor(0xffcc4422.toInt())
            c.drawPath(p2, paint)

            // 3: framework VectorDrawable with fillType="evenOdd"
            sVector?.let { vector ->
                vector.setBounds(50, 300, 350, 600)
                vector.setTint(0xff008833.toInt())
                vector.draw(c)
            }
            return bm
        }
    }
}
