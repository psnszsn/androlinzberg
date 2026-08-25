/* artzig stub: stylus handwriting — inert on host. */
package android.view;
import android.graphics.Rect;
public class HandwritingInitiator {
    public HandwritingInitiator() {}
    public boolean onTouchEvent(MotionEvent event) { return false; }
    public void updateHandwritingAreasForView(View view) {}
    public void deallocateHandwritingAreasForView(View view) {}
    public void onDelegateViewFocused(View view) {}
    public void onEditorFocused(View view) {}
    public void onInputConnectionCreated(View view) {}
    public void onInputConnectionClosed(View view) {}
    public void updateEditorBound(Rect bound) {}
    public void clearFocusedView(View view) {}
    public void unregisterHandwritingDelegatorCallback(View view) {}
}
