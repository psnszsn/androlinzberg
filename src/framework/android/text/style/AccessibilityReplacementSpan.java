/* artzig stub: accessibility span. */
package android.text.style;
import android.os.Parcel;
import android.text.ParcelableSpan;
public class AccessibilityReplacementSpan extends ReplacementSpan implements ParcelableSpan {
    private final CharSequence mContentDescription;
    public AccessibilityReplacementSpan(CharSequence contentDescription) { mContentDescription = contentDescription; }
    public AccessibilityReplacementSpan(Parcel p) { mContentDescription = p.readString(); }
    public CharSequence getContentDescription() { return mContentDescription; }
    @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
            android.graphics.Paint.FontMetricsInt fm) { return 0; }
    @Override public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
            float x, int top, int y, int bottom, android.graphics.Paint paint) {}
    @Override public int getSpanTypeId() { return 29; }
    public int getSpanTypeIdInternal() { return 29; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(String.valueOf(mContentDescription)); }
    public void writeToParcelInternal(Parcel dest, int flags) { writeToParcel(dest, flags); }
}
