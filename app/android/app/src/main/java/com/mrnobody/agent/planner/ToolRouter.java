package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.util.Hosts;

import java.util.Collection;
import java.util.Locale;

/**
 * Chooses which tool an instruction needs.
 *
 * <p>This is the thing whose absence produced the reported bug. The planner ran
 * a fixed cascade — search, read, answer, verify — and hard-coded two tool
 * calls. {@code DownloadTool}, {@code BrowserTool} and {@code TerminalTool}
 * were registered and unreachable, so "search for X from example.com and
 * download it" could never download: not refused, simply never attempted, and
 * the model answered a research question instead.
 *
 * <p>Deterministic on purpose. A model asked to pick a tool will occasionally
 * pick a plausible wrong one, and the cost of that here is a download the user
 * did not ask for. Rules are auditable, testable without a network, and cheap;
 * when they do not match, the answer is the research cascade, which is the
 * behaviour that was always there.
 *
 * <p>Selection is <em>not</em> permission. Everything chosen here still goes
 * through {@code ToolPipeline}, so a routed EXEC call is a call the user is
 * asked about, not one that happens because a regex matched.
 */
public final class ToolRouter {

    /** What the router decided, and why. */
    public static final class Route {
        public final String tool;
        public final ToolRequest request;
        public final String reason;

        Route(String tool, ToolRequest request, String reason) {
            this.tool = tool;
            this.request = request;
            this.reason = reason;
        }
    }

    /** Verbs that mean "fetch this file", as opposed to "tell me about it". */
    private static final String[] DOWNLOAD_VERBS = {
            "download", "save", "grab", "fetch the file", "get the file"
    };

    /** Things a user is plausibly asking to download rather than read. */
    private static final String[] FILE_HINTS = {
            ".mkv", ".mp4", ".avi", ".mov", ".webm", ".mp3", ".m4a", ".flac",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".iso", ".apk", ".pdf",
            ".epub", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".csv"
    };

    private ToolRouter() {
    }

    /**
     * Pick a tool for {@code instruction}, or null to run the research
     * cascade.
     *
     * @param available tools the engine currently has. A tool that is not
     *                  registered is never routed to — the terminal switch
     *                  being off must mean the terminal cannot be reached,
     *                  not that it is reached and refused.
     */
    public static Route route(String instruction, Collection<String> available) {
        if (instruction == null || instruction.trim().isEmpty()) return null;

        String text = instruction.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        Route download = routeDownload(text, lower, available);
        if (download != null) return download;

        return null; // research cascade
    }

    /**
     * True when the instruction asks to download/save/grab something, whether
     * or not it names a direct URL. Used by the planner to add a
     * "resolve download" step for the common case of a download asked by name
     * rather than by link.
     */
    public static boolean isDownloadIntent(String instruction) {
        if (instruction == null || instruction.trim().isEmpty()) return false;
        return mentionsAny(instruction.trim().toLowerCase(Locale.ROOT), DOWNLOAD_VERBS);
    }

    private static Route routeDownload(String text, String lower, Collection<String> available) {
        if (!has(available, "download")) return null;
        if (!mentionsAny(lower, DOWNLOAD_VERBS)) return null;

        // A download needs something to download. The instruction names either
        // a direct file URL, or a site to look on -- and only the first can be
        // acted on without reading a page first.
        String url = firstUrl(text);
        if (url == null) return null;

        // "download the report from example.com" names a site, not a file. The
        // cascade has to read the page and find the link, so routing straight
        // to the downloader would fetch the HTML and call it a file.
        if (!looksLikeAFile(url) && !mentionsAny(lower, FILE_HINTS)) return null;

        return new Route("download", ToolRequest.of("download", "url", url),
                "the instruction names a file to download");
    }

    /** A URL written with a scheme, or a bare host promoted to https. */
    private static String firstUrl(String text) {
        int at = indexOfScheme(text);
        if (at >= 0) {
            int end = at;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                    && "\"'<>".indexOf(text.charAt(end)) < 0) {
                end++;
            }
            String candidate = trimTrailingPunctuation(text.substring(at, end));
            if (candidate.length() > "https://".length()) return candidate;
        }
        String host = Hosts.firstIn(text);
        return host == null ? null : "https://" + host;
    }

    private static int indexOfScheme(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int https = lower.indexOf("https://");
        int http = lower.indexOf("http://");
        if (https < 0) return http;
        if (http < 0) return https;
        return Math.min(https, http);
    }

    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0 && ".,;:!?)]}".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    /** True when the URL's path ends in something that is plainly a file. */
    private static boolean looksLikeAFile(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        int h = lower.indexOf('#');
        if (h >= 0) lower = lower.substring(0, h);
        for (String ext : FILE_HINTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean mentionsAny(String lower, String[] needles) {
        for (String n : needles) {
            if (lower.contains(n)) return true;
        }
        return false;
    }

    private static boolean has(Collection<String> available, String tool) {
        return available != null && available.contains(tool);
    }
}
