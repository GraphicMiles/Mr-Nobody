package com.mrnobody.browser.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.debug.ErrorLog;

/**
 * Keeps the process alive while downloads are running.
 *
 * <p>A transfer we perform ourselves dies with the process unless something
 * holds it up, so this is a foreground service: it is what lets a film keep
 * downloading while the user reads something else, and — unlike the system
 * downloader — it stops when Mr Nobody is uninstalled, because it was always
 * ours.
 *
 * <p>It owns no transfer logic and, since the completion-notification bug, no
 * per-download rendering either. {@link DownloadEngine} moves the bytes,
 * {@link DownloadNotifier} draws the notifications, and this holds the process
 * up and routes the Pause / Resume / Cancel buttons back to the engine.
 *
 * <p>The split matters: this object deliberately stops itself the moment no
 * download is active, which made it the worst possible owner of the
 * notification that says a download just finished. Drawing now outlives it.
 */
public final class DownloadService extends Service implements DownloadEngine.Listener {

    public static final String ACTION_START = "com.mrnobody.download.START";
    public static final String ACTION_PAUSE = "com.mrnobody.download.PAUSE";
    public static final String ACTION_RESUME = "com.mrnobody.download.RESUME";
    public static final String ACTION_CANCEL = "com.mrnobody.download.CANCEL";

    public static final String EXTRA_ID = "id";

    private final Handler main = new Handler(Looper.getMainLooper());

    private DownloadEngine engine;
    private NotificationManager notifications;
    private boolean foreground;

    /** Start (or wake) the service to run a download that is already queued. */
    static void wake(@NonNull Context context) {
        try {
            Intent intent = new Intent(context, DownloadService.class).setAction(ACTION_START);
            context.startForegroundService(intent);
        } catch (Exception e) {
            // Background start restrictions: the transfer still runs, it just
            // has no service holding it up. Better than crashing.
            ErrorLog.record("could not start the download service: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = DownloadEngine.get(this);
        notifications = getSystemService(NotificationManager.class);
        DownloadNotifications.ensureChannel(this);
        engine.addListener(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Promote immediately: Android gives a foreground service only a few
        // seconds to post its notification before it kills the process.
        goForeground();

        String action = intent == null ? ACTION_START : intent.getAction();
        long id = intent == null ? -1 : intent.getLongExtra(EXTRA_ID, -1);
        if (action != null && id >= 0) {
            switch (action) {
                case ACTION_PAUSE:
                    engine.pause(id);
                    break;
                case ACTION_RESUME:
                    engine.resume(id);
                    break;
                case ACTION_CANCEL:
                    engine.cancel(id);
                    notifications.cancel(idFor(id));
                    break;
                default:
                    break;
            }
        }
        stopIfIdle();
        // Do not resurrect with a null intent: the engine's own reconcile()
        // decides what a restarted process should do with interrupted work.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        engine.removeListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------- engine listener

    /**
     * The service no longer draws per-download notifications.
     *
     * <p>It used to, and that was the bug: this object stops itself as soon as
     * nothing is active, and the state change that makes a download inactive
     * is COMPLETED. A renderer owned by the service was therefore shutting
     * down at the precise moment it needed to replace "downloading" with
     * "saved", so the last notification the user saw was a stale progress bar
     * on a file that had already finished. {@link DownloadNotifier} draws
     * instead, and it lives as long as the engine.
     *
     * <p>What is left here is the one thing that genuinely needs a service:
     * keeping the process alive while bytes move, and letting it go when they
     * stop.
     */
    @Override
    public void onChanged(@NonNull DownloadRecord record) {
        main.post(() -> {
            try {
                updateSummary();
                stopIfIdle();
            } catch (Throwable t) {
                ErrorLog.record("download service update failed: " + t);
            }
        });
    }

    // ------------------------------------------------------------- lifecycle

    private void goForeground() {
        if (foreground) return;
        Notification summary = DownloadNotifications.summary(this, activeCount());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(DownloadNotifications.SUMMARY_ID, summary,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(DownloadNotifications.SUMMARY_ID, summary);
            }
            foreground = true;
        } catch (Exception e) {
            ErrorLog.record("download service could not go foreground: " + e);
        }
    }

    private void updateSummary() {
        if (!foreground || !DownloadNotifications.canNotify(this)) return;
        int active = activeCount();
        if (active == 0) return;
        notifications.notify(DownloadNotifications.SUMMARY_ID,
                DownloadNotifications.summary(this, active));
    }

    private int activeCount() {
        int active = 0;
        for (DownloadRecord record : engine.store().all()) {
            if (record.status.isActive()) active++;
        }
        return active;
    }

    /** Nothing left to carry: drop the notification and let the process go. */
    private void stopIfIdle() {
        if (engine.hasActiveWork() || activeCount() > 0) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static int idFor(long recordId) {
        DownloadRecord stub = new DownloadRecord();
        stub.id = recordId;
        return DownloadNotifications.idFor(stub);
    }
}
