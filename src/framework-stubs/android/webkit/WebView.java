/* artzig stub: no webview on host. */
package android.webkit;
public class WebView extends android.view.ViewGroup {
    public WebView(android.content.Context context) { super(context); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public float getScale() { return 1.0f; }
    public int getContentHeight() { return 0; }
    public static String findAddress(String addr) { return null; }
}
