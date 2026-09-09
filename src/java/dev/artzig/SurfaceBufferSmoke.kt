package dev.artzig

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.view.Surface
import java.io.File

/** Observes pixels from the stock Surface producer through stock SurfaceTexture's EGL consumer. */
object SurfaceBufferSmoke {
    @JvmStatic private external fun liveBuffers(): Long
    @JvmStatic private external fun nativeBinderUid(): Int
    @JvmStatic private external fun foreignBinderThread(binder: android.os.IBinder): Boolean
    @JvmStatic private external fun createReader(): Long
    @JvmStatic private external fun textureName(reader: Long): Int
    @JvmStatic private external fun pixel(reader: Long): Int
    @JvmStatic private external fun activateReader(reader: Long)
    @JvmStatic private external fun destroyReader(reader: Long)
    @JvmStatic private external fun ndkRoundTrip(texture: SurfaceTexture): Boolean
    @JvmStatic private external fun ndkCopy(surface: Surface): Surface

    private inline fun <reified T : Throwable> fails(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return
        }
        error("Expected ${T::class.java.name}")
    }

    private fun await(label: String = "SurfaceTexture frame callback", done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3000
        val queue = checkNotNull(Looper.myLooper()).queue
        while (!done() && SystemClock.uptimeMillis() < deadline) {
            val message = queue.`pollDueMessage$artzig`()
            if (message == null) { Thread.sleep(1); continue }
            try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        check(done()) { "Missing $label" }
    }

    @JvmStatic fun main(args: Array<String>) {
        val taskOutputsOnly = args.size == 2 && args[1] == "--task-outputs"
        require(args.size == 1 || taskOutputsOnly)
        if (taskOutputsOnly) BinderSession.setIdentity("dev.artzig.task-output", true)
        System.load(File(args[0]).absolutePath)
        check(nativeBinderUid() == android.os.Process.myUid()) { "Java and C++ must share one Binder identity" }
        val callback = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(code == 34)
                checkNotNull(reply).writeInt(data.readInt() + 1)
                return true
            }
        }
        check(foreignBinderThread(callback)) { "Binder retained an attachment on an external native thread" }

        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        // This isolated native gate owns SF and the production display/input
        // bootstrap, without starting ATMS/WMS or a second input dispatcher.
        val stockServices = if (taskOutputsOnly) dev.artzig.system.StockWindowServices.prepare() else null
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 640, 480, 120, 0)
        stockServices?.attachContext(context)
        if (taskOutputsOnly) {
            check(android.os.Process.myUid() == android.os.Process.SYSTEM_UID)
            val died = java.util.concurrent.CountDownLatch(1)
            checkNotNull(android.os.ServiceManager.checkService("SurfaceFlingerAIDL"))
                .linkToDeath({ died.countDown() }, 0)
            HostInputDispatcherSmoke.run()
            blastFrames()
            taskOutputs()
            println("READY_DEATH")
            System.out.flush()
            check(died.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "SF death was not delivered" }
            return
        }
        val baseline = liveBuffers()
        val reader = createReader()
        val texture = SurfaceTexture(false)
        val original = Surface(texture)
        val surfaces = mutableListOf(original)
        var attached = false
        try {
            check(original.isValid && !texture.isReleased && !original.isSingleBuffered)
            check(ndkRoundTrip(texture))
            texture.attachToGLContext(textureName(reader))
            attached = true
            val owner = Thread.currentThread()
            var callbacks = 0
            texture.setOnFrameAvailableListener({
                check(Thread.currentThread() === owner)
                callbacks++
            }, Handler(checkNotNull(Looper.myLooper())))

            val parcel = Parcel.obtain()
            val imported: Surface
            try {
                parcel.writeInt(0x1234)
                original.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                check(!original.isValid)
                parcel.writeInt(0x5678)
                parcel.setDataPosition(0)
                check(parcel.readInt() == 0x1234)
                imported = Surface.CREATOR.createFromParcel(parcel).also { surfaces.add(it) }
                check(parcel.readInt() == 0x5678 && parcel.dataAvail() == 0)
            } finally { parcel.recycle() }
            check(imported.isValid)
            val surface = ndkCopy(imported).also { surfaces.add(it) }
            imported.release()
            check(surface.isValid)
            var lastTimestamp = 0L
            repeat(6) { frame ->
                val width = if (frame < 3) 16 else 24
                val height = if (frame < 3) 12 else 8
                val color = 0xff000000.toInt() or ((frame + 1) * 0x102030)
                texture.setDefaultBufferSize(width, height)
                val before = callbacks
                val canvas = surface.lockCanvas(Rect(0, 0, width, height))
                check(canvas.width == width && canvas.height == height)
                canvas.drawColor(color)
                surface.unlockCanvasAndPost(canvas)
                await { callbacks > before }
                texture.updateTexImage()
                check(pixel(reader) == color) { "SurfaceTexture pixel mismatch at frame $frame" }
                check(texture.timestamp > lastTimestamp)
                lastTimestamp = texture.timestamp
                val matrix = FloatArray(16)
                texture.getTransformMatrix(matrix)
                check(matrix.all { it.isFinite() } && matrix[15] == 1f)
            }
            texture.detachFromGLContext()
            attached = false
            texture.release()
            // Surface::isValid checks that a producer exists; consumer
            // abandonment is reported by the next buffer operation.
            check(texture.isReleased && surface.isValid)
            fails<RuntimeException> { texture.updateTexImage() }
            val abandoned = Surface(texture).also { surfaces.add(it) }
            check(abandoned.isValid)
            fails<IllegalArgumentException> { abandoned.lockCanvas(null) }
            surface.release()
            fails<IllegalStateException> { surface.lockCanvas(null) }
            fails<IllegalStateException> { surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        } finally {
            if (attached) texture.detachFromGLContext()
            texture.release()
            surfaces.forEach { it.release() }
            destroyReader(reader)
        }
        check(liveBuffers() == baseline) { "Leaked Surface/SurfaceTexture allocations" }
        hardwareFrames()
        blastFrames()
        println("SurfaceBuffer: stock CPU/HWUI queues, EGL pixels, independent windows, BLAST/SF transactions, resize and ownership passed")
    }

    private fun taskOutputs() {
        val physical = com.android.server.display.DisplayControl.getPhysicalDisplayIds()
        check(physical.size == 1)
        check(com.android.server.display.DisplayControl.getPhysicalDisplayToken(physical.single()) != null)
        val baseline = liveBuffers()
        class Output(val id: Int) : AutoCloseable {
            val reader = createReader()
            val texture = SurfaceTexture(false).apply {
                setDefaultBufferSize(32, 24)
                attachToGLContext(textureName(reader))
            }
            val surface = Surface(texture)
            val source = android.view.SurfaceControl.Builder().setName("task-output-source-$id")
                .setContainerLayer().build()
            val content = android.view.SurfaceControl.Builder().setName("task-output-content-$id")
                .setParent(source).setBLASTLayer().build()
            val output = dev.artzig.window.TaskSurfaceOutput("task-output-$id", source, surface, 1000 + id)
            var callbacks = 0
            var bounds = dev.artzig.window.TaskGeometry.Bounds(id * 200, 17, 64, 48)
            init {
                texture.setOnFrameAvailableListener({ callbacks++ }, Handler(Looper.myLooper()!!))
                android.view.SurfaceControl.Transaction().use {
                    it.setLayerStack(source, 0).setPosition(source, bounds.left.toFloat(), bounds.top.toFloat())
                        .setWindowCrop(source, bounds.width, bounds.height).show(source).show(content)
                    output.configure(it, bounds, 32, 24)
                    it.apply()
                }
            }
            fun color(color: Int, resize: Boolean = false, secure: Boolean? = null) {
                val before = callbacks
                android.view.SurfaceControl.Transaction().use {
                    if (secure != null) it.setSecure(content, secure)
                    if (resize) {
                        bounds = dev.artzig.window.TaskGeometry.Bounds(bounds.left + 93, 49, 80, 40)
                        it.setPosition(source, bounds.left.toFloat(), bounds.top.toFloat())
                            .setWindowCrop(source, bounds.width, bounds.height)
                        output.configure(it, bounds, 32, 24)
                    }
                    android.hardware.HardwareBuffer.create(bounds.width, bounds.height,
                        android.hardware.HardwareBuffer.RGBA_8888, 1,
                        android.hardware.HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                            android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or
                            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            android.hardware.HardwareBuffer.USAGE_COMPOSER_OVERLAY).use { buffer ->
                        val graphic = checkNotNull(android.graphics.GraphicBuffer.createFromHardwareBuffer(buffer))
                        try {
                            val canvas = checkNotNull(graphic.lockCanvas(Rect(0, 0, bounds.width, bounds.height)))
                            canvas.drawColor(color)
                            graphic.unlockCanvasAndPost(canvas)
                            it.setBuffer(content, buffer).apply()
                        } finally { graphic.destroy() }
                    }
                }
                await("task $id composed pixel") {
                    if (callbacks <= before) false else {
                        activateReader(reader)
                        texture.updateTexImage()
                        pixel(reader) == if (secure == true) 0xff000000.toInt() else color
                    }
                }
            }
            override fun close() {
                output.close()
                output.close()
                android.view.SurfaceControl.Transaction().use { it.remove(content).remove(source).apply() }
                content.release()
                source.release()
                surface.release()
                activateReader(reader)
                texture.detachFromGLContext()
                texture.release()
                destroyReader(reader)
            }
        }
        val first = Output(1)
        try {
            val second = Output(2)
            try {
                first.color(0xffff0000.toInt())
                second.color(0xff00ff00.toInt())
                second.color(0xffff00ff.toInt(), secure = true)
                second.color(0xff00ff00.toInt(), secure = false)
                first.color(0xff0000ff.toInt(), resize = true)
                activateReader(second.reader)
                check(pixel(second.reader) == 0xff00ff00.toInt()) { "Resizing a task changed its peer" }
                check(com.android.server.display.DisplayControl.getPhysicalDisplayIds().contentEquals(physical))
            } finally { second.close() }
            first.color(0xffffff00.toInt())
        } finally { first.close() }
        await("released task output imports") { liveBuffers() == baseline }
        println("TaskSurfaceOutput: two private SF outputs, synthetic relocation, scaling, secure blackout and independent teardown passed")
    }

    private fun hardwareFrames() {
        val baseline = liveBuffers()
        class Target(val reader: Long, val texture: SurfaceTexture, val surface: Surface) {
            var callbacks = 0
            var attached = true
            var closed = false
            var lastColor = 0
            fun close() {
                if (closed) return
                surface.release()
                activateReader(reader)
                if (attached) texture.detachFromGLContext()
                texture.release()
                destroyReader(reader)
                closed = true
            }
        }
        val targets = mutableListOf<Target>()
        try {
            repeat(2) {
                val reader = createReader()
                val texture = SurfaceTexture(false)
                val target = Target(reader, texture, Surface(texture))
                targets.add(target)
                texture.attachToGLContext(textureName(reader))
                texture.setOnFrameAvailableListener({ target.callbacks++ }, Handler(Looper.myLooper()!!))
            }
            repeat(12) { frame ->
                val index = if (frame < 8) frame % 2 else 0
                val target = targets[index]
                val width = (if (frame < 4) 32 else 48) + index * 8
                val height = if (frame < 4) 24 else 16
                target.texture.setDefaultBufferSize(width, height)
                val before = target.callbacks
                val color = 0xff000000.toInt() or ((frame + 1) * 0x08121c)
                val canvas = target.surface.lockHardwareCanvas()
                check(canvas.isHardwareAccelerated && canvas.width == width && canvas.height == height)
                fails<IllegalStateException> { target.surface.lockHardwareCanvas() }
                canvas.drawColor(color)
                target.surface.unlockCanvasAndPost(canvas)
                await("HWUI target $index frame $frame callback") { target.callbacks > before }
                activateReader(target.reader)
                target.texture.updateTexImage()
                val observed = pixel(target.reader)
                check(observed == color) {
                    "HWUI target $index frame $frame: ${observed.toUInt().toString(16)} != ${color.toUInt().toString(16)}"
                }
                target.lastColor = color
                val other = targets[1 - index]
                if (!other.closed && other.lastColor != 0) {
                    activateReader(other.reader)
                    check(pixel(other.reader) == other.lastColor) { "Drawing one window changed its sibling" }
                }
                if (frame == 7) targets[1].close()
            }
            val target = targets[0]
            activateReader(target.reader)
            target.texture.detachFromGLContext()
            target.attached = false
            target.texture.release()
            check(target.surface.isValid && target.texture.isReleased)
            // Stock ReliableSurface observes consumer abandonment while HWUI
            // still owns the producer; rendering must return without hanging.
            val canvas = target.surface.lockHardwareCanvas()
            canvas.drawColor(0xff123456.toInt())
            target.surface.unlockCanvasAndPost(canvas)
        } finally {
            targets.forEach { it.close() }
        }
        check(liveBuffers() == baseline) { "Leaked HWUI queue or recovery buffer" }
    }

    private fun blastFrames() {
        val baseline = liveBuffers()
        val session = android.view.SurfaceSession()
        val layer = android.view.SurfaceControl.Builder(session).setName("java-blast-queue")
            .setBLASTLayer().build()
        val blast = android.graphics.BLASTBufferQueue("java-blast-queue", true)
        blast.update(layer, 32, 24, android.graphics.PixelFormat.RGBA_8888)
        val surface = blast.createSurfaceWithHandle()
        try {
            check(surface.isValid && blast.isSameSurfaceControl(layer))
            val owner = Thread.currentThread()
            val executor = android.os.HandlerExecutor(Handler(Looper.myLooper()!!))
            var commits = 0
            var acquired = 0L
            repeat(4) { frame ->
                val width = if (frame < 2) 32 else 48
                blast.update(layer, width, 24, android.graphics.PixelFormat.RGBA_8888)
                check(blast.syncNextTransaction { transaction ->
                    transaction.use {
                        it.show(layer).addTransactionCommittedListener(executor) {
                            check(Thread.currentThread() === owner)
                            commits++
                        }.apply()
                    }
                })
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(0xff000000.toInt() or ((frame + 1) * 0x123456))
                surface.unlockCanvasAndPost(canvas)
                await("BLAST frame $frame committed callback") { commits == frame + 1 }
                check(blast.lastAcquiredFrameNum > acquired)
                acquired = blast.lastAcquiredFrameNum
            }
        } finally {
            surface.release()
            blast.destroy()
            android.view.SurfaceControl.Transaction().use { it.remove(layer).apply() }
            layer.release()
            session.kill()
        }
        await { liveBuffers() == baseline }
    }
}
