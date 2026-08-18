package com.mrnobody.browser.download;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Hears Android say a download finished, and queues the move into the user's
 * folder if that download was staged for one.
 *
 * <p>Declared in the manifest rather than registered at runtime: a download
 * outlives the app, and the whole point is that the file arrives where the
 * user asked even if they closed Mr Nobody an hour ago.
 * {@code ACTION_DOWNLOAD_COMPLETE} is one of the broadcasts still delivered to
 * manifest receivers on modern Android.
 */
public final class DownloadCompleteReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id < 0) return;
        if (new DownloadDestination(context).pending(id) == null) {
            return; // an ordinary download straight to public Downloads
        }
        // The copy itself is a job, not broadcast work: a film takes longer
        // than a receiver is allowed to live.
        DownloadMoveWorker.enqueue(context, id);
    }
}
