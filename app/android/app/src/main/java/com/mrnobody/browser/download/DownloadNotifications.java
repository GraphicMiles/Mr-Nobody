package com.mrnobody.browser.download;

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

import com.mrnobody.browser.R;

import java.util.Locale;

/**
 * Mr Nobody's own download notification.
 *
 * <p>Handing files to {@code DownloadManager} meant the notification was
 * Android's: the system's wording, the system's icon, no pause, and no way to
 * reach the app's own Downloads screen from it. This is the app's, so it says
 * what the app knows — the real file name, the folder it is going to, the
 * speed — and carries the two buttons that matter.
 *
 * <p>Quiet by design: the channel is low importance, so a download does not
 * buzz a phone, and nothing here names a URL or a page.
 */
final class DownloadNotifications {

    static final String CHANNEL_ID = "downloads";

    /** The foreground-service notification: one summary for the whole engine. */
    static final int SUMMARY_ID = 4300;

    /** Per-download ids start above the summary. */
    private static final int PER_DOWNLOAD_BASE = 4400;

    private DownloadNotifications() {
    }

    static void ensureChannel(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_downloads),
                // A file arriving is information, not an interruption.
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_downloads_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    static boolean canNotify(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static int idFor(@NonNull DownloadRecord record) {
        return PER_DOWNLOAD_BASE + (int) (record.id % 1000);
    }

    /** The notification that keeps the service in the foreground. */
    static Notification summary(@NonNull Context context, int active) {
        ensureChannel(context);
        String text = active == 1
                ? context.getString(R.string.download_summary_one)
                : context.getString(R.string.download_summary_many, active);
        return base(context)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openDownloads(context))
                .build();
    }

    /**
     * One download's notification: progress while it runs, the outcome when it
     * stops, and the controls the system downloader never offered.
     */
    static Notification forRecord(@NonNull Context context, @NonNull DownloadRecord record,
                                  long bytesPerSecond) {
        ensureChannel(context);
        Notification.Builder b = base(context)
                .setContentTitle(record.fileName)
                .setContentIntent(openDownloads(context));

        switch (record.status) {
            case QUEUED:
                b.setContentText(context.getString(R.string.download_queued))
                        .setProgress(0, 0, true)
                        .setOngoing(true)
                        .addAction(action(context, R.string.download_cancel,
                                DownloadService.ACTION_CANCEL, record.id));
                break;
            case RUNNING:
                b.setContentText(progressText(context, record, bytesPerSecond))
                        .setSubText(record.destLabel)
                        .setOngoing(true)
                        .addAction(action(context, R.string.download_pause,
                                DownloadService.ACTION_PAUSE, record.id))
                        .addAction(action(context, R.string.download_cancel,
                                DownloadService.ACTION_CANCEL, record.id));
                if (record.percent() >= 0) {
                    b.setProgress(100, record.percent(), false);
                } else {
                    b.setProgress(0, 0, true);
                }
                break;
            case PAUSED:
            case WAITING:
                b.setContentText(record.status == DownloadRecord.Status.PAUSED
                                ? context.getString(R.string.download_paused_at,
                                        formatBytes(record.bytes))
                                : context.getString(R.string.download_stalled))
                        .setOngoing(false)
                        .setProgress(100, Math.max(record.percent(), 0), false)
                        .addAction(action(context, R.string.download_resume,
                                DownloadService.ACTION_RESUME, record.id))
                        .addAction(action(context, R.string.download_cancel,
                                DownloadService.ACTION_CANCEL, record.id));
                break;
            case COMPLETED:
                b.setContentText(context.getString(R.string.download_saved_to,
                                record.destLabel == null ? "" : record.destLabel))
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setProgress(0, 0, false);
                PendingIntent open = openFile(context, record);
                if (open != null) b.setContentIntent(open);
                break;
            case FAILED:
                b.setContentText(record.error == null
                                ? context.getString(R.string.download_failed) : record.error)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setProgress(0, 0, false)
                        .addAction(action(context, R.string.download_retry,
                                DownloadService.ACTION_RESUME, record.id));
                break;
            case CANCELLED:
            default:
                return null;
        }
        return b.build();
    }

    private static Notification.Builder base(Context context) {
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_nobody)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(false);
    }

    private static String progressText(Context context, DownloadRecord record, long speed) {
        StringBuilder sb = new StringBuilder();
        sb.append(formatBytes(record.bytes));
        if (record.total > 0) sb.append(" / ").append(formatBytes(record.total));
        if (speed > 0) sb.append("  ·  ").append(formatBytes(speed)).append("/s");
        return sb.toString();
    }

    private static Notification.Action action(Context context, int labelRes, String action,
                                              long id) {
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(DownloadService.EXTRA_ID, id);
        // Distinct request codes: two downloads must not share a Pause button.
        int request = (int) (id * 10 + action.hashCode() % 7);
        PendingIntent pending = PendingIntent.getService(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, context.getString(labelRes), pending).build();
    }

    private static PendingIntent openDownloads(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("mrnobody://downloads"));
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openFile(Context context, DownloadRecord record) {
        if (record.destUri == null) return null;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(record.destUri),
                            record.mime == null ? "*/*" : record.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context, (int) record.id, view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sizes people recognise, not raw bytes. */
    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
