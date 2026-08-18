package com.mrnobody.browser.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.NonNull;

import com.mrnobody.debug.ErrorLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the notification for every download, whatever the service is doing.
 *
 * <p>This exists because of a bug worth naming. Notifications used to be
 * rendered by {@link DownloadService}, which registers itself as an engine
 * listener when it is created and unregisters when it is destroyed. The
 * service also stops itself the moment no download is active — and the state
 * change that makes a download inactive is the very last one, {@code
 * COMPLETED}. So the finish notification was posted by an object that was in
 * the process of shutting down, and any callback that arrived a moment later,
 * or arrived while the service was already gone, was delivered to nobody. The
 * last thing the user saw was the final progress update, which said the file
 * was still downloading. The bytes were on disk; only the notification lied.
 *
 * <p>The fix is not a longer timeout or a delayed stop, both of which just
 * make the race rarer. Rendering is moved onto an object with the lifetime of
 * the engine itself. The service is still what keeps the process alive while
 * bytes move, which is a genuinely different job, and it no longer has any say
 * in whether a completion is drawn.
 *
 * <p>It also reconciles on startup: a notification is device state that
 * outlives the process, so a transfer killed mid-flight leaves a progress bar
 * on screen that nothing will ever update again. {@link #reconcile()} clears
 * those, because a stale notification is worse than none — it is a claim about
 * right now that happens to be false.
 */
final class DownloadNotifier implements DownloadEngine.Listener {

    private final Context context;

    /** id → (bytes, at) from the previous update, for the speed readout. */
    private final Map<Long, long[]> lastSample = new HashMap<>();
    private final Map<Long, Long> speed = new HashMap<>();

    DownloadNotifier(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onChanged(@NonNull DownloadRecord record) {
        // Snapshot first. The engine mutates its record from a worker thread,
        // so reading fields while building a notification can mix a running
        // byte count into a completed layout.
        render(snapshot(record));
    }

    private void render(DownloadRecord record) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        int id = DownloadNotifications.idFor(record);
        try {
            if (record.status.isTerminal()) {
                lastSample.remove(record.id);
                speed.remove(record.id);
            }
            Notification notification =
                    DownloadNotifications.forRecord(context, record, sampleSpeed(record));
            if (notification == null) {
                nm.cancel(id);
            } else if (DownloadNotifications.canNotify(context)) {
                nm.notify(id, notification);
            }
        } catch (Throwable t) {
            // A download that finished must never look unfinished because
            // drawing it failed. Clear the row rather than leave a progress
            // bar that will never move again.
            ErrorLog.record("download notification failed: " + t);
            if (record.status.isTerminal()) {
                try {
                    nm.cancel(id);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Clear notifications describing work that is no longer happening.
     *
     * <p>Called when the app starts. Anything the database still calls active
     * was interrupted by process death — {@link DownloadEngine#reconcile()}
     * moves those rows to WAITING, and this makes the notification agree.
     */
    void reconcile() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.cancel(DownloadNotifications.SUMMARY_ID);
            for (DownloadRecord record : DownloadStore.get(context).all()) {
                if (record.status.isActive()) {
                    nm.cancel(DownloadNotifications.idFor(record));
                }
            }
        } catch (Throwable t) {
            ErrorLog.record("notification reconcile failed: " + t);
        }
    }

    /**
     * Bytes per second, smoothed. A raw per-tick delta jumps around enough to
     * be unreadable, and a number that flickers is worse than no number.
     */
    private long sampleSpeed(DownloadRecord record) {
        if (record.status != DownloadRecord.Status.RUNNING) return 0;
        long now = System.currentTimeMillis();
        long[] previous = lastSample.get(record.id);
        lastSample.put(record.id, new long[]{record.bytes, now});
        if (previous == null) return 0;
        long elapsed = now - previous[1];
        Long known = speed.get(record.id);
        if (elapsed < 300) return known == null ? 0L : known;
        long delta = record.bytes - previous[0];
        long instant = delta <= 0 ? 0 : delta * 1000L / elapsed;
        long prior = known == null ? instant : known;
        long smoothed = (long) (prior * 0.6 + instant * 0.4);
        speed.put(record.id, smoothed);
        return smoothed;
    }

    /** A copy that cannot change underneath the notification builder. */
    private static DownloadRecord snapshot(DownloadRecord live) {
        DownloadRecord copy = new DownloadRecord();
        copy.id = live.id;
        copy.url = live.url;
        copy.fileName = live.fileName;
        copy.mime = live.mime;
        copy.destUri = live.destUri;
        copy.destLabel = live.destLabel;
        copy.total = live.total;
        copy.bytes = live.bytes;
        copy.status = live.status;
        copy.error = live.error;
        copy.resumable = live.resumable;
        copy.createdAt = live.createdAt;
        copy.updatedAt = live.updatedAt;
        return copy;
    }
}
