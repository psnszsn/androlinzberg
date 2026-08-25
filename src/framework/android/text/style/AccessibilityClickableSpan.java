/* artzig stub: accessibility span (no a11y stack on host). */
package android.text.style;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.view.View;
public class AccessibilityClickableSpan extends ClickableSpan implements ParcelableSpan {
    private final int mOriginalClickableSpanId;
    public AccessibilityClickableSpan(int originalClickableSpanId) { mOriginalClickableSpanId = originalClickableSpanId; }
    public AccessibilityClickableSpan(Parcel p) { mOriginalClickableSpanId = p.readInt(); }
    public int getOriginalClickableSpanId() { return mOriginalClickableSpanId; }
    @Override public void onClick(View widget) {}
    public void copyConnectionDataFrom(android.view.accessibility.AccessibilityNodeInfo info) {}
    @Override public int getSpanTypeId() { return 28; }
    public int getSpanTypeIdInternal() { return 28; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(mOriginalClickableSpanId); }
    public void writeToParcelInternal(Parcel dest, int flags) { writeToParcel(dest, flags); }
}
