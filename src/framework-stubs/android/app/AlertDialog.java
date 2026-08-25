/* artzig stub. */
package android.app;
import android.content.Context;
import android.content.DialogInterface;
public class AlertDialog extends Dialog implements DialogInterface {
    protected AlertDialog(Context context) { super(context); }
    public static final int BUTTON_POSITIVE = -1;
    public static final int BUTTON_NEGATIVE = -2;
    public static final int BUTTON_NEUTRAL = -3;
    public android.widget.Button getButton(int whichButton) {
        Context c = getContext();
        return c != null ? new android.widget.Button(c) : null;
    }
    public void setMessage(CharSequence message) {}
    public void setView(android.view.View view) {}
    public void setButton(int whichButton, CharSequence text, DialogInterface.OnClickListener listener) {}
    public void setIcon(int resId) {}
    public android.widget.ListView getListView() { return null; }
    public static class Builder {
        public Builder(Context context) { mCtx = context; }
        public Builder(Context context, int themeResId) { mCtx = context; }
        private Context mCtx;
        public Builder setTitle(int titleId) { return this; }
        public Builder setMessage(int messageId) { return this; }
        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }
        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) { return this; }
        public Builder setItems(int itemsId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setSingleChoiceItems(CharSequence[] items, int checkedItem, DialogInterface.OnClickListener listener) { return this; }
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checkedItems, DialogInterface.OnMultiChoiceClickListener listener) { return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener onCancelListener) { return this; }
        public Builder setView(int layoutResId) { return this; }
        public AlertDialog create() { return new AlertDialog(mCtx); }
        public AlertDialog show() { return create(); }
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setMessage(CharSequence message) { return this; }
        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }
        public Builder setSingleChoiceItems(android.widget.ListAdapter adapter, int checkedItem, DialogInterface.OnClickListener listener) { return this; }
        public Builder setView(android.view.View view) { return this; }
        public Builder setCancelable(boolean cancelable) { return this; }
        public android.content.Context getContext() { return null; }
        public Builder setAdapter(android.widget.ListAdapter adapter, DialogInterface.OnClickListener listener) { return this; }
        public Builder setCustomTitle(android.view.View customTitleView) { return this; }
        public Builder setIcon(android.graphics.drawable.Drawable icon) { return this; }
        public Builder setIcon(int iconId) { return this; }
        public Builder setOnKeyListener(DialogInterface.OnKeyListener onKeyListener) { return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener onDismissListener) { return this; }

    }
}
