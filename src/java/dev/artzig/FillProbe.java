package dev.artzig;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

/** Visual probe: does even-odd path filling work (Canvas + VectorDrawable)? */
public final class FillProbe {
    private static boolean sInit;
    private static Drawable sVector;
    public static void motion(int action, float x, float y) {}

    public static Bitmap draw(int width, int height) {
        if (!sInit) {
            sInit = true;
            android.graphics.Typeface.loadPreinstalledSystemFontMap();
            try {
                android.content.res.AssetManager.createSystemAssetsInZygoteLocked(
                        false, System.getenv("ARTZIG_FRAMEWORK_RES"));
                android.content.res.Resources res = android.content.res.Resources.getSystem();
                int id = res.getIdentifier("ic_signal_cellular_alt_24px", "drawable", "android");
                System.out.println("probe: vector id=0x" + Integer.toHexString(id));
                if (id != 0) sVector = res.getDrawable(id, null);
            } catch (Throwable t) {
                System.out.println("probe: vector failed: " + t);
            }
        }
        Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xffffffff);

        // 1: Java Path, EVEN_ODD donut — must show a hole
        Path p = new Path();
        p.setFillType(Path.FillType.EVEN_ODD);
        p.addRect(50, 50, 250, 250, Path.Direction.CW);
        p.addRect(100, 100, 200, 200, Path.Direction.CW);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff2266cc);
        c.drawPath(p, paint);
        System.out.println("probe: path fillType=" + p.getFillType());

        // 2: same rects, WINDING — solid square (control)
        Path p2 = new Path();
        p2.addRect(300, 50, 500, 250, Path.Direction.CW);
        p2.addRect(350, 100, 450, 200, Path.Direction.CW);
        paint.setColor(0xffcc4422);
        c.drawPath(p2, paint);

        // 3: framework VectorDrawable with fillType="evenOdd"
        if (sVector != null) {
            sVector.setBounds(50, 300, 350, 600);
            sVector.setTint(0xff008833);
            sVector.draw(c);
        }
        return bm;
    }
}
