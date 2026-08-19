package com.mrnobody.browser.download;

import com.mrnobody.agent.util.DownloadLinkResolver;

import java.util.List;
import java.util.Locale;

/**
 * Finds the original HTTP file behind a page-created blob: download.
 *
 * <p>A blob URL is private to the page's JavaScript memory, so Android's HTTP
 * downloader cannot open it. Many sites first fetch an ordinary file, wrap it
 * in a blob and click a temporary link. The page still exposes the source in
 * metadata, a download anchor or the Resource Timing list. This class ranks
 * those candidates without special-casing a site or a file type.
 */
public final class BlobSourceResolver {

    public static final class Candidate {
        public final String url;
        public final String kind;

        public Candidate(String url, String kind) {
            this.url = url == null ? "" : url.trim();
            this.kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        }
    }

    private BlobSourceResolver() {
    }

    /** Return the strongest plausible backing file, or null rather than guess. */
    public static String resolve(List<Candidate> candidates, String mimeType) {
        if (candidates == null) return null;
        String expectedExtension = DownloadNaming.extensionForMime(mimeType);
        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Candidate candidate : candidates) {
            if (candidate == null || !isHttp(candidate.url)) continue;
            String lower = candidate.url.toLowerCase(Locale.ROOT);
            int score = kindScore(candidate.kind);

            boolean extensionMatches = expectedExtension != null
                    && path(lower).endsWith("." + expectedExtension);
            boolean plainlyDownloadable = DownloadLinkResolver.isDownloadable(candidate.url);

            if (extensionMatches) score += 500;
            if (plainlyDownloadable) score += 250;
            if (lower.contains("thumbnail") || lower.contains("/thumb/")) score -= 800;

            // A performance entry with no file/download evidence could be an
            // ad, script or font. Metadata and explicit download anchors are
            // authored statements about the page's primary file and may stand
            // on their own; resource entries may not.
            boolean trustedKind = "content".equals(candidate.kind)
                    || "download".equals(candidate.kind);
            if (!trustedKind && !extensionMatches && !plainlyDownloadable) continue;

            if (score > bestScore) {
                best = candidate.url;
                bestScore = score;
            }
        }
        return best;
    }

    private static int kindScore(String kind) {
        switch (kind) {
            case "content":
                return 1_000;
            case "download":
                return 800;
            case "resource":
                return 400;
            default:
                return 0;
        }
    }

    private static boolean isHttp(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static String path(String url) {
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        int fragment = url.indexOf('#');
        if (fragment >= 0) url = url.substring(0, fragment);
        return url;
    }
}
