package com.mrnobody.browser.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * The folder downloads are written to.
 *
 * <p>This used to describe a staging dance: {@code DownloadManager} cannot
 * write into a Storage Access Framework tree, so a download was written to
 * app-private storage and copied into the user's folder afterwards. That is
 * why choosing a folder still left the file in
 * {@code Android/data/com.mrnobody.browser/files/staging}. {@link
 * DownloadEngine} performs the transfer itself and writes into the chosen
 * folder from the first byte, so all that is left here is the grant and its
 * label.
 */
public final class DownloadDestination {

    private static final String PREFS = "mrnobody_downloads";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";

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

    // -------------------------------------------------------------- naming

    /**
     * Never overwrite: two downloads can share a name, and silently replacing
     * someone's file is not a thing a browser gets to do.
     */
    public static String uniqueName(java.util.Set<String> taken, String fileName) {
        if (!taken.contains(fileName)) return fileName;
        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String next = stem + " (" + i + ")" + extension;
            if (!taken.contains(next)) return next;
        }
        return stem + "-" + System.currentTimeMillis() + extension;
    }
}
