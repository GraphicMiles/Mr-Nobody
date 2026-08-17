package com.mrnobody.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;

import com.mrnobody.agent.core.Task;

/**
 * Tells the user when a background task finished.
 *
 * <p>A task can outlive the UI (V1 §13: task saved → worker wakes → agent runs
 * → worker exits → notification). Without this last step a task that completes
 * while the app is closed is invisible until the user happens to reopen it.
 *
 * <p>Deliberately built on the platform APIs only — no support library, no
 * extra dependency, nothing that phones home.
 */
public final class TaskNotifier {

    private static final String CHANNEL_ID = "tasks";
    private static final int NOTIFICATION_BASE = 4200;

    private TaskNotifier() {
    }

    /** Create the notification channel once per process. Safe to call repeatedly. */
    public static void ensureChannel(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_tasks_desc));
        channel.setShowBadge(true);
        nm.createNotificationChannel(channel);
    }

    /** Whether we are allowed to post at all (Android 13+ asks the user). */
    public static boolean canNotify(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Post the outcome of a finished task. Tapping it deep-links into the Tasks
     * screen; the body is the instruction the user typed, never page content.
     */
    public static void notifyFinished(@NonNull Context context, @NonNull Task task) {
        if (!canNotify(context)) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(context);

        boolean ok = task.status() == Task.Status.COMPLETED;
        String title = ok ? context.getString(R.string.notification_task_done)
                : context.getString(R.string.notification_task_failed);

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("mrnobody://tasks"));
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                context,
                (int) task.id(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_nobody)
                .setContentTitle(title)
                .setContentText(task.instruction())
                .setStyle(new Notification.BigTextStyle().bigText(
                        ok && task.result() != null && !task.result().isEmpty()
                                ? task.instruction() + "\n\n" + preview(task.result())
                                : task.instruction()))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();

        nm.notify(NOTIFICATION_BASE + (int) task.id(), notification);
    }

    private static String preview(String result) {
        String trimmed = result.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
