/* artzig stub (auto-generated placeholder; members added on demand). */
package com.android.internal.display;
public class BrightnessSynchronizer {
    public static boolean floatEquals(float f1, float f2) {
        return f1 == f2 || (Float.isNaN(f1) && Float.isNaN(f2));
    }
    public static float brightnessIntToFloat(int brightnessInt) { return brightnessInt / 255f; }
    public static int brightnessFloatToInt(float brightnessFloat) { return (int) (brightnessFloat * 255); }
}
