/* artzig stub: in-process clipboard. */
package android.content;
public class ClipboardManager {
    public interface OnPrimaryClipChangedListener { void onPrimaryClipChanged(); }
    private ClipData mClip;
    public void setPrimaryClip(ClipData clip) { mClip = clip; }
    public ClipData getPrimaryClip() { return mClip; }
    public ClipDescription getPrimaryClipDescription() { return mClip != null ? mClip.getDescription() : null; }
    public boolean hasPrimaryClip() { return mClip != null; }
    public void clearPrimaryClip() { mClip = null; }
    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener what) {}
    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener what) {}
    public void setText(CharSequence text) { setPrimaryClip(ClipData.newPlainText("text", text)); }
    public CharSequence getText() {
        return mClip != null && mClip.getItemCount() > 0 ? mClip.getItemAt(0).getText() : null;
    }
    public boolean hasText() { return getText() != null; }
}
