package com.mrnobody.browser.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Where a download's bytes actually land.
 *
 * <p>The old design wrote every download into app-private staging and copied
 * it to the user's folder at the end, because {@code DownloadManager} cannot
 * write into a Storage Access Framework tree. That is why picking a folder
 * still produced a file in
 * {@code Android/data/com.mrnobody.browser/files/staging} — and why deleting
 * the app could not stop it. Writing the transfer ourselves removes the
 * problem at the root: bytes go straight into the destination the user chose,
 * from the first byte, and there is no second copy of a four-gigabyte film.
 *
 * <p>Two destinations, one interface:
 * <ul>
 *   <li>a document in the folder the user granted us (SAF), or
 *   <li>an entry in the public Downloads collection (MediaStore), which is
 *       where files go when no folder has been picked.
 * </ul>
 *
 * <p>Both are opened in append mode so a paused download resumes by writing
 * onto the end of what is already there, rather than starting again.
 */
final class DownloadSink {

    private final Context context;
    private final Uri uri;
    private final boolean mediaStore;

    private DownloadSink(Context context, Uri uri, boolean mediaStore) {
        this.context = context;
        this.uri = uri;
        this.mediaStore = mediaStore;
    }

    Uri uri() {
        return uri;
    }

    /**
     * Create the destination file and return a sink for it.
     *
     * <p>Called after the first response, not at enqueue time: the server's
     * {@code Content-Disposition} and {@code Content-Type} are what decide the
     * real name and extension, and creating the file before knowing them is
     * how a {@code .mkv} ends up called {@code downloadfile.bin}.
     */
    static DownloadSink create(@NonNull Context context, @Nullable Uri tree,
                               @NonNull String fileName, @Nullable String mime)
            throws IOException {
        String type = mime == null || mime.trim().isEmpty()
                ? "application/octet-stream" : mime.trim();
        return tree != null
                ? createInTree(context, tree, fileName, type)
                : createInDownloads(context, fileName, type);
    }

    /** Re-open a destination that a previous run already created. */
    static DownloadSink existing(@NonNull Context context, @NonNull Uri uri) {
        return new DownloadSink(context, uri, isMediaStore(uri));
    }

    private static DownloadSink createInTree(Context context, Uri tree, String fileName,
                                             String mime) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        try {
            Uri created = DocumentsContract.createDocument(
                    context.getContentResolver(), parent, mime, fileName);
            if (created == null) {
                throw new IOException("The chosen folder refused a new file");
            }
            return new DownloadSink(context, created, false);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // A revoked grant, a removed SD card, a provider that died.
            throw new IOException("Cannot write to the chosen folder: " + e.getMessage(), e);
        }
    }

    private static DownloadSink createInDownloads(Context context, String fileName, String mime)
            throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        // Hidden from other apps until it is whole: a half-downloaded film
        // should not show up in the gallery as a broken file.
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        try {
            Uri created = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (created == null) throw new IOException("Downloads folder refused a new file");
            return new DownloadSink(context, created, true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot write to Downloads: " + e.getMessage(), e);
        }
    }

    /**
     * Open for writing at the end of what exists ({@code wa}), or from scratch
     * ({@code wt}) when the server would not honour a range and the transfer
     * has to start over.
     */
    OutputStream open(boolean append) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        OutputStream out = resolver.openOutputStream(uri, append ? "wa" : "wt");
        if (out == null) throw new IOException("No output stream for " + uri);
        return out;
    }

    /** How many bytes are already on disk — the truth a resume must start from. */
    long size() {
        try (android.os.ParcelFileDescriptor fd =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            return fd == null ? 0 : Math.max(0, fd.getStatSize());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Make the finished file visible to the rest of the device. Only MediaStore
     * needs this; a SAF document was always visible in the user's folder.
     */
    void publish() {
        if (!mediaStore) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
        } catch (Exception ignored) {
            // The bytes are written; visibility is a nicety, not the download.
        }
    }

    /** Remove the file — a cancelled download must not leave a stub behind. */
    void discard() {
        try {
            if (mediaStore) {
                context.getContentResolver().delete(uri, null, null);
            } else {
                DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        } catch (Exception ignored) {
            // Already gone, or the user deleted it themselves. Either is fine.
        }
    }

    private static boolean isMediaStore(Uri uri) {
        return MediaStore.AUTHORITY.equals(uri.getAuthority());
    }

    /** Delete whatever a record points at, without needing a live sink. */
    static void discard(@NonNull Context context, @Nullable String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        try {
            existing(context, Uri.parse(uriString)).discard();
        } catch (Exception ignored) {
        }
    }
}
