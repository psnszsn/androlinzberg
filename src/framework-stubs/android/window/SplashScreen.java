/* artzig shim: desktop host has no separate system splash window. */
package android.window;
public interface SplashScreen {
    void setSplashScreenTheme(int themeId);
    void setOnExitAnimationListener(OnExitAnimationListener listener);
    public interface OnExitAnimationListener {
        void onSplashScreenExit(SplashScreenView splashScreenView);
    }
}
