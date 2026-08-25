/* artzig stub: no EGL on host — every query reports "unsupported". */
package android.opengl;
public class EGL14 {
    public static final int EGL_DEFAULT_DISPLAY = 0;
    public static final int EGL_SUCCESS = 0x3000;
    public static final int EGL_EXTENSIONS = 0x3055;
    public static final int EGL_NO_CONTEXT_HANDLE = 0;
    public static final EGLDisplay EGL_NO_DISPLAY = new EGLDisplay();
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext();
    public static final EGLSurface EGL_NO_SURFACE = new EGLSurface();
    public static EGLDisplay eglGetDisplay(int display_id) { return EGL_NO_DISPLAY; }
    public static boolean eglInitialize(EGLDisplay dpy, int[] major, int majorOffset, int[] minor, int minorOffset) {
        if (major != null && major.length > majorOffset) major[majorOffset] = 1;
        if (minor != null && minor.length > minorOffset) minor[minorOffset] = 4;
        return true;
    }
    public static boolean eglTerminate(EGLDisplay dpy) { return true; }
    public static String eglQueryString(EGLDisplay dpy, int name) { return ""; }
    public static int eglGetError() { return EGL_SUCCESS; }
    public static EGLContext eglGetCurrentContext() { return EGL_NO_CONTEXT; }
    public static boolean eglChooseConfig(EGLDisplay dpy, int[] attrib_list, int attrib_listOffset,
            EGLConfig[] configs, int configsOffset, int config_size, int[] num_config, int num_configOffset) {
        if (configs != null && configs.length > configsOffset) configs[configsOffset] = new EGLConfig();
        if (num_config != null && num_config.length > num_configOffset) num_config[num_configOffset] = 1;
        return true;
    }
    public static EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config, EGLContext share_context, int[] attrib_list, int offset) { return new EGLContext(); }
    public static EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config, int[] attrib_list, int offset) { return new EGLSurface(); }
    public static EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config, Object win, int[] attrib_list, int offset) { return new EGLSurface(); }
    public static boolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) { return true; }
    public static boolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) { return true; }
    public static boolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) { return true; }
    public static boolean eglReleaseThread() { return true; }
}
