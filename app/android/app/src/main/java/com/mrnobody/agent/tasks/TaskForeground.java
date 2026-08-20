package com.mrnobody.agent.tasks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;

import com.mrnobody.agent.core.Task;
import com.mrnobody.browser.R;

/** Keeps a user-started agent task scheduled while the app is backgrounded. */
final class TaskForeground {

    private static final String CHANNEL_ID = "agent_work";
    private static final int BASE_ID = 4700;

    private TaskForeground() {
    }

    static ForegroundInfo info(@NonNull Context context, @NonNull Task task) {
        ensureChannel(context);
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("mrnobody://task?id=" + task.id()));
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                context,
                (int) task.id(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_nobody)
                .setContentTitle(context.getString(R.string.notification_task_working))
                .setContentText(context.getString(R.string.notification_task_working_body))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(false)
                .build();
        int id = BASE_ID + (int) Math.floorMod(task.id(), 1000L);
        return new ForegroundInfo(
                id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_agent_work),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                context.getString(R.string.notification_channel_agent_work_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }
}
