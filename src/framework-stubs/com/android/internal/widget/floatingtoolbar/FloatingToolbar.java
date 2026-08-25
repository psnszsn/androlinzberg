/* artzig stub: selection floating toolbar (real popup wiring in M4+). */
package com.android.internal.widget.floatingtoolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
public final class FloatingToolbar {
    public static final String FLOATING_TOOLBAR_TAG = "floating_toolbar";
    public FloatingToolbar(Window window) {}
    public FloatingToolbar setMenu(Menu menu) { return this; }
    public FloatingToolbar setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener listener) { return this; }
    public FloatingToolbar setContentRect(android.graphics.Rect rect) { return this; }
    public FloatingToolbar setSuggestedWidth(int width) { return this; }
    public FloatingToolbar show() { return this; }
    public FloatingToolbar updateLayout() { return this; }
    public void dismiss() {}
    public void hide() {}
    public boolean isShowing() { return false; }
    public boolean isHidden() { return false; }
    public void setOutsideTouchable(boolean outsideTouchable, android.widget.PopupWindow.OnDismissListener listener) {}
}
