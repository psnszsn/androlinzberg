// artzig: focused smoke checks for hosted-package framework behavior.
package dev.artzig

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RenderNode
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.ThreadedRenderer
import android.view.ViewRootImpl
import android.view.WindowManagerImpl
import java.security.KeyStore
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import javax.crypto.spec.SecretKeySpec

public final class FrameworkCompatSmoke {
    companion object {
        @JvmStatic private fun require(
            condition: Boolean,
            message: String,
        ) {
            if (!condition) throw AssertionError(message)
        }

        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val filtered: Intent =
                Intent(Intent.ACTION_VIEW)
                    .setPackage("org.example")
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            val filteredCopy: Intent = Intent(filtered)
            require(filtered.filterEquals(filteredCopy), "Intent.filterEquals copy")
            require(
                filtered.filterHashCode() == filteredCopy.filterHashCode(),
                "Intent.filterHashCode copy",
            )

            PackageManager.configureHostedPackage("org.example", 1, "1", "/tmp/example.apk", "/tmp/example-data")
            PackageManager.configureHostedLauncher("org.example.MainActivity")

            val service: ServiceInfo = ServiceInfo()
            service.packageName = "org.example"
            service.name = "org.example.PlaybackService"
            service.enabled = true
            val services: MutableList<ServiceInfo> = ArrayList()
            services.add(service)
            val serviceActions: MutableMap<String, MutableSet<String>> = HashMap()
            val actions: MutableSet<String> = HashSet()
            actions.add("org.example.PLAYBACK")
            serviceActions.put(service.name, actions)
            PackageManager.configureHostedServices(services, serviceActions)

            val packageManager: PackageManager = object : PackageManager() {}
            val launch: Intent? = packageManager.getLaunchIntentForPackage("org.example")
            require(launch != null, "hosted launch intent")
            require(
                ComponentName("org.example", "org.example.MainActivity")
                    .equals(launch?.getComponent()),
                "hosted launcher component",
            )

            val implicitServices: MutableList<ResolveInfo> =
                packageManager.queryIntentServices(Intent("org.example.PLAYBACK").setPackage("org.example"), 0)
            require(implicitServices.size == 1, "implicit hosted service resolution")
            require(
                packageManager.resolveService(
                    Intent().setComponent(ComponentName("org.example", "org.example.PlaybackService")),
                    0,
                ) != null,
                "explicit hosted service resolution",
            )

            require(
                NotificationManager().getActiveNotifications().size == 0,
                "host notification list",
            )
            val defaultScheduler: JobScheduler = JobScheduler()
            require(
                defaultScheduler.getNamespace() == null,
                "default job scheduler namespace",
            )
            val workScheduler: JobScheduler = defaultScheduler.forNamespace("  androidx.work  ")
            require(
                "androidx.work".equals(workScheduler.getNamespace()),
                "job scheduler namespace is trimmed",
            )
            var rejectedEmptyNamespace: Boolean = false
            try {
                defaultScheduler.forNamespace("   ")
            } catch (expected: IllegalArgumentException) {
                rejectedEmptyNamespace = true
            }
            require(rejectedEmptyNamespace, "empty job scheduler namespace is rejected")
            var rejectedNullNamespace: Boolean = false
            try {
                defaultScheduler.forNamespace(null)
            } catch (expected: NullPointerException) {
                rejectedNullNamespace = true
            }
            require(rejectedNullNamespace, "null job scheduler namespace is rejected")
            require(
                Application::class.java.getMethod("getProcessName").getReturnType() === String::class.java,
                "Application.getProcessName API",
            )
            require(
                android.app
                    .ActivityManager()
                    .getHistoricalProcessExitReasons(null, 0, 0)
                    .isEmpty(),
                "host has no historical process exits",
            )
            require(
                "org.example".equals(packageManager.getApplicationInfo("org.example", 0).processName),
                "hosted application process name",
            )
            val authenticatedAction: android.app.Notification.Action =
                android.app.Notification.Action
                    .Builder(0, "unlock", null)
                    .setAuthenticationRequired(true)
                    .build()
            require(
                authenticatedAction.isAuthenticationRequired(),
                "notification action authentication requirement",
            )
            val notificationManager: NotificationManager = NotificationManager()
            val conversationChannel: android.app.NotificationChannel =
                android.app.NotificationChannel("conversation", "Conversation", NotificationManager.IMPORTANCE_DEFAULT)
            conversationChannel.setConversationId("messages", "recipient-1")
            notificationManager.createNotificationChannel(conversationChannel)
            require(
                notificationManager.getNotificationChannel("messages", "recipient-1") ===
                    conversationChannel,
                "conversation notification channel",
            )
            require(
                !notificationManager.areBubblesEnabled() &&
                    !notificationManager.areBubblesAllowed() &&
                    notificationManager.getBubblePreference() ==
                    NotificationManager.BUBBLE_PREFERENCE_NONE,
                "desktop notification bubble policy",
            )
            require(
                android.hardware.Camera.getNumberOfCameras() == 0,
                "desktop legacy camera count",
            )
            verifyAndroidKeyStoreNullParameters()
            verifyMediaStoreCompatibility()
            verifyBoundedHostThrowableDiagnostic()
            require(
                PendingIntent.getActivities(
                    null,
                    7,
                    arrayOf<Intent>(Intent()),
                    0,
                    Bundle(),
                ) != null,
                "PendingIntent.getActivities options overload",
            )
            val accessibilityExtras: Bundle = Bundle()
            val spanStarts: ArrayList<Int> = ArrayList()
            spanStarts.add(4)
            accessibilityExtras.putIntegerArrayList("span-starts", spanStarts)
            require(
                accessibilityExtras.getIntegerArrayList("span-starts") === spanStarts,
                "Bundle integer array list",
            )
            val notificationItems: ArrayList<android.os.Parcelable> = ArrayList()
            notificationItems.add(Intent("notification-item"))
            val notificationIntent: Intent =
                Intent().putParcelableArrayListExtra("notification-items", notificationItems)
            require(
                notificationIntent.getParcelableArrayListExtra<android.os.Parcelable>("notification-items") ===
                    notificationItems,
                "Intent parcelable array list extra",
            )
            require(
                !ViewRootImpl(null).getWindowInsets(false).isConsumed(),
                "host zero window insets propagate to descendants",
            )
            WindowManagerImpl.configureWindowMetrics(411, 731, 1.5f)
            val resizedMetrics: android.view.WindowMetrics =
                WindowManagerImpl(null).getCurrentWindowMetrics()
            require(
                resizedMetrics.getBounds().width() == 411 &&
                    resizedMetrics.getBounds().height() == 731 &&
                    resizedMetrics.getDensity() == 1.5f,
                "current window metrics follow host resize",
            )
            require(
                android.content.res.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    1280,
                    720,
                ),
                "unspecified orientation follows a wide desktop window",
            )
            require(
                !android.content.res.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    420,
                    800,
                ),
                "unspecified orientation follows a tall phone window",
            )
            require(
                !android.content.res.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    1280,
                    720,
                ),
                "fixed portrait orientation overrides window geometry",
            )
            require(
                android.content.res.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    420,
                    800,
                ),
                "fixed landscape orientation overrides window geometry",
            )
            require(
                android.content.pm.ActivityInfo
                    .activityInfoConfigNativeToJava(0x0200) ==
                    android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE,
                "manifest screenSize config change uses Java ActivityInfo bit",
            )
            val alarmFired: BooleanArray = booleanArrayOf(false)
            val alarmManager: AlarmManager = AlarmManager()
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 30,
                "compat-alarm",
                { true.also { alarmFired[0] = it } },
                Handler(Looper.getMainLooper()),
            )
            drainMainLooper()
            require(!alarmFired[0], "future alarm is not delivered immediately")
            Thread.sleep(40)
            drainMainLooper()
            require(alarmFired[0], "future alarm is delivered when due")
            val snapshotNode: RenderNode = RenderNode("compat-snapshot")
            snapshotNode.setPosition(0, 0, 3, 2)
            val snapshot: Bitmap? = ThreadedRenderer.createHardwareBitmap(snapshotNode, 3, 2)
            require(
                snapshot != null && snapshot.getWidth() == 3 && snapshot.getHeight() == 2,
                "host RenderNode bitmap fallback",
            )
            val mutableIconBitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            require(
                mutableIconBitmap.getConfig() === Bitmap.Config.ARGB_8888,
                "host 8888 bitmap config",
            )
            val icon: Icon = Icon.createWithBitmap(mutableIconBitmap)
            require(
                icon.getBitmap() != null && !icon.getBitmap().isMutable(),
                "mutable bitmap icon copy",
            )
            System.out.println("framework compat smoke: PASS")
        }

        @JvmStatic private fun verifyAndroidKeyStoreNullParameters() {
            val keyStore: KeyStore =
                KeyStore.getInstance("AndroidKeyStore", ArtzigKeyStoreProvider())
            keyStore.load(null as KeyStore.LoadStoreParameter?)
            keyStore.load(null as java.io.InputStream?, null)

            val alias: String = "artzig-null-parameter-smoke"
            try {
                val expected: ByteArray = ByteArray(32) { index: Int -> index.toByte() }
                keyStore.setEntry(
                    alias,
                    KeyStore.SecretKeyEntry(SecretKeySpec(expected, "AES")),
                    null,
                )
                val entry: KeyStore.Entry? = keyStore.getEntry(alias, null)
                require(entry is KeyStore.SecretKeyEntry, "AndroidKeyStore secret-key entry")
                require(
                    expected.contentEquals((entry as KeyStore.SecretKeyEntry).secretKey.encoded),
                    "AndroidKeyStore null-protected key round trip",
                )
            } finally {
                keyStore.deleteEntry(alias)
            }
        }

        @JvmStatic private fun verifyMediaStoreCompatibility() {
            require(
                "content://media/external/video/media".equals(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString(),
                ),
                "MediaStore video URI",
            )
            require(
                "content://media/external/images/media".equals(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString(),
                ) &&
                    "content://media/external/audio/media".equals(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString(),
                    ) &&
                    "content://media/external/downloads".equals(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString(),
                    ),
                "MediaStore external collection URIs",
            )
            require(MediaStore.getPickImagesMaxLimit() == 100, "photo picker selection limit")
            require(
                "1".equals(
                    MediaStore
                        .setRequireOriginal(android.net.Uri.parse("content://media/item"))
                        .getQueryParameter("requireOriginal"),
                ),
                "MediaStore original-content query parameter",
            )

            val thumbnailUri: Array<android.net.Uri?> = arrayOfNulls(1)
            val thumbnailSize: Array<Size?> = arrayOfNulls(1)
            val expected: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val resolver: ContentResolver =
                object : ContentResolver() {
                    override fun loadThumbnail(
                        uri: android.net.Uri,
                        size: Size,
                        signal: android.os.CancellationSignal?,
                    ): Bitmap {
                        thumbnailUri[0] = uri
                        thumbnailSize[0] = size
                        return expected
                    }
                }
            require(
                MediaStore.Video.Thumbnails.getThumbnail(
                    resolver,
                    42,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                ) === expected,
                "MediaStore video thumbnail result",
            )
            require(
                "content://media/external/video/media/42".equals(thumbnailUri[0].toString()) &&
                    Size(512, 384).equals(thumbnailSize[0]),
                "MediaStore video thumbnail resolver seam",
            )
        }

        @JvmStatic private fun verifyBoundedHostThrowableDiagnostic() {
            var cause: Throwable = IllegalStateException("leaf")
            repeat(20) { index: Int ->
                cause = RuntimeException("cause-" + index, cause)
            }
            val hostile: Throwable =
                object : RuntimeException("printer must not run", cause) {
                    override fun printStackTrace(writer: java.io.PrintWriter) {
                        throw AssertionError("bounded logger called Throwable.printStackTrace")
                    }

                    override fun printStackTrace(stream: java.io.PrintStream) {
                        throw AssertionError("bounded logger called Throwable.printStackTrace")
                    }
                }
            val diagnostic: String = ApkHost.`formatThrowableForHost$artzig`(hostile)
            require(diagnostic.length <= 64 * 1024, "host throwable diagnostic is bounded")
            require(
                diagnostic.contains(hostile.javaClass.name) &&
                    diagnostic.contains("[throwable diagnostic truncated]"),
                "host throwable diagnostic retains type and truncation marker",
            )
        }

        @JvmStatic private fun drainMainLooper() {
            var message: Message
            while ((Looper.myQueue().`pollDueMessage$artzig`().also { message = it }) != null) {
                message.getTarget().dispatchMessage(message)
                message.recycle()
            }
        }
    }
}
