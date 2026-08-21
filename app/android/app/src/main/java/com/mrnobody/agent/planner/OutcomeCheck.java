package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;

import java.util.List;
import java.util.Locale;

/**
 * Did the outcome satisfy the instruction? A deterministic check on the two
 * promises the engine can actually verify about itself:
 *
 * <ul>
 *   <li><b>A download instruction must end in a download.</b> "download a
 *       png icon from pngtree" once completed as a research summary with no
 *       file, and nothing said so in as many words.</li>
 *   <li><b>A named site must be among the sources actually read.</b> When
 *       "from nkiri.ink" gets answered entirely from substitutes, the reader
 *       is told, instead of assuming the named site was consulted.</li>
 * </ul>
 *
 * <p>Returns a note to append to the answer, or {@code ""} when the outcome
 * matches the ask (or the mismatch is already stated explicitly — a
 * "Download failed: …" line needs no echo).
 */
public final class OutcomeCheck {

    private OutcomeCheck() {
    }

    /**
     * @param instruction  what the user asked
     * @param downloadNote the engine's download outcome line, or null
     * @param readUrls     the pages actually read as sources
     */
    public static String note(String instruction, String downloadNote, List<String> readUrls) {
        if (instruction == null || instruction.trim().isEmpty()) return "";

        StringBuilder out = new StringBuilder();

        if (ToolRouter.isDownloadIntent(instruction)) {
            String d = downloadNote == null ? "" : downloadNote;
            boolean happened = d.startsWith("Downloaded ")
                    || d.startsWith("Download still in progress");
            boolean alreadyExplicit = d.startsWith("Download failed")
                    || d.startsWith("No downloadable file");
            if (!happened && !alreadyExplicit) {
                out.append("The instruction asked for a download, but no file "
                        + "was downloaded.");
            }
        }

        String named = Hosts.firstIn(instruction);
        if (named != null && !named.isEmpty() && readUrls != null && !readUrls.isEmpty()
                && !anyOnHost(readUrls, named)) {
            if (out.length() > 0) out.append(' ');
            out.append("Note: ").append(named).append(", which the instruction named, "
                    + "was not among the pages actually read; the answer uses "
                    + "other sources.");
        }

        return out.toString();
    }

    private static boolean anyOnHost(List<String> urls, String host) {
        String want = host.toLowerCase(Locale.ROOT);
        if (want.startsWith("www.")) want = want.substring(4);
        for (String url : urls) {
            String h = Hosts.firstIn(url);
            if (h == null) continue;
            h = h.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            if (h.equals(want) || h.endsWith("." + want)) return true;
        }
        return false;
    }
}
