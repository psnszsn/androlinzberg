package dev.artzig

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputEventSender
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicReference

/** Exercises stock Java/JNI input and MessageQueue callbacks through real sockets. */
object InputEventSmoke {
    private fun pump(looper: Looper) {
        repeat(100) {
            val message = looper.queue.`pollDueMessage$artzig`() ?: return
            try {
                message.target.dispatchMessage(message)
            } finally {
                MessageQueue.`recycleDispatched$artzig`(message)
            }
        }
    }

    private fun await(looper: Looper, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for input callback" }
            pump(looper)
            Thread.sleep(1)
        }
    }

    fun run() {
        if (Looper.myLooper() == null) Looper.prepare()
        val looper = checkNotNull(Looper.myLooper())
        testEvents(looper)
        testCallbackException(looper)
        testFileDescriptorListener(looper)
        println("InputEvent: stock JNI delivery, batching, acknowledgements, threads, exceptions and fd callbacks passed")
    }

    private fun testEvents(looper: Looper) {
        val pair = InputChannel.openInputChannelPair("event-smoke")
        val observed = mutableListOf<String>()
        val finished = mutableListOf<Pair<Int, Boolean>>()
        var batches = 0
        val receiver = object : InputEventReceiver(pair[1], looper) {
            override fun onInputEvent(event: InputEvent) {
                check(Looper.myLooper() === looper)
                when (event) {
                    is KeyEvent -> {
                        check(event.keyCode == KeyEvent.KEYCODE_ENTER)
                        check(event.source == InputDevice.SOURCE_KEYBOARD)
                        observed.add("key:${event.action}")
                    }
                    is MotionEvent -> {
                        check(event.source == InputDevice.SOURCE_TOUCHSCREEN)
                        check(event.x == 23f && event.y == 41f)
                        observed.add("motion:${event.actionMasked}")
                    }
                    else -> error("Unexpected event $event")
                }
                finishInputEvent(event, event is KeyEvent)
            }
            override fun onBatchedInputEventPending(source: Int) {
                check(source == InputDevice.SOURCE_TOUCHSCREEN)
                batches++
                // Leave pending until the explicit frame-time consumption below.
            }
        }
        val sender = object : InputEventSender(pair[0], looper) {
            override fun onInputEventFinished(seq: Int, handled: Boolean) {
                check(Looper.myLooper() === looper)
                finished.add(seq to handled)
            }
        }
        try {
            check(receiver.token === pair[0].token)
            val now = SystemClock.uptimeMillis()
            fun key(action: Int) = KeyEvent(now, now, action, KeyEvent.KEYCODE_ENTER, 0,
                0, 7, 28, 0, InputDevice.SOURCE_KEYBOARD)
            check(sender.sendInputEvent(101, key(KeyEvent.ACTION_DOWN)))
            check(sender.sendInputEvent(102, key(KeyEvent.ACTION_UP)))
            await(looper) { finished.size == 2 }
            check(observed == listOf("key:0", "key:1"))
            check(finished == listOf(101 to true, 102 to true))

            fun motion(seq: Int, action: Int) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 23f, 41f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(sender.sendInputEvent(seq, event)) } finally { event.recycle() }
            }
            motion(201, MotionEvent.ACTION_DOWN)
            await(looper) { finished.size == 3 }
            motion(202, MotionEvent.ACTION_MOVE)
            await(looper) { batches > 0 }
            check(observed.last() == "motion:0" && finished.size == 3)
            check(receiver.consumeBatchedInputEvents(-1))
            await(looper) { finished.size == 4 }
            motion(203, MotionEvent.ACTION_UP)
            await(looper) { finished.size == 5 }
            check(observed.takeLast(3) == listOf("motion:0", "motion:2", "motion:1"))
            check(finished.takeLast(3) == listOf(201 to false, 202 to false, 203 to false))

            // Stock sender posts to its owning Handler when called from another thread.
            val workerError = AtomicReference<Throwable>()
            val worker = Thread({
                try { check(sender.sendInputEvent(301, key(KeyEvent.ACTION_DOWN))) }
                catch (error: Throwable) { workerError.set(error) }
            }, "input-sender-worker")
            worker.start()
            await(looper) { !worker.isAlive && finished.size == 6 }
            worker.join()
            workerError.get()?.let { throw it }
            check(finished.last() == (301 to true))
            check(sender.sendInputEvent(302, key(KeyEvent.ACTION_UP)))
            await(looper) { finished.size == 7 }
            receiver.dispose()
            check(receiver.token == null && !receiver.probablyHasInput())
            sender.dispose()
            check(!sender.sendInputEvent(999, key(KeyEvent.ACTION_DOWN)))
        } finally {
            receiver.dispose()
            sender.dispose()
            pair.forEach { it.dispose() }
        }
    }

    private fun testCallbackException(looper: Looper) {
        val pair = InputChannel.openInputChannelPair("callback-exception")
        val expected = IllegalStateException("expected input receiver callback failure")
        val receiver = object : InputEventReceiver(pair[1], looper) {
            override fun onInputEvent(event: InputEvent) {
                finishInputEvent(event, false)
                throw expected
            }
        }
        var ack = false
        val sender = object : InputEventSender(pair[0], looper) {
            override fun onInputEventFinished(seq: Int, handled: Boolean) {
                check(seq == 401 && !handled)
                ack = true
            }
        }
        try {
            check(sender.sendInputEvent(401, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            var caught: Throwable? = null
            try { pump(looper) } catch (error: Throwable) { caught = error }
            check(caught === expected) { "Native MessageQueue did not rethrow the callback exception: $caught" }
            await(looper) { ack }
            var resumed = false
            Handler(looper).post { resumed = true }
            await(looper) { resumed }
        } finally {
            receiver.dispose()
            sender.dispose()
            pair.forEach { it.dispose() }
        }
    }

    private fun testFileDescriptorListener(looper: Looper) {
        val pipe = ParcelFileDescriptor.createPipe()
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        var calls = 0
        try {
            looper.queue.addOnFileDescriptorEventListener(pipe[0].fileDescriptor,
                MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT) { _, events ->
                check(events and MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT != 0)
                check(input.read() == 42)
                calls++
                0 // A zero mask unregisters the stock native callback.
            }
            output.write(42)
            await(looper) { calls == 1 }
            output.write(43)
            repeat(3) { pump(looper) }
            check(calls == 1 && input.read() == 43)
        } finally {
            looper.queue.removeOnFileDescriptorEventListener(pipe[0].fileDescriptor)
            input.close()
            output.close()
        }
    }
}
