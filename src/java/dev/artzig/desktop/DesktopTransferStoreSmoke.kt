package dev.artzig.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object DesktopTransferStoreSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("desktop-transfers").toFile()
        try {
            val store = DesktopTransferStore(root)
            fun source(value: String) = DesktopTransferStore.Source("café.txt", "text/plain") { ByteArrayInputStream(value.toByteArray()) }
            val token = DesktopTransferStore.token()
            val lease = store.stage(token, listOf(source("hello 😀"), source("second")), false)
            check(lease.items[0].file.readText() == "hello 😀")
            check(store.item(token, 1).size == 6L)
            store.release(token)
            check(runCatching { store.item(token, 0) }.isFailure)
            val exported = store.stage(DesktopTransferStore.token(), listOf(source("retained")), true)
            store.release(exported.token)
            check(store.item(exported.token, 0).file.readText() == "retained")
            check(DesktopTransferStore.parseUriList("# files\r\nfile:///tmp/caf%C3%A9%20x\r\nfile://localhost/tmp/b\r\n").map { it.path } == listOf("/tmp/café x", "/tmp/b"))
            for (bad in listOf("file://remote/tmp/a", "content://a/b", "file:///tmp/a?x", "file:///tmp/a#x", "file:///tmp/%00", "file:relative", "file:///tmp/%zz", "")) {
                check(runCatching { DesktopTransferStore.parseUriList(bad) }.isFailure) { bad }
            }
            check(runCatching { store.stage("../escape", listOf(source("bad")), false) }.isFailure)
            val failure = DesktopTransferStore.token()
            check(runCatching { store.stage(failure, listOf(source("first"), DesktopTransferStore.Source("bad", "text/plain") { error("source died") }), false) }.isFailure)
            check(!java.io.File(root, failure).exists())
            val huge = DesktopTransferStore.Source("huge", "application/octet-stream") { ByteArrayInputStream(ByteArray(DesktopTransferStore.MAX_FILE.toInt() + 1)) }
            check(runCatching { store.stage(DesktopTransferStore.token(), listOf(huge), false) }.isFailure)
            val started = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val cancelled = DesktopTransferStore.token()
            val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val worker = Thread {
                workerFailure.set(runCatching { store.stage(cancelled, listOf(DesktopTransferStore.Source("slow", "text/plain") {
                    started.countDown(); check(unblock.await(3, TimeUnit.SECONDS)); ByteArrayInputStream(byteArrayOf(1))
                }), false) }.exceptionOrNull())
            }
            worker.start(); check(started.await(3, TimeUnit.SECONDS)); store.release(cancelled); unblock.countDown()
            worker.join(3000); check(!worker.isAlive && workerFailure.get() is java.util.concurrent.CancellationException && !java.io.File(root, cancelled).exists())
            val pressureRoot = java.io.File(root, "pressure")
            val pressureStore = DesktopTransferStore(pressureRoot)
            val admitted = CountDownLatch(2)
            val resume = CountDownLatch(1)
            val completed = java.util.concurrent.atomic.AtomicInteger()
            val pressureWorkers = (0..1).map {
                Thread {
                    val lease = pressureStore.stage(DesktopTransferStore.token(), listOf(DesktopTransferStore.Source("blocked", "text/plain") {
                        admitted.countDown(); check(resume.await(3, TimeUnit.SECONDS)); ByteArrayInputStream(byteArrayOf(1))
                    }), false)
                    pressureStore.release(lease.token)
                    completed.incrementAndGet()
                }.apply { start() }
            }
            check(admitted.await(3, TimeUnit.SECONDS))
            check(runCatching { pressureStore.stage(DesktopTransferStore.token(), listOf(source("over capacity")), false) }.isFailure)
            resume.countDown(); pressureWorkers.forEach { it.join(3000); check(!it.isAlive) }
            check(completed.get() == 2)
            pressureStore.release(pressureStore.stage(DesktopTransferStore.token(), listOf(source("recovered")), false).token)
            var now = 0L
            val deadlineStore = DesktopTransferStore(java.io.File(root, "deadline")) { now }
            check(runCatching { deadlineStore.stage(DesktopTransferStore.token(), listOf(DesktopTransferStore.Source("late", "text/plain") {
                now = DesktopTransferStore.TIMEOUT_NANOS; ByteArrayInputStream(byteArrayOf(1))
            }), false) }.isFailure)
            println("desktop transfer store: immutable leases, URI validation, budgets, cancellation and deadline PASS")
        } finally { root.deleteRecursively() }
    }
}
