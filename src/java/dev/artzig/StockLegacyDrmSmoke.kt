package dev.artzig

import android.app.ActivityThread
import android.drm.DrmErrorEvent
import android.drm.DrmEvent
import android.drm.DrmInfo
import android.drm.DrmInfoRequest
import android.drm.DrmManagerClient
import android.drm.DrmOutputStream
import android.drm.DrmRights
import android.drm.DrmStore
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import java.io.File
import java.net.UnknownServiceException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Original Java/JNI/native client behavior with no drm.drmManager service. */
internal object StockLegacyDrmSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Any?): T {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
        return failure
    }
    fun run() {
        check(ServiceManager.checkService("drm.drmManager") == null)
        val context = ActivityThread.currentActivityThread().systemContext
        val mime = "application/vnd.oma.drm.message"
        val path = File.createTempFile("stock-legacy-drm-", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val client = DrmManagerClient(context)
        val callbacks = LinkedBlockingQueue<Pair<DrmErrorEvent, Thread>>()
        val unexpected = LinkedBlockingQueue<DrmEvent>()
        val info = DrmInfo(DrmInfoRequest.TYPE_REGISTRATION_INFO, byteArrayOf(1, 2), mime)
        info.put("account", "fixture")
        try {
            check(client.availableDrmEngines.isEmpty())
            check(client.availableDrmSupportInfo.isEmpty())
            check(!client.canHandle(path.path, "text/plain"))
            check(!client.canHandle(null as String?, mime))
            rejects<IllegalArgumentException> { client.canHandle(null as String?, null) }
            rejects<IllegalArgumentException> { client.getConstraints("", DrmStore.Action.PLAY) }
            rejects<IllegalArgumentException> { client.getConstraints(path.path, -1) }
            rejects<IllegalArgumentException> { client.getMetadata("") }
            check(client.getConstraints(path.path, DrmStore.Action.PLAY) == null)
            check(client.getMetadata(path.path) == null)
            check(client.getOriginalMimeType(path.path) == "")
            check(client.getDrmObjectType(path.path, mime) == -1)
            check(client.checkRightsStatus(path.path) == -1)
            rejects<IllegalArgumentException> { client.checkRightsStatus(path.path, -1) }
            check(client.removeRights(path.path) == Int.MIN_VALUE) // Native UNKNOWN_ERROR.
            rejects<IllegalArgumentException> { client.removeRights("") }
            val rights = DrmRights(path.path, mime, "account", "subscription")
            check(client.saveRights(rights, "", path.path) == Int.MIN_VALUE)
            rejects<IllegalArgumentException> { client.saveRights(null, "", path.path) }
            val request = DrmInfoRequest(DrmInfoRequest.TYPE_REGISTRATION_INFO, mime)
            request.put("account", "fixture")
            check(request.get("account") == "fixture")
            check(client.acquireDrmInfo(request) == null)
            rejects<IllegalArgumentException> { client.acquireDrmInfo(null) }
            check(client.acquireRights(request) == DrmManagerClient.ERROR_UNKNOWN)
            rejects<IllegalArgumentException> { client.processDrmInfo(null) }
            check(client.openConvertSession(mime) == DrmManagerClient.INVALID_SESSION)
            rejects<IllegalArgumentException> { client.openConvertSession("") }
            check(client.convertData(123, byteArrayOf(1)) == null)
            rejects<IllegalArgumentException> { client.convertData(123, byteArrayOf()) }
            check(client.closeConvertSession(123) == null)
            ParcelFileDescriptor.open(path, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val failure = rejects<UnknownServiceException> { DrmOutputStream(client, descriptor, mime) }
                check(failure.message == "Failed to open DRM session for $mime")
                check(descriptor.fileDescriptor.valid())
            }
            // Stock Java accepts these asynchronous requests, then reports native failure
            // through the event handler rather than inventing successful DRM operations.
            client.setOnEventListener { _, event -> unexpected.add(event) }
            client.setOnInfoListener { _, event -> unexpected.add(event) }
            client.setOnErrorListener { owner, event ->
                check(owner === client)
                callbacks.add(event to Thread.currentThread())
            }
            check(client.processDrmInfo(info) == DrmManagerClient.ERROR_NONE)
            val processed = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
            check(processed.first.type == DrmErrorEvent.TYPE_PROCESS_DRM_INFO_FAILED)
            check(processed.first.getAttribute(DrmEvent.DRM_INFO_OBJECT) === info)
            check(processed.first.getAttribute(DrmEvent.DRM_INFO_STATUS_OBJECT) == null)
            check(processed.second !== Thread.currentThread())
            check(client.removeAllRights() == DrmManagerClient.ERROR_NONE)
            val removed = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
            check(removed.first.type == DrmErrorEvent.TYPE_REMOVE_ALL_RIGHTS_FAILED)
            check(removed.second === processed.second)
            check(unexpected.isEmpty())
        } finally {
            client.close()
            client.release()
            check(path.delete())
        }
        // These two Java entry points explicitly reject enqueueing after their handler is closed.
        check(client.processDrmInfo(info) == DrmManagerClient.ERROR_UNKNOWN)
        check(client.removeAllRights() == DrmManagerClient.ERROR_UNKNOWN)
        repeat(3) { DrmManagerClient(context).use { check(it.availableDrmEngines.isEmpty()) } }
        System.err.println("stock-legacy-drm: PASS original service fallback, validation, queries, conversion rejection, async errors and cleanup")
    }
}
