package com.mrnobody.browser.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

/**
 * Where downloads are written, and how a file gets to a folder the user chose.
 *
 * <p>DownloadManager can only write to paths this app owns or to the public
 * Downloads directory — it cannot write into a Storage Access Framework tree.
 * So when the user picks a folder, a download is <em>staged</em> in app-private
 * storage and moved into the tree once it finishes
 * ({@link DownloadMoveWorker}). Without a chosen folder nothing changes: the
 * file goes straight to public Downloads, where every other app puts them.
 */
public final class DownloadDestination {

    private static final String PREFS = "mrnobody_downloads";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";
    private static final String PENDING_PREFIX = "pending_";

    /** Subdirectory of the app's external files dir used for staging. */
    public static final String STAGING_DIR = "staging";

    private final SharedPreferences prefs;

    public DownloadDestination(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- the folder

    /** The tree the user picked, or null for "wherever Android puts downloads". */
    public Uri treeUri() {
        String stored = prefs.getString(KEY_TREE, "");
        return stored == null || stored.isEmpty() ? null : Uri.parse(stored);
    }

    /** A name to show in Settings — the folder's display name, or the default. */
    public String label() {
        String stored = prefs.getString(KEY_TREE_LABEL, "");
        return stored == null || stored.isEmpty() ? "Downloads (system)" : stored;
    }

    public boolean isCustom() {
        return treeUri() != null;
    }

    public void setTree(Uri uri, String label) {
        prefs.edit()
                .putString(KEY_TREE, uri == null ? "" : uri.toString())
                .putString(KEY_TREE_LABEL, label == null ? "" : label)
                .apply();
    }

    public void clearTree() {
        prefs.edit().remove(KEY_TREE).remove(KEY_TREE_LABEL).apply();
    }

    // ------------------------------------------------------------- staging

    /**
     * The file a download is written to while it runs, when it will be moved
     * afterwards. App-private, so nothing half-downloaded appears in the user's
     * folder — and a failed move leaves the bytes somewhere we can retry from.
     */
    public static File stagingFile(Context context, String fileName) {
        File dir = new File(context.getExternalFilesDir(null), STAGING_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, uniqueName(dir, fileName));
    }

    /** Never overwrite a staged file: two downloads can share a name. */
    static String uniqueName(File dir, String fileName) {
        File candidate = new File(dir, fileName);
        if (!candidate.exists()) return fileName;
        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String next = stem + " (" + i + ")" + extension;
            if (!new File(dir, next).exists()) return next;
        }
        return stem + "-" + System.currentTimeMillis() + extension;
    }

    // ------------------------------------------------------- pending moves

    /**
     * Remember that this download is staged and where it should end up. The
     * process can die between enqueueing and completion, so this has to be on
     * disk rather than in a map.
     */
    public void rememberPending(long downloadId, String stagedPath, String fileName, String mime) {
        prefs.edit()
                .putString(PENDING_PREFIX + downloadId,
                        encode(stagedPath, fileName, mime == null ? "" : mime))
                .apply();
    }

    public Pending pending(long downloadId) {
        String raw = prefs.getString(PENDING_PREFIX + downloadId, "");
        return decode(raw);
    }

    public void forgetPending(long downloadId) {
        prefs.edit().remove(PENDING_PREFIX + downloadId).apply();
    }

    /** A staged download waiting to be moved into the user's folder. */
    public static final class Pending {
        public final String stagedPath;
        public final String fileName;
        public final String mime;

        Pending(String stagedPath, String fileName, String mime) {
            this.stagedPath = stagedPath;
            this.fileName = fileName;
            this.mime = mime;
        }
    }

    // Values are joined with a character that cannot appear in a path segment
    // we generate; kept package-visible so the format is unit-tested.
    static final String SEPARATOR = "\u001f";

    static String encode(String stagedPath, String fileName, String mime) {
        return stagedPath + SEPARATOR + fileName + SEPARATOR + mime;
    }

    static Pending decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(SEPARATOR, -1);
        if (parts.length < 2 || parts[0].isEmpty()) return null;
        String mime = parts.length > 2 ? parts[2] : "";
        return new Pending(parts[0], parts[1], mime.isEmpty() ? null : mime);
    }
}
