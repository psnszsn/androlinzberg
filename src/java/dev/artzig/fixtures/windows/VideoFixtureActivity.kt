package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Real AVC -> SurfaceView -> TextureView, using only public Android APIs. */
class VideoFixtureActivity : Activity() {
    private val worker = HandlerThread("Fixture codec").apply { start() }
    private val decode = Handler(worker.looper)
    private var codec: MediaCodec? = null // Owned by the decoder thread.
    private var textureSurface: Surface? = null
    private var moved = false
    private var resized = false
    private var removed = false
    private var windowReadbackGeneration = -1
    private lateinit var status: TextView
    private lateinit var callbackStatus: TextView
    // Decoder-thread-owned callback expectations. Each listener generation owns
    // a separate set, so an old queued frame cannot be relabelled after a switch.
    private var listenerGeneration = 0
    private val expectedFrames = HashMap<Long, Pair<Long, Long>>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Waiting for video" }
        content.addView(status)
        callbackStatus = TextView(this).apply { text = "Waiting for rendered callbacks" }
        content.addView(callbackStatus)
        val frame = FrameLayout(this)
        val surface = SurfaceView(this).apply { contentDescription = "Decoded SurfaceView" }
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        // A normal Android UI sibling must cover the underlying video layer.
        frame.addView(View(this).apply { setBackgroundColor(Color.RED) }, FrameLayout.LayoutParams(32, 32))
        content.addView(frame, LinearLayout.LayoutParams(240, 120))
        val texture = TextureView(this).apply { contentDescription = "Decoded TextureView" }
        content.addView(texture, LinearLayout.LayoutParams(240, 120))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                decode.post {
                    check(codec == null)
                    codec = MediaCodec.createDecoderByType("video/avc").apply {
                        configure(MediaFormat.createVideoFormat("video/avc", 128, 96), holder.surface, null, 0)
                        start()
                    }
                    // Hold the listener looper in this decode operation while
                    // frames queue, then replace it before it can run.
                    checkNotNull(codec).setOnFrameRenderedListener({ _, _, _ ->
                        error("Superseded rendered listener invoked")
                    }, decode)
                    renderFrames()
                    installRenderedListener("Surface", intermediate = false, defaultHandler = true)
                    renderFrames()
                    status.post { status.text = "Surface video queued" }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                textureSurface = Surface(value)
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                if (moved) decode.post {
                    installRenderedListener("Resized", intermediate = true, defaultHandler = true)
                    renderFrames()
                }
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                val generation = if (resized) 1 else 0
                texture.post {
                    if (!moved || removed || !texture.isAvailable) return@post
                    if (generation != if (resized) 1 else 0) return@post
                    if (texture.width != if (resized) 320 else 240) return@post
                    val bitmap = checkNotNull(texture.bitmap)
                    try {
                        check(bitmap.width == if (resized) 320 else 240)
                        check(bitmap.height == if (resized) 160 else 120)
                        for (y in intArrayOf(4, bitmap.height - 5)) for (x in intArrayOf(4, bitmap.width - 5)) {
                            val color = bitmap.getPixel(x, y)
                            check(Color.alpha(color) > 243 && Color.red(color) < 12 && Color.green(color) > 243 && Color.blue(color) > 243) {
                                "Decoded video pixel mismatch: ${Integer.toHexString(color)} at $x,$y"
                            }
                        }
                    } finally { bitmap.recycle() }
                    if (windowReadbackGeneration == generation) return@post
                    windowReadbackGeneration = generation
                    val location = IntArray(2)
                    texture.getLocationInWindow(location)
                    val decor = window.decorView
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        val copy = android.graphics.Bitmap.createBitmap(decor.width, decor.height,
                            android.graphics.Bitmap.Config.ARGB_8888)
                        android.view.PixelCopy.request(window, copy, { result ->
                            try {
                                check(result == android.view.PixelCopy.SUCCESS) { "Window PixelCopy failed: $result" }
                                val color = copy.getPixel(location[0] + 8, location[1] + 8)
                                android.util.Log.i("VideoFixture", "window pixel=${Integer.toHexString(color)} generation=$generation")
                                check(Color.alpha(color) > 243 && Color.red(color) < 12 &&
                                    Color.green(color) > 243 && Color.blue(color) > 243) {
                                    "Window video pixel mismatch: ${Integer.toHexString(color)}"
                                }
                                status.text = if (generation == 1) "Resized video pixels verified" else "Texture video pixels verified"
                            } finally { copy.recycle() }
                        }, Handler(mainLooper))
                    }
                    status.text = "Verifying video window"
                }
            }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                textureSurface?.release()
                textureSurface = null
                return true
            }
        }
        content.addView(Button(this).apply {
            text = "Move video to texture"
            setOnClickListener {
                val target = checkNotNull(textureSurface)
                moved = true
                decode.post {
                    checkNotNull(codec).setOutputSurface(target)
                    installRenderedListener("Texture", intermediate = true, defaultHandler = false)
                    renderFrames()
                    // Keep the same listener: flush must invalidate already
                    // queued callbacks, and synchronous decoding resumes without start.
                    checkNotNull(codec).flush()
                    expectedFrames.clear()
                    renderFrames()
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Resize video"
            setOnClickListener {
                check(moved)
                resized = true
                texture.layoutParams = LinearLayout.LayoutParams(320, 160)
            }
        })
        content.addView(Button(this).apply {
            text = "Remove video"
            setOnClickListener {
                removed = true
                decode.post {
                    renderFrames() // Pending callbacks must be invalidated by release.
                    listenerGeneration++
                    codec?.release()
                    codec = null
                    decode.postDelayed({
                        status.post {
                            content.removeView(frame)
                            content.removeView(texture)
                            status.text = "Video surfaces released"
                            callbackStatus.text = "Released callbacks cancelled"
                        }
                    }, 200)
                }
            }
        })
        setContentView(content)
    }

    private fun installRenderedListener(phase: String, intermediate: Boolean, defaultHandler: Boolean) {
        val generation = ++listenerGeneration
        expectedFrames.clear()
        checkNotNull(codec).setOnFrameRenderedListener({ owner, pts, rendered ->
            check(android.os.Looper.myLooper() === worker.looper) { "Rendered callback on wrong looper" }
            check(owner === codec && generation == listenerGeneration) { "Stale rendered callback" }
            val (requested, queued) = checkNotNull(expectedFrames.remove(pts)) { "Unknown rendered PTS: $pts" }
            if (intermediate) check(rendered == requested) { "Intermediate surface changed queue timestamp" }
            else check(rendered >= queued && rendered <= System.nanoTime()) { "Invalid display render timestamp: $rendered" }
            callbackStatus.post { callbackStatus.text = "$phase rendered callback verified" }
        }, if (defaultHandler) null else decode)
    }

    private fun renderFrames() {
        val output = checkNotNull(codec)
        repeat(12) { frame ->
            val input = output.dequeueInputBuffer(1_000_000)
            check(input >= 0)
            checkNotNull(output.getInputBuffer(input)).put(SAMPLE)
            output.queueInputBuffer(input, 0, SAMPLE.size, System.nanoTime() / 1000, 0)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (true) {
                val index = output.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = output.outputFormat
                    check(format.getInteger(MediaFormat.KEY_WIDTH) == 112 &&
                        format.getInteger(MediaFormat.KEY_HEIGHT) == 80) { "Decoder crop lost: $format" }
                    check(format.getInteger(MediaFormat.KEY_COLOR_FORMAT) ==
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    check(format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709 &&
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO &&
                        format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL) { "Unexpected decoded RGB color metadata: $format" }
                    check(format.getInteger(MediaFormat.KEY_CROP_LEFT) == 0 &&
                        format.getInteger(MediaFormat.KEY_CROP_TOP) == 0 &&
                        format.getInteger(MediaFormat.KEY_CROP_RIGHT) == 111 &&
                        format.getInteger(MediaFormat.KEY_CROP_BOTTOM) == 79)
                }
                if (index >= 0) {
                    val timestamp = if (frame % 2 == 0) System.nanoTime() else info.presentationTimeUs * 1000
                    expectedFrames[info.presentationTimeUs] = timestamp to System.nanoTime()
                    if (frame % 2 == 0) output.releaseOutputBuffer(index, timestamp)
                    else output.releaseOutputBuffer(index, true)
                    break
                }
                check(System.nanoTime() < deadline) { "Decoder did not produce the fixture frame" }
                Thread.sleep(2)
            }
        }
    }

    override fun onDestroy() {
        decode.post {
            listenerGeneration++
            codec?.release()
            codec = null
            worker.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        // Generated AVC: 128x96 red border, cyan 112x80 interior, SPS crop 8 on
        // every edge. A lost top/left hardware crop leaves a visible red border.
        // ffmpeg -f lavfi -i color=c=red:s=128x96:r=30,drawbox=x=8:y=8:w=112:h=80:color=cyan:t=fill
        // -frames:v 1 -c:v libx264 -pix_fmt yuv420p -color_primaries bt709
        // -color_trc bt709 -colorspace bt709 -x264-params keyint=1
        // -bsf:v h264_metadata=crop_left=8:crop_right=8:crop_top=8:crop_bottom=8:colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1,filter_units=remove_types=6
        // -f h264 sample.h264
        private val SAMPLE = android.util.Base64.decode(
            "AAAAAWdkEAqsuEG5SlLgLUBAQFAAAAMAEAAAAwPAQAAAAAFo7g8siwAAAWWIhDeAMcT/4SA/Erij03g4dDrIVTRCTXrMT/feNus/xT/z4SyLUnlk9uPmpDIN6IaVrScYAMvBg8Egpa4j6iA1rGRMV5T8PgBYXxpL/w2k3m8deqG8eGZ3GsFhvhiW2QAx2Ij+OQ==",
            android.util.Base64.DEFAULT)
    }
}
