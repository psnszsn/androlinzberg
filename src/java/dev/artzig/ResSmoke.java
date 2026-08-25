/* artzig M2 smoke: real Resources stack over androidfw — framework-res.apk. */
package dev.artzig;

import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;

public final class ResSmoke {
    private ResSmoke() {}

    public static void main(String[] args) throws Exception {
        String path = System.getenv("ARTZIG_FRAMEWORK_RES");
        System.out.println("1: ApkAssets.loadFromPath " + path);
        ApkAssets apk = ApkAssets.loadFromPath(path, ApkAssets.PROPERTY_SYSTEM);

        System.out.println("2: AssetManager (system assets from " + path + ")");
        AssetManager.createSystemAssetsInZygoteLocked(true, path);
        AssetManager am = new AssetManager.Builder().addApkAssets(apk).build();

        System.out.println("3: Resources");
        DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        Configuration config = new Configuration();
        config.setToDefaults();
        Resources res = new Resources(am, dm, config);

        System.out.println("4: getText(android.R.string.ok) = " + res.getText(android.R.string.ok));
        System.out.println("5: getText(android.R.string.cancel) = " + res.getText(android.R.string.cancel));

        TypedValue tv = new TypedValue();
        res.getValue(android.R.color.white, tv, true);
        System.out.println("6: color white = 0x" + Integer.toHexString(tv.data));

        System.out.println("7: theme + styled attrs");
        Resources.Theme theme = res.newTheme();
        theme.applyStyle(android.R.style.Theme_DeviceDefault, true);
        TypedArray ta = theme.obtainStyledAttributes(new int[] { android.R.attr.colorBackground });
        System.out.println("8: colorBackground = 0x" + Integer.toHexString(ta.getColor(0, 0xdead)));
        ta.recycle();

        System.out.println("9: drawable (ColorDrawable)");
        Drawable d = res.getDrawable(android.R.drawable.screen_background_dark, theme);
        System.out.println("10: drawable = " + d);

        System.out.println("done");
    }
}
