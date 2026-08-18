package com.mrnobody.browser.download;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mrnobody.debug.ErrorLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves a finished download from app-private staging into the folder the user
 * picked.
 *
 * <p>Runs as a WorkManager job rather than inside the completion broadcast: a
 * film is gigabytes, a broadcast receiver gets seconds, and a copy that is
 * killed halfway would leave the user with a truncated file and no way to tell.
 * If the move fails the staged file is kept, so the bytes are never lost to a
 * retry that cannot happen.
 */
public final class DownloadMoveWorker extends Worker {

    private static final String KEY_ID = "download_id";

    public DownloadMoveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Queue the move for a download that has just finished. */
    public static void enqueue(Context context, long downloadId) {
        Data input = new Data.Builder().putLong(KEY_ID, downloadId).build();
        WorkManager.getInstance(context).enqueue(
                new OneTimeWorkRequest.Builder(DownloadMoveWorker.class)
                        .setInputData(input)
                        .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        long id = getInputData().getLong(KEY_ID, -1);
        if (id < 0) return Result.failure();

        Context context = getApplicationContext();
        DownloadDestination destination = new DownloadDestination(context);
        DownloadDestination.Pending pending = destination.pending(id);
        if (pending == null) return Result.success(); // nothing staged for this one

        Uri tree = destination.treeUri();
        File staged = new File(pending.stagedPath);
        if (!staged.exists()) {
            destination.forgetPending(id);
            return Result.success();
        }
        if (tree == null) {
            // The folder was cleared while this was downloading. Leave the file
            // where it is and say so, rather than guessing a new destination.
            ErrorLog.record("Download folder was cleared; " + pending.fileName
                    + " is in the app's storage.");
            destination.forgetPending(id);
            return Result.success();
        }

        try {
            Uri created = createDocument(context, tree, pending.fileName, pending.mime);
            if (created == null) {
                ErrorLog.record("Could not create " + pending.fileName + " in the chosen folder.");
                return Result.retry();
            }
            copy(context, staged, created);
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            destination.forgetPending(id);
            return Result.success();
        } catch (Exception e) {
            ErrorLog.record("Moving " + pending.fileName + " failed: " + e);
            // Keep the staged file; a retry can still complete the move.
            return Result.retry();
        }
    }

    private static Uri createDocument(Context context, Uri tree, String name, String mime)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        return DocumentsContract.createDocument(
                resolver, parent, mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                name);
    }

    private static void copy(Context context, File from, Uri to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = context.getContentResolver().openOutputStream(to)) {
            if (out == null) throw new IllegalStateException("no output stream for " + to);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
