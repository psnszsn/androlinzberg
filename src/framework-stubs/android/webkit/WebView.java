/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* artzig hosted WebView state/callback adapter. Chromium's WebView provider is not available;
 * public lifecycle and callback behavior follows AOSP WebView while document loading is reduced
 * to an in-process navigation seam. */
package android.webkit;
import android.content.Context;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowInsets;

import android.net.Uri;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebView extends android.view.ViewGroup {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface RendererPriority {}
    public static final int RENDERER_PRIORITY_WAIVED = 0;
    public static final int RENDERER_PRIORITY_BOUND = 1;
    public static final int RENDERER_PRIORITY_IMPORTANT = 2;
    private final WebSettings mSettings;
    private final HostedWebViewProvider mProvider;
    private final Map<String, Object> mJavascriptInterfaces = new HashMap<>();
    private WebViewClient mWebViewClient = new WebViewClient();
    private WebChromeClient mWebChromeClient;
    private String mUrl;
    private boolean mDestroyed;

    public WebView(Context context) { this(context, null); }
    public WebView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    public WebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mSettings = new WebSettings(context);
        mProvider = HostedWebViewProviders.create(this);
        setWillNotDraw(false);
    }
    @Deprecated
    public WebView(Context context, AttributeSet attrs, int defStyleAttr,
            boolean privateBrowsing) {
        this(context, attrs, defStyleAttr, 0);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public float getScale() { return 1.0f; }
    public int getContentHeight() { return 0; }
    public static String findAddress(String addr) { return null; }

    public WebSettings getSettings() { return mSettings; }
    public void setWebViewClient(WebViewClient client) {
        mWebViewClient = client != null ? client : new WebViewClient();
    }
    public WebViewClient getWebViewClient() { return mWebViewClient; }
    public void setWebChromeClient(WebChromeClient client) { mWebChromeClient = client; }
    public WebChromeClient getWebChromeClient() { return mWebChromeClient; }
    public String getUrl() { return mUrl; }

    public void loadUrl(String url) {
        mProvider.loadUrl(url != null ? url : "about:blank", java.util.Collections.emptyMap());
    }
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        mProvider.loadUrl(url != null ? url : "about:blank",
                additionalHttpHeaders != null ? additionalHttpHeaders
                        : java.util.Collections.emptyMap());
    }
    public void loadData(String data, String mimeType, String encoding) {
        String type = mimeType != null ? mimeType : "text/html";
        String payload = Base64.encodeToString(
                (data != null ? data : "").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        loadUrl("data:" + type + ";base64," + payload);
    }
    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType,
            String encoding, String historyUrl) {
        // AOSP delegates base-URL document loading to the provider. The hosted
        // provider currently preserves the document and callback behavior; it
        // cannot synthesize Chromium's unreachable internal base-URL loader.
        loadData(data, mimeType, encoding);
    }

    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        mProvider.evaluateJavascript(script != null ? script : "", resultCallback);
    }
    public void addJavascriptInterface(Object object, String name) {
        if (object != null && name != null) mJavascriptInterfaces.put(name, object);
    }
    public void removeJavascriptInterface(String name) { mJavascriptInterfaces.remove(name); }
    public void clearCache(boolean includeDiskFiles) {}
    public void clearHistory() {}
    public void stopLoading() { mProvider.stopLoading(); }
    public void reload() { if (mUrl != null) loadUrl(mUrl); }
    public boolean canGoBack() { return false; }
    public void goBack() {}
    public void onPause() {}
    public void onResume() {}
    public void pauseTimers() {}
    public void resumeTimers() {}
    public void destroy() {
        mDestroyed = true;
        mProvider.destroy();
        mJavascriptInterfaces.clear();
        removeAllViews();
    }

    /* Provider callbacks. Package-private so the host adapter owns callback
     * ordering without exposing another Android-facing API. */
    void hostedOnPageStarted(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageStarted(this, url, null);
    }
    void hostedOnProgressChanged(int progress) {
        if (!mDestroyed && mWebChromeClient != null) {
            mWebChromeClient.onProgressChanged(this, progress);
        }
    }
    void hostedOnPageCommitVisible(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageCommitVisible(this, url);
        invalidate();
    }
    void hostedOnPageFinished(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageFinished(this, url);
        invalidate();
    }
    void hostedOnReceivedError(int errorCode, String description, String failingUrl) {
        if (!mDestroyed) {
            mWebViewClient.onReceivedError(this, errorCode, description, failingUrl);
        }
    }

    public void postVisualStateCallback(long requestId, VisualStateCallback callback) {
        if (callback != null) callback.onComplete(requestId);
    }
    public static abstract class VisualStateCallback {
        public abstract void onComplete(long requestId);
    }
    public static class WebViewTransport {
        private WebView mWebView;
        public synchronized void setWebView(WebView webView) { mWebView = webView; }
        public synchronized WebView getWebView() { return mWebView; }
    }

    public static void setDataDirectorySuffix(String suffix) {}
    public static ClassLoader getWebViewClassLoader() { return WebView.class.getClassLoader(); }
    public Looper getWebViewLooper() { return Looper.myLooper(); }
    public static android.content.pm.PackageInfo getCurrentWebViewPackage() { return null; }
    public static Uri getSafeBrowsingPrivacyPolicyUrl() {
        return Uri.parse("https://www.google.com/chrome/browser/privacy/whitepaper.html#safe-browsing");
    }
    public static void startSafeBrowsing(Context context, ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
    @Deprecated
    public static void setSafeBrowsingWhitelist(List<String> hosts,
            ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
    // Verbatim nested contracts from pinned AOSP WebView, required by the stock
    // WebViewProvider interfaces in the AppOps/Zygote source closure. The outer
    // WebView still uses the hosted provider until Android Chromium's native
    // library and presentation dependencies are supplied.
    public interface FindListener {
        /**
         * Notifies the listener about progress made by a find operation.
         *
         * @param activeMatchOrdinal the zero-based ordinal of the currently selected match
         * @param numberOfMatches how many matches have been found
         * @param isDoneCounting whether the find operation has actually completed. The listener
         *                       may be notified multiple times while the
         *                       operation is underway, and the numberOfMatches
         *                       value should not be considered final unless
         *                       isDoneCounting is {@code true}.
         */
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches,
            boolean isDoneCounting);
    }

    @Deprecated
    public interface PictureListener {
        /**
         * Used to provide notification that the WebView's picture has changed.
         * See {@link WebView#capturePicture} for details of the picture.
         *
         * @param view the WebView that owns the picture
         * @param picture the new picture. Applications targeting
         *     {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2} or above
         *     will always receive a {@code null} Picture.
         * @deprecated Deprecated due to internal changes.
         */
        @Deprecated
        void onNewPicture(WebView view, @Nullable Picture picture);
    }

    public static class HitTestResult {
        /**
         * Default HitTestResult, where the target is unknown.
         */
        public static final int UNKNOWN_TYPE = 0;
        /**
         * @deprecated This type is no longer used.
         */
        @Deprecated
        public static final int ANCHOR_TYPE = 1;
        /**
         * HitTestResult for hitting a phone number.
         */
        public static final int PHONE_TYPE = 2;
        /**
         * HitTestResult for hitting a map address.
         */
        public static final int GEO_TYPE = 3;
        /**
         * HitTestResult for hitting an email address.
         */
        public static final int EMAIL_TYPE = 4;
        /**
         * HitTestResult for hitting an HTML::img tag.
         */
        public static final int IMAGE_TYPE = 5;
        /**
         * @deprecated This type is no longer used.
         */
        @Deprecated
        public static final int IMAGE_ANCHOR_TYPE = 6;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http.
         */
        public static final int SRC_ANCHOR_TYPE = 7;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http + HTML::img.
         */
        public static final int SRC_IMAGE_ANCHOR_TYPE = 8;
        /**
         * HitTestResult for hitting an edit text area.
         */
        public static final int EDIT_TEXT_TYPE = 9;

        private int mType;
        private String mExtra;

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public HitTestResult() {
            mType = UNKNOWN_TYPE;
        }

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public void setType(int type) {
            mType = type;
        }

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public void setExtra(String extra) {
            mExtra = extra;
        }

        /**
         * Gets the type of the hit test result. See the XXX_TYPE constants
         * defined in this class.
         *
         * @return the type of the hit test result
         */
        public int getType() {
            return mType;
        }

        /**
         * Gets additional type-dependant information about the result. See
         * {@link WebView#getHitTestResult()} for details. May either be {@code null}
         * or contain extra information about this result.
         *
         * @return additional type-dependant information about the result
         */
        @Nullable
        public String getExtra() {
            return mExtra;
        }
    }

    public class PrivateAccess {
        // ---- Access to super-class methods ----
        public int super_getScrollBarStyle() {
            return WebView.super.getScrollBarStyle();
        }

        public void super_scrollTo(int scrollX, int scrollY) {
            WebView.super.scrollTo(scrollX, scrollY);
        }

        public void super_computeScroll() {
            WebView.super.computeScroll();
        }

        public boolean super_onHoverEvent(MotionEvent event) {
            return WebView.super.onHoverEvent(event);
        }

        public boolean super_performAccessibilityAction(int action, Bundle arguments) {
            return WebView.super.performAccessibilityActionInternal(action, arguments);
        }

        public boolean super_performLongClick() {
            return WebView.super.performLongClick();
        }

        public boolean super_setFrame(int left, int top, int right, int bottom) {
            return WebView.super.setFrame(left, top, right, bottom);
        }

        public boolean super_dispatchKeyEvent(KeyEvent event) {
            return WebView.super.dispatchKeyEvent(event);
        }

        public boolean super_onGenericMotionEvent(MotionEvent event) {
            return WebView.super.onGenericMotionEvent(event);
        }

        public boolean super_requestFocus(int direction, Rect previouslyFocusedRect) {
            return WebView.super.requestFocus(direction, previouslyFocusedRect);
        }

        public void super_setLayoutParams(ViewGroup.LayoutParams params) {
            WebView.super.setLayoutParams(params);
        }

        public void super_startActivityForResult(Intent intent, int requestCode) {
            WebView.super.startActivityForResult(intent, requestCode);
        }

        /**
         * @see View#onApplyWindowInsets(WindowInsets)
         */
        @Nullable
        public WindowInsets super_onApplyWindowInsets(@Nullable WindowInsets insets) {
            return WebView.super.onApplyWindowInsets(insets);
        }

        // ---- Access to non-public methods ----
        public void overScrollBy(int deltaX, int deltaY,
                int scrollX, int scrollY,
                int scrollRangeX, int scrollRangeY,
                int maxOverScrollX, int maxOverScrollY,
                boolean isTouchEvent) {
            WebView.this.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                    maxOverScrollX, maxOverScrollY, isTouchEvent);
        }

        public void awakenScrollBars(int duration) {
            WebView.this.awakenScrollBars(duration);
        }

        public void awakenScrollBars(int duration, boolean invalidate) {
            WebView.this.awakenScrollBars(duration, invalidate);
        }

        public float getVerticalScrollFactor() {
            return WebView.this.getVerticalScrollFactor();
        }

        public float getHorizontalScrollFactor() {
            return WebView.this.getHorizontalScrollFactor();
        }

        public void setMeasuredDimension(int measuredWidth, int measuredHeight) {
            WebView.this.setMeasuredDimension(measuredWidth, measuredHeight);
        }

        public void onScrollChanged(int l, int t, int oldl, int oldt) {
            WebView.this.onScrollChanged(l, t, oldl, oldt);
        }

        public int getHorizontalScrollbarHeight() {
            return WebView.this.getHorizontalScrollbarHeight();
        }

        public void super_onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar,
                int l, int t, int r, int b) {
            WebView.super.onDrawVerticalScrollBar(canvas, scrollBar, l, t, r, b);
        }

        // ---- Access to (non-public) fields ----
        /** Raw setter for the scroll X value, without invoking onScrollChanged handlers etc. */
        public void setScrollXRaw(int scrollX) {
            WebView.this.mScrollX = scrollX;
        }

        /** Raw setter for the scroll Y value, without invoking onScrollChanged handlers etc. */
        public void setScrollYRaw(int scrollY) {
            WebView.this.mScrollY = scrollY;
        }

    }
}
