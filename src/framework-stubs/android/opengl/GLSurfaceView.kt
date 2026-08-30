package android.opengl

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Provider-less adaptation of AOSP GLSurfaceView's public lifecycle contract.
 * The host's video path uses SurfaceView/MediaCodec rather than a legacy EGL
 * render thread, so this class retains renderer state without creating one.
 */
open class GLSurfaceView : SurfaceView {
    interface Renderer {
        fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
        fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
        fun onDrawFrame(gl: GL10?)
    }

    private var renderer: Renderer? = null
    private var renderMode = RENDERMODE_CONTINUOUSLY
    private var eglContextClientVersion = 0
    private var preserveEglContextOnPause = false
    private var paused = false

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    open fun setRenderer(value: Renderer) { renderer = value }
    open fun setRenderMode(value: Int) {
        require(value == RENDERMODE_WHEN_DIRTY || value == RENDERMODE_CONTINUOUSLY) {
            "renderMode"
        }
        renderMode = value
    }
    open fun getRenderMode(): Int = renderMode
    open fun requestRender() = Unit
    open fun queueEvent(action: Runnable) { action.run() }
    open fun onPause() { paused = true }
    open fun onResume() { paused = false }
    open fun setEGLContextClientVersion(version: Int) { eglContextClientVersion = version }
    open fun setPreserveEGLContextOnPause(preserveOnPause: Boolean) {
        preserveEglContextOnPause = preserveOnPause
    }
    open fun getPreserveEGLContextOnPause(): Boolean = preserveEglContextOnPause
    open fun setDebugFlags(debugFlags: Int) = Unit
    open fun getDebugFlags(): Int = 0

    companion object {
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
        const val DEBUG_CHECK_GL_ERROR = 1
        const val DEBUG_LOG_GL_CALLS = 2
    }
}
