/* artzig: focused smoke checks for hosted-package framework behavior. */
package dev.artzig;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.RenderNode;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.ThreadedRenderer;
import android.view.ViewRootImpl;
import android.view.WindowManagerImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FrameworkCompatSmoke {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Intent filtered = new Intent(Intent.ACTION_VIEW)
                .setPackage("org.example")
                .addCategory(Intent.CATEGORY_BROWSABLE);
        Intent filteredCopy = new Intent(filtered);
        require(filtered.filterEquals(filteredCopy), "Intent.filterEquals copy");
        require(filtered.filterHashCode() == filteredCopy.filterHashCode(),
                "Intent.filterHashCode copy");

        PackageManager.configureHostedPackage(
                "org.example", 1, "1", "/tmp/example.apk", "/tmp/example-data");
        PackageManager.configureHostedLauncher("org.example.MainActivity");

        ServiceInfo service = new ServiceInfo();
        service.packageName = "org.example";
        service.name = "org.example.PlaybackService";
        service.enabled = true;
        List<ServiceInfo> services = new ArrayList<>();
        services.add(service);
        Map<String, Set<String>> serviceActions = new HashMap<>();
        Set<String> actions = new HashSet<>();
        actions.add("org.example.PLAYBACK");
        serviceActions.put(service.name, actions);
        PackageManager.configureHostedServices(services, serviceActions);

        PackageManager packageManager = new PackageManager() {};
        Intent launch = packageManager.getLaunchIntentForPackage("org.example");
        require(launch != null, "hosted launch intent");
        require(new ComponentName("org.example", "org.example.MainActivity")
                        .equals(launch.getComponent()),
                "hosted launcher component");

        List<ResolveInfo> implicitServices = packageManager.queryIntentServices(
                new Intent("org.example.PLAYBACK").setPackage("org.example"), 0);
        require(implicitServices.size() == 1, "implicit hosted service resolution");
        require(packageManager.resolveService(new Intent().setComponent(
                        new ComponentName("org.example", "org.example.PlaybackService")), 0) != null,
                "explicit hosted service resolution");

        require(new NotificationManager().getActiveNotifications().length == 0,
                "host notification list");
        JobScheduler defaultScheduler = new JobScheduler();
        require(defaultScheduler.getNamespace() == null,
                "default job scheduler namespace");
        JobScheduler workScheduler = defaultScheduler.forNamespace("  androidx.work  ");
        require("androidx.work".equals(workScheduler.getNamespace()),
                "job scheduler namespace is trimmed");
        boolean rejectedEmptyNamespace = false;
        try {
            defaultScheduler.forNamespace("   ");
        } catch (IllegalArgumentException expected) {
            rejectedEmptyNamespace = true;
        }
        require(rejectedEmptyNamespace, "empty job scheduler namespace is rejected");
        boolean rejectedNullNamespace = false;
        try {
            defaultScheduler.forNamespace(null);
        } catch (NullPointerException expected) {
            rejectedNullNamespace = true;
        }
        require(rejectedNullNamespace, "null job scheduler namespace is rejected");
        require(Application.class.getMethod("getProcessName").getReturnType() == String.class,
                "Application.getProcessName API");
        require(new android.app.ActivityManager()
                        .getHistoricalProcessExitReasons(null, 0, 0).isEmpty(),
                "host has no historical process exits");
        require("org.example".equals(
                        packageManager.getApplicationInfo("org.example", 0).processName),
                "hosted application process name");
        android.app.Notification.Action authenticatedAction =
                new android.app.Notification.Action.Builder(0, "unlock", null)
                        .setAuthenticationRequired(true)
                        .build();
        require(authenticatedAction.isAuthenticationRequired(),
                "notification action authentication requirement");
        NotificationManager notificationManager = new NotificationManager();
        android.app.NotificationChannel conversationChannel = new android.app.NotificationChannel(
                "conversation", "Conversation", NotificationManager.IMPORTANCE_DEFAULT);
        conversationChannel.setConversationId("messages", "recipient-1");
        notificationManager.createNotificationChannel(conversationChannel);
        require(notificationManager.getNotificationChannel("messages", "recipient-1")
                        == conversationChannel,
                "conversation notification channel");
        require(!notificationManager.areBubblesEnabled()
                        && !notificationManager.areBubblesAllowed()
                        && notificationManager.getBubblePreference()
                                == NotificationManager.BUBBLE_PREFERENCE_NONE,
                "desktop notification bubble policy");
        require(android.hardware.Camera.getNumberOfCameras() == 0,
                "desktop legacy camera count");
        require(PendingIntent.getActivities(null, 7, new Intent[] { new Intent() }, 0,
                        new Bundle()) != null,
                "PendingIntent.getActivities options overload");
        Bundle accessibilityExtras = new Bundle();
        ArrayList<Integer> spanStarts = new ArrayList<>();
        spanStarts.add(4);
        accessibilityExtras.putIntegerArrayList("span-starts", spanStarts);
        require(accessibilityExtras.getIntegerArrayList("span-starts") == spanStarts,
                "Bundle integer array list");
        ArrayList<android.os.Parcelable> notificationItems = new ArrayList<>();
        notificationItems.add(new Intent("notification-item"));
        Intent notificationIntent = new Intent().putParcelableArrayListExtra(
                "notification-items", notificationItems);
        require(notificationIntent.getParcelableArrayListExtra("notification-items")
                        == notificationItems,
                "Intent parcelable array list extra");
        require(!new ViewRootImpl(null).getWindowInsets(false).isConsumed(),
                "host zero window insets propagate to descendants");
        WindowManagerImpl.configureWindowMetrics(411, 731, 1.5f);
        android.view.WindowMetrics resizedMetrics =
                new WindowManagerImpl(null).getCurrentWindowMetrics();
        require(resizedMetrics.getBounds().width() == 411
                        && resizedMetrics.getBounds().height() == 731
                        && resizedMetrics.getDensity() == 1.5f,
                "current window metrics follow host resize");
        require(android.content.res.HostedConfigurationController.resolveLandscape(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        1280, 720),
                "unspecified orientation follows a wide desktop window");
        require(!android.content.res.HostedConfigurationController.resolveLandscape(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        420, 800),
                "unspecified orientation follows a tall phone window");
        require(!android.content.res.HostedConfigurationController.resolveLandscape(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        1280, 720),
                "fixed portrait orientation overrides window geometry");
        require(android.content.res.HostedConfigurationController.resolveLandscape(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        420, 800),
                "fixed landscape orientation overrides window geometry");
        require(android.content.pm.ActivityInfo.activityInfoConfigNativeToJava(0x0200)
                        == android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE,
                "manifest screenSize config change uses Java ActivityInfo bit");
        boolean[] alarmFired = { false };
        AlarmManager alarmManager = new AlarmManager();
        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 30, "compat-alarm",
                () -> alarmFired[0] = true, new Handler(Looper.getMainLooper()));
        drainMainLooper();
        require(!alarmFired[0], "future alarm is not delivered immediately");
        Thread.sleep(40);
        drainMainLooper();
        require(alarmFired[0], "future alarm is delivered when due");
        RenderNode snapshotNode = new RenderNode("compat-snapshot");
        snapshotNode.setPosition(0, 0, 3, 2);
        Bitmap snapshot = ThreadedRenderer.createHardwareBitmap(snapshotNode, 3, 2);
        require(snapshot != null && snapshot.getWidth() == 3 && snapshot.getHeight() == 2,
                "host RenderNode bitmap fallback");
        Bitmap mutableIconBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        require(mutableIconBitmap.getConfig() == Bitmap.Config.ARGB_8888,
                "host 8888 bitmap config");
        Icon icon = Icon.createWithBitmap(mutableIconBitmap);
        require(icon.getBitmap() != null && !icon.getBitmap().isMutable(),
                "mutable bitmap icon copy");
        System.out.println("framework compat smoke: PASS");
    }

    private static void drainMainLooper() {
        Message message;
        while ((message = Looper.myQueue().pollDueMessage$artzig()) != null) {
            message.getTarget().dispatchMessage(message);
            message.recycle();
        }
    }
}
