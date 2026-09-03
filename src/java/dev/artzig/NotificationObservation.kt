package dev.artzig

import android.app.ActivityThread
import android.app.Notification
import android.app.NotificationManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * The testkit's `notifications` observation: the notifications this process's
 * package has posted, as the hosted notification service holds them
 * (`NotificationManager.getActiveNotifications` through
 * `INotificationManager.getAppActiveNotifications`). The host has no shade;
 * this is where a test proves a notification was posted, updated or cancelled.
 */
internal object NotificationObservation {
    @JvmStatic
    fun snapshot(): String {
        val packageName = ActivityThread.currentPackageName() ?: return "[]"
        val service = NotificationManager.getService() ?: return "[]"
        val list: List<*> = try {
            service.getAppActiveNotifications(packageName, UserHandle.myUserId())?.list ?: emptyList<Any>()
        } catch (t: Throwable) {
            return JSONArray().put(JSONObject().put("error", t.toString())).toString()
        }
        val out = JSONArray()
        for (item in list) {
            val sbn = item as? StatusBarNotification ?: continue
            val n = sbn.notification
            out.put(
                JSONObject()
                    .put("key", sbn.key)
                    .put("id", sbn.id)
                    .put("tag", sbn.tag ?: JSONObject.NULL)
                    .put("package", sbn.packageName)
                    .put("channel", n.channelId ?: JSONObject.NULL)
                    .put("title", n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: JSONObject.NULL)
                    .put("text", n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: JSONObject.NULL)
                    .put("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    .put("foreground_service", n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                    .put("flags", n.flags)
                    .put("group", n.group ?: JSONObject.NULL)
                    .put("post_time", sbn.postTime)
                    .put("when", n.`when`),
            )
        }
        return out.toString()
    }
}
