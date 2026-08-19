package com.mrnobody.agent.util;

import android.content.Context;

import com.mrnobody.browser.net.NetworkGate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Fetch a preview image over the same privacy route as a page read.
 *
 * <p>Flutter must not open the CDN itself: that would bypass
 * {@link NetworkGate} and leak the reading list on a Nobody session.
 * The agent downloads a bounded thumbnail into the workspace; the UI
 * only paints a local file.
 */
public final class PageImage {

    static final int MAX_BYTES = 400 * 1024;
    static final int CONNECT_MS = 8_000;
    static final int READ_MS = 10_000;

    private PageImage() {
    }

    /**
     * Download {@code url} into {@code workspace/cards/}. Returns the
     * absolute path, or empty when the fetch is refused or not an image.
     */
    public static String download(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return "";
        if (!HtmlText.usableImage(url)) return "";
        if (!NetworkGate.canConnect()) return "";
        String host = Hosts.firstIn(url);
        if (!HostRateLimit.tryAcquire(host)) return "";
        File dir = new File(context.getFilesDir(), "workspace/cards");
        if (!dir.isDirectory() && !dir.mkdirs()) return "";
        String ext = extensionOf(url);
        File dest = new File(dir, Integer.toHexString(url.hashCode()) + ext);
        if (dest.isFile() && dest.length() > 32) return dest.getAbsolutePath();
        HttpURLConnection conn = null;
        try {
            conn = NetworkGate.openHttp(url);
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MrNobody/1.0");
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "";
            String type = conn.getContentType();
            if (type != null && !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                return "";
            }
            File tmp = new File(dest.getAbsolutePath() + ".part");
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    int keep = Math.min(n, MAX_BYTES - total);
                    if (keep <= 0) break;
                    out.write(buf, 0, keep);
                    total += keep;
                }
                if (total < 32) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return "";
                }
            }
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return "";
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String extensionOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        if (lower.endsWith(".avif")) return ".avif";
        return ".jpg";
    }
}
