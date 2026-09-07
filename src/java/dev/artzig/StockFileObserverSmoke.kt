package dev.artzig

import android.os.FileObserver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Kernel events through the original FileObserver Java and Linux JNI implementations. */
internal object StockFileObserverSmoke {
    private data class Event(val mask: Int, val path: String?, val thread: Thread)
    private fun await(queue: LinkedBlockingQueue<Event>, mask: Int, path: String?): Event {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Missing file event $mask for $path" }
            val event = queue.poll(remaining, TimeUnit.NANOSECONDS)
                ?: error("Missing file event $mask for $path")
            if (event.mask and mask != 0 && event.path == path) return event
        }
    }
    private fun absent(queue: LinkedBlockingQueue<Event>, path: String?) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val event = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return
            check(event.path != path) { "Unexpected file event $event" }
        }
    }
    fun run() {
        val root = Files.createTempDirectory("stock-file-observer-").toFile()
        val first = File(root, "first").apply { check(mkdir()) }
        val second = File(root, "second").apply { check(mkdir()) }
        val events = LinkedBlockingQueue<Event>()
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.DELETE_SELF
        val observer = object : FileObserver(listOf(first, second), mask) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FileObserver.ALL_EVENTS != 0) events.add(Event(event, path, Thread.currentThread()))
            }
        }
        val missingEvents = LinkedBlockingQueue<Event>()
        val missing = File(root, "initially-missing")
        val missingObserver = object : FileObserver(missing, FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FileObserver.CLOSE_WRITE != 0) missingEvents.add(Event(event, path, Thread.currentThread()))
            }
        }
        try {
            observer.startWatching()
            observer.startWatching() // Stock start is idempotent while descriptors are active.
            val original = File(first, "original")
            original.writeText("payload")
            val created = await(events, FileObserver.CREATE, "original")
            check(created.thread !== Thread.currentThread())
            check(await(events, FileObserver.CLOSE_WRITE, "original").thread === created.thread)
            val moved = File(second, "moved")
            check(original.renameTo(moved))
            await(events, FileObserver.MOVED_FROM, "original")
            await(events, FileObserver.MOVED_TO, "moved")
            check(moved.delete())
            await(events, FileObserver.DELETE, "moved")
            check(first.delete())
            await(events, FileObserver.DELETE_SELF, null)
            repeat(2) { observer.stopWatching() }
            check(first.mkdir())
            File(first, "while-stopped").writeText("unobserved")
            absent(events, "while-stopped") // Pending events for old paths remain permitted.
            observer.startWatching()
            File(first, "after-restart").writeText("observed")
            await(events, FileObserver.CREATE, "after-restart")
            await(events, FileObserver.CLOSE_WRITE, "after-restart")

            missingObserver.startWatching()
            missing.writeText("not automatically watched")
            absent(missingEvents, null)
            missingObserver.stopWatching()
            missingObserver.startWatching()
            missing.appendText(" now watched")
            await(missingEvents, FileObserver.CLOSE_WRITE, null)
            missingObserver.stopWatching()
            missing.appendText(" stopped again")
            absent(missingEvents, null)
        } finally {
            observer.stopWatching()
            missingObserver.stopWatching()
            check(root.deleteRecursively())
        }
        System.err.println("stock-file-observer: PASS create/write/move/delete, multiple paths, callback thread, missing paths and restart")
    }
}
