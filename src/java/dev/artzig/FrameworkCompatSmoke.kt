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
        testParcelRoundTrips()
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

        /** Cross-process prerequisite: every framework value type must survive a Parcel round trip. */
        @JvmStatic
        private fun testParcelRoundTrips() {
        fun <T> roundTrip(value: android.os.Parcelable, creator: android.os.Parcelable.Creator<T>): T {
            val parcel = android.os.Parcel.obtain()
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val out = creator.createFromParcel(parcel)
            check(parcel.dataAvail() == 0) { "${'$'}{value.javaClass.simpleName}: ${'$'}{parcel.dataAvail()} bytes unread" }
            parcel.recycle()
            return checkNotNull(out)
        }
        val intent = android.content.Intent("dev.artzig.ACTION")
        intent.setDataAndType(android.net.Uri.parse("content://dev.artzig/item/1"), "text/plain")
        intent.addCategory("android.intent.category.DEFAULT")
        intent.addCategory("dev.artzig.CATEGORY")
        intent.setPackage("dev.artzig.pkg")
        intent.setComponent(android.content.ComponentName("dev.artzig.pkg", "dev.artzig.pkg.Main"))
        intent.putExtra("i", 7)
        intent.putExtra("s", "text")
        val intent2 = roundTrip(intent, android.content.Intent.CREATOR)
        check(intent.filterEquals(intent2)) { "intent filterEquals after round trip" }
        check(intent2.getIntExtra("i", 0) == 7 && intent2.getStringExtra("s") == "text") { "intent extras" }
        check(intent2.flags == intent.flags) { "intent flags" }

        val filter = android.content.IntentFilter("dev.artzig.ACTION")
        filter.addCategory("dev.artzig.CATEGORY")
        filter.addDataScheme("content")
        filter.setPriority(7)
        val filter2 = roundTrip(filter, android.content.IntentFilter.CREATOR)
        check(filter2.hasAction("dev.artzig.ACTION") && filter2.hasCategory("dev.artzig.CATEGORY")) { "filter fields" }
        check(filter2.hasDataScheme("content") && filter2.priority == 7) { "filter scheme/priority" }

        val app = android.content.pm.ApplicationInfo()
        app.packageName = "dev.artzig.pkg"
        app.name = "dev.artzig.pkg.App"
        app.theme = 0x7f010001
        app.uid = 10001
        app.sourceDir = "/tmp/app.apk"
        val app2 = roundTrip(app, android.content.pm.ApplicationInfo.CREATOR)
        check(app2.packageName == app.packageName && app2.theme == app.theme && app2.uid == app.uid) { "applicationInfo" }
        check(app2.sourceDir == app.sourceDir) { "applicationInfo sourceDir" }

        val activity = android.content.pm.ActivityInfo()
        activity.packageName = "dev.artzig.pkg"
        activity.name = "dev.artzig.pkg.Main"
        activity.applicationInfo = app
        activity.theme = 3
        activity.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity.configChanges = 0x480
        val activity2 = roundTrip(activity, android.content.pm.ActivityInfo.CREATOR)
        check(activity2.name == activity.name && activity2.screenOrientation == activity.screenOrientation) { "activityInfo" }
        check(activity2.configChanges == activity.configChanges && activity2.applicationInfo?.uid == app.uid) { "activityInfo nested app" }

        val service = android.content.pm.ServiceInfo()
        service.packageName = "dev.artzig.pkg"
        service.name = "dev.artzig.pkg.Svc"
        service.permission = "dev.artzig.PERM"
        service.exported = true
        val resolve = android.content.pm.ResolveInfo()
        resolve.serviceInfo = service
        val resolve2 = roundTrip(resolve, android.content.pm.ResolveInfo.CREATOR)
        check(resolve2.serviceInfo?.permission == "dev.artzig.PERM" && resolve2.serviceInfo?.exported == true) { "resolveInfo{serviceInfo}" }

        val pkg = android.content.pm.PackageInfo()
        pkg.packageName = "dev.artzig.pkg"
        pkg.versionCode = 42
        pkg.versionName = "4.2"
        pkg.applicationInfo = app
        pkg.activities = arrayOf(activity)
        pkg.services = arrayOf(service)
        pkg.requestedPermissions = arrayOf("dev.artzig.PERM")
        val pkg2 = roundTrip(pkg, android.content.pm.PackageInfo.CREATOR)
        check(pkg2.versionCode == 42 && pkg2.activities?.size == 1 && pkg2.services?.get(0)?.name == service.name) { "packageInfo" }

        val provider = android.content.pm.ProviderInfo()
        provider.packageName = "dev.artzig.pkg"
        provider.name = "dev.artzig.pkg.Provider"
        provider.authority = "dev.artzig"
        provider.grantUriPermissions = true
        val provider2 = roundTrip(provider, android.content.pm.ProviderInfo.CREATOR)
        check(provider2.authority == "dev.artzig" && provider2.grantUriPermissions) { "providerInfo" }
        // Wire-shaped bundle path: marshalled bytes into a fresh Parcel, lazy decode.
        run {
            val src = android.os.Parcel.obtain()
            val b = android.os.Bundle()
            b.putInt("i", 7)
            b.putString("s", "text")
            val nested2 = android.os.Bundle()
            nested2.putDouble("d", 2.5)
            b.putBundle("n", nested2)
            src.writeBundle(b)
            val bytes = src.marshall()
            src.recycle()
            val dst = android.os.Parcel.obtain()
            dst.unmarshall(bytes, 0, bytes.size)
            dst.setDataPosition(0)
            val back2 = checkNotNull(dst.readBundle())
            check(back2.isParcelled()) { "wire bundle should stay parcelled until touched" }
            check(back2.getInt("i") == 7 && back2.getString("s") == "text") { "wire bundle values " + back2.getInt("i") + " " + back2.getString("s") }
            check(back2.getBundle("n")?.getDouble("d") == 2.5) { "wire nested bundle" }
            val copy = android.os.Bundle(back2)
            copy.putString("extra", "x")
            check(copy.getInt("i") == 7 && copy.getString("extra") == "x") { "bundle copy after unparcel" }
            dst.recycle()
        }
        android.util.Log.i("FrameworkCompatSmoke", "parcel round trips ok")
        }
    }
}
