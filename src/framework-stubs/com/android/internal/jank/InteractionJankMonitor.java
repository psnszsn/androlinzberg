/* artzig stub (auto-generated placeholder; members added on demand). */
package com.android.internal.jank;
public class InteractionJankMonitor {
    private static final InteractionJankMonitor sInstance = new InteractionJankMonitor();
    public static InteractionJankMonitor getInstance() { return sInstance; }
    public boolean begin(android.view.View v, int cujType) { return false; }
    public boolean begin(Configuration.Builder builder) { return false; }
    public static class Configuration {
        public static class Builder {
            public static Builder withSurface(int cuj, android.content.Context context,
                    android.view.SurfaceControl surface, android.os.Handler handler) { return new Builder(); }
            public static Builder withView(int cuj, android.view.View view) { return new Builder(); }
            public Builder setTag(String tag) { return this; }
            public Builder setTimeout(long timeout) { return this; }
        }
    }
    public boolean end(int cujType) { return false; }
    public boolean cancel(int cujType) { return false; }
    public boolean isInstrumenting(int cujType) { return false; }
}
