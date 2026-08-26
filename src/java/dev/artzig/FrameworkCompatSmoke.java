/* artzig: focused smoke checks for hosted-package framework behavior. */
package dev.artzig;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.RenderNode;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.ThreadedRenderer;
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

    public static void main(String[] args) {
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
}
