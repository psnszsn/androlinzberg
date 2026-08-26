/* artzig M2 gate: a bare Activity with one colored View painting through the
 * real Canvas/Drawable/Resources and HWUI display-list stack. */
package dev.artzig;

import android.app.Activity;
import android.app.ContextImpl;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.os.SystemClock;

public final class ViewHost {
    private static Activity sActivity;
    private static ContextImpl sContext;
    private static android.view.ViewRootImpl sRoot;
    private static TextView sStatusText;
    private static long sDownTime;
    private static int sWidth, sHeight, sScale120;

    /** Pointer event from the platform (Wayland). action: 0=down 1=up 2=move. */
    public static void motion(int action, float x, float y) {
        if (sActivity == null) return;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) sDownTime = now;
        MotionEvent ev = MotionEvent.obtain(sDownTime, now, action, x, y, 0);
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        sActivity.getWindow().getDecorView().dispatchTouchEvent(ev);
        ev.recycle();
    }
    private ViewHost() {}

    static final class DemoActivity extends Activity {
        @Override protected void onCreate(Bundle savedInstanceState) {
            // theme-resolved window background
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
            getDecorView().setBackgroundColor(tv.data);

            FrameLayout content = new FrameLayout(getBaseContext());
            // one "colored View" that also exercises onDraw through the real Canvas
            View colored = new View(getBaseContext()) {
                private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    mPaint.setColor(0xff7aa2f7);
                    canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), 28f, 28f, mPaint);
                    mPaint.setColor(Color.argb(255, 158, 206, 106));
                    canvas.drawCircle(getWidth() * 0.5f, getHeight() * 0.5f, getHeight() * 0.25f, mPaint);
                }
            };
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(600, 340);
            lp.leftMargin = 80;
            lp.topMargin = 90;
            content.addView(colored, lp);

            TextView text = new TextView(getBaseContext());
            sStatusText = text;
            text.setText("Hello from the real TextView — Minikin shapes this.");
            text.setTextSize(24f);
            text.setTextColor(0xffc0caf5);
            ViewGroup.MarginLayoutParams tlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = 80;
            tlp.topMargin = 480;
            content.addView(text, tlp);

            Button button = new Button(DemoActivity.this);
            button.setText("Tap me");
            button.setOnClickListener(v -> {
                sStatusText.setText("Button clicked! Input dispatch works.");
                sStatusText.setTextColor(0xff9ece6a);
            });
            ViewGroup.MarginLayoutParams blp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = 80;
            blp.topMargin = 550;
            content.addView(button, blp);

            View inflated = LayoutInflater.from(DemoActivity.this)
                    .inflate(android.R.layout.simple_list_item_1, null);
            if (inflated instanceof TextView) {
                ((TextView) inflated).setText("Inflated simple_list_item_1");
            }
            ViewGroup.MarginLayoutParams ilp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.leftMargin = 700;
            ilp.topMargin = 480;
            content.addView(inflated, ilp);
            setContentView(content);
        }
    }

    /** Called once from the launcher. */
    public static void init(int width, int height, int scale120) throws Exception {
        sWidth = width;
        sHeight = height;
        sScale120 = scale120;
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        android.graphics.Typeface.loadPreinstalledSystemFontMap();
        String resPath = System.getenv("ARTZIG_FRAMEWORK_RES");
        sContext = ContextImpl.createSystemContext(resPath, width, height, scale120,
                android.R.style.Theme_DeviceDefault_NoActionBar);
        sActivity = new DemoActivity();
        sActivity.attach(sContext);
        sActivity.performCreate(null);
        sActivity.performStart();
        sActivity.performResume();
        sRoot = new android.view.ViewRootImpl(sContext);
        android.view.View decor = sActivity.getWindow().getDecorView();
        sRoot.setView(decor, width, height);
        android.view.ArtzigAccessibilityBridge.setActivityRoot(sRoot);
    }

    public static String accessibilitySnapshot() {
        return android.view.ArtzigAccessibilityBridge.snapshot();
    }

    public static String accessibilityDiagnostics(long afterRevision) {
        return android.view.ArtzigAccessibilityBridge.diagnostics(afterRevision);
    }

    public static String testDiagnostics(long afterLogSequence, long afterIssueSequence) {
        return android.util.ArtzigTestDiagnostics.snapshot(afterLogSequence, afterIssueSequence);
    }

    public static long diagnosticLogSequence() {
        return android.util.ArtzigTestDiagnostics.logSequence();
    }

    public static long diagnosticIssueSequence() {
        return android.util.ArtzigTestDiagnostics.issueSequence();
    }

    public static long accessibilityRevision() {
        return android.view.ArtzigAccessibilityBridge.revision();
    }

    public static boolean accessibilityIdle() {
        return android.view.ArtzigAccessibilityBridge.isIdle();
    }

    public static boolean accessibilityPerform(long expectedRevision, long windowId,
            int viewId, int virtualId, int action, String text) {
        return android.view.ArtzigAccessibilityBridge.perform(expectedRevision, windowId,
                viewId, virtualId, action, text);
    }

    /** Per-frame: record the view tree; RenderThread presents it directly through EGL. */
    public static void drawHardware(int logicalWidth, int logicalHeight,
            int bufferWidth, int bufferHeight, int scale120) throws Exception {
        if (sActivity == null) init(bufferWidth, bufferHeight, scale120);
        if (bufferWidth != sWidth || bufferHeight != sHeight || scale120 != sScale120) {
            resize(bufferWidth, bufferHeight, scale120);
        }
        // drain main-looper messages posted by views (clicks, invalidations)
        drainLooper();
        android.view.ThreadedRenderer.getInstance().render(
                sRoot.doTraversal(), bufferWidth, bufferHeight);
    }

    private static void resize(int width, int height, int scale120) {
        sWidth = width;
        sHeight = height;
        sScale120 = scale120;
        android.content.res.Resources res = sContext.getResources();
        android.util.DisplayMetrics dm = res.getDisplayMetrics();
        float density = scale120 / 120f;
        android.view.WindowManagerImpl.configureWindowMetrics(width, height, density);
        int densityDpi = Math.round(160f * density);
        android.view.ArtzigDisplay.configure(width, height, densityDpi);
        dm.widthPixels = dm.noncompatWidthPixels = width;
        dm.heightPixels = dm.noncompatHeightPixels = height;
        dm.densityDpi = dm.noncompatDensityDpi = densityDpi;
        dm.density = dm.noncompatDensity = density;
        dm.scaledDensity = dm.noncompatScaledDensity = density;
        dm.xdpi = dm.noncompatXdpi = densityDpi;
        dm.ydpi = dm.noncompatYdpi = densityDpi;
        android.content.res.Configuration config =
                new android.content.res.Configuration(res.getConfiguration());
        config.densityDpi = densityDpi;
        config.screenWidthDp = (int) (width / density);
        config.screenHeightDp = (int) (height / density);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.orientation = width >= height
                ? android.content.res.Configuration.ORIENTATION_LANDSCAPE
                : android.content.res.Configuration.ORIENTATION_PORTRAIT;
        res.updateConfiguration(config, dm);
        sRoot.setSize(width, height);
        sActivity.onConfigurationChanged(config);
    }

    private static void drainLooper() {
        // run pending messages on this thread (we own the main looper)
        android.os.Looper looper = android.os.Looper.myLooper();
        if (looper == null) return;
        while (true) {
            android.os.Message msg = nextMessageNow(looper.getQueue());
            if (msg == null) break;
            msg.getTarget().dispatchMessage(msg);
            msg.recycle();
        }
    }

    private static android.os.Message nextMessageNow(android.os.MessageQueue q) {
        return q.pollDueMessage$artzig();
    }
}
