/* artzig stub: android.app.ActionBar — layout-params carrier (real toolbar decor in M4). */
package android.app;
import android.view.Gravity;
import android.view.ViewGroup;
public abstract class ActionBar {
    public static final int NAVIGATION_MODE_STANDARD = 0, NAVIGATION_MODE_LIST = 1, NAVIGATION_MODE_TABS = 2;
    public static final int DISPLAY_SHOW_HOME = 2, DISPLAY_HOME_AS_UP = 4, DISPLAY_SHOW_TITLE = 8,
            DISPLAY_SHOW_CUSTOM = 16, DISPLAY_USE_LOGO = 1;
    public abstract static class Tab {
        public static final int INVALID_POSITION = -1;
        public abstract int getPosition();
        public abstract android.graphics.drawable.Drawable getIcon();
        public abstract CharSequence getText();
        public abstract Tab setIcon(android.graphics.drawable.Drawable icon);
        public abstract Tab setText(CharSequence text);
        public abstract Tab setCustomView(android.view.View view);
        public abstract android.view.View getCustomView();
        public abstract Tab setTag(Object obj);
        public abstract Object getTag();
        public abstract void select();
        public abstract CharSequence getContentDescription();
        public abstract Tab setContentDescription(CharSequence contentDesc);
    }
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public int gravity = Gravity.NO_GRAVITY;
        public LayoutParams() { super(WRAP_CONTENT, WRAP_CONTENT); }
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(int width, int height, int gravity) { super(width, height); this.gravity = gravity; }
        public LayoutParams(int gravity) { this(WRAP_CONTENT, WRAP_CONTENT, gravity); }
        public LayoutParams(LayoutParams source) { super(source); this.gravity = source.gravity; }
        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
        public LayoutParams(android.content.Context c, android.util.AttributeSet attrs) {
            super(WRAP_CONTENT, WRAP_CONTENT);
        }
    }
}
