/* artzig: overlay window stack — dialogs and popups added via
 * WindowManagerImpl.addView render as layers over the activity, composited by
 * dev.artzig.ApkHost each frame. */
package android.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public final class ArtzigWindowStack {
    private static final String TAG = "ArtzigWindowStack";

    public static final class Entry {
        public final View view;
        public final WindowManager.LayoutParams lp;
        public final ViewRootImpl root;
        /** Frame in screen coordinates, updated by the compositor each frame. */
        public final Rect frame = new Rect();
        public Runnable onBack;
        Entry(View view, WindowManager.LayoutParams lp, ViewRootImpl root) {
            this.view = view;
            this.lp = lp;
            this.root = root;
        }
    }

    private static final ArrayList<Entry> sEntries = new ArrayList<>();

    private ArtzigWindowStack() {}

    static void add(Context context, View view, ViewGroup.LayoutParams params) {
        WindowManager.LayoutParams lp = params instanceof WindowManager.LayoutParams
                ? (WindowManager.LayoutParams) params : new WindowManager.LayoutParams();
        ViewRootImpl root = new ViewRootImpl(context);
        // real WindowManagerGlobal.addView sets the window params on the view;
        // PopupWindow.alignToAnchor() reads them back from getLayoutParams().
        view.setLayoutParams(lp);
        Entry e = new Entry(view, lp, root);
        synchronized (sEntries) {
            sEntries.add(e);
        }
        root.setView(view, 0, 0); // sized by the compositor per-frame
        Log.i(TAG, "addView " + view.getClass().getSimpleName() + " lp=" + lp.width + "x"
                + lp.height + " g=0x" + Integer.toHexString(lp.gravity) + " at " + lp.x + "," + lp.y);
    }

    static void remove(View view) {
        synchronized (sEntries) {
            for (int i = sEntries.size() - 1; i >= 0; i--) {
                if (sEntries.get(i).view == view) {
                    Entry e = sEntries.remove(i);
                    try {
                        view.dispatchDetachedFromWindow();
                        view.assignParent(null);
                    } catch (Throwable t) {
                        Log.w(TAG, "detach failed", t);
                    }
                    Log.i(TAG, "removeView " + view.getClass().getSimpleName());
                    return;
                }
            }
        }
    }

    static void update(View view, ViewGroup.LayoutParams params) {
        synchronized (sEntries) {
            for (Entry e : sEntries) {
                if (e.view == view && params instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams l = (WindowManager.LayoutParams) params;
                    e.lp.copyFrom(l);
                    view.setLayoutParams(e.lp);
                    e.root.requestLayout();
                    return;
                }
            }
        }
    }

    /** Snapshot, bottom to top. */
    public static List<Entry> entries() {
        synchronized (sEntries) {
            return new ArrayList<>(sEntries);
        }
    }

    public static Entry top() {
        synchronized (sEntries) {
            return sEntries.isEmpty() ? null : sEntries.get(sEntries.size() - 1);
        }
    }

    /** Topmost overlay eligible for pointer input. Exit transitions mark their
     * window not-touchable before the decor is finally removed. */
    public static Entry topTouchable() {
        synchronized (sEntries) {
            for (int i = sEntries.size() - 1; i >= 0; --i) {
                Entry entry = sEntries.get(i);
                if ((entry.lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
                    return entry;
                }
            }
            return null;
        }
    }

    public static boolean isEmpty() {
        synchronized (sEntries) {
            return sEntries.isEmpty();
        }
    }

    /** Detach every host overlay during process shutdown. */
    public static void clear() {
        final ArrayList<Entry> entries;
        synchronized (sEntries) {
            entries = new ArrayList<>(sEntries);
            sEntries.clear();
        }
        for (Entry entry : entries) entry.root.destroy();
    }

    public static void setOnBack(View decor, Runnable r) {
        synchronized (sEntries) {
            for (Entry e : sEntries) {
                if (e.view == decor) { e.onBack = r; return; }
            }
        }
    }
}
