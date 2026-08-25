/* artzig stub: accessibility span. */
package android.text.style;
import android.os.Parcel;
public class AccessibilityURLSpan extends URLSpan {
    public AccessibilityURLSpan(URLSpan spanToReplace) { super(spanToReplace.getURL()); }
    public AccessibilityURLSpan(Parcel p) { super(p); }
    public void copyConnectionDataFrom(android.view.accessibility.AccessibilityNodeInfo info) {}
    @Override public int getSpanTypeId() { return 30; }
    public int getSpanTypeIdInternal() { return 30; }
}
