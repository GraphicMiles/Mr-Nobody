package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The site or account a user pointed at, found by structure, not vocabulary.
 *
 * <p>Intent ("keep watching" vs "get this file") is a reasoning problem.
 * Noticing that {@code nkiri.ink} or {@code @Marvel} is a target is a
 * parsing problem. Mixing the two is how "download it from nkiri.ink"
 * became a web search: the planner was waiting for a verb it knew, and
 * never looked at the hostname sitting in the sentence.
 *
 * <p>Order of appearance wins. A typed {@code https://} URL is used as
 * written; a bare host becomes {@code https://} plus the host; an
 * {@code @handle} becomes {@code https://x.com/handle}, which is the
 * fetchable form of an X/Twitter account.
 */
public final class NamedSource {

    private static final Pattern URL = Pattern.compile(
            "(https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code @Marvel}, but not {@code user@gmail.com}. The look-behind is
     * a fixed class so Java's regex engine will accept it.
     */
    private static final Pattern HANDLE = Pattern.compile(
            "(?<![A-Za-z0-9.])@([A-Za-z0-9_]{1,30})\\b");

    /** Something the http/browser tools can open. */
    public final String fetchUrl;
    /** How the user wrote it. */
    public final String written;

    NamedSource(String fetchUrl, String written) {
        this.fetchUrl = fetchUrl;
        this.written = written;
    }

    /**
     * The first named source in {@code text}, or {@code null} when nothing
     * in the text is shaped like a site or a handle.
     */
    public static NamedSource extract(String text) {
        if (text == null || text.isEmpty()) return null;

        int bestAt = Integer.MAX_VALUE;
        NamedSource best = null;

        Matcher um = URL.matcher(text);
        if (um.find()) {
            bestAt = um.start();
            best = new NamedSource(um.group(1), um.group(1));
        }

        String host = Hosts.firstIn(text);
        if (host != null) {
            int at = indexOfIgnoreCase(text, host);
            // A host taken from a full URL is the same mention; do not let
            // the reduced form steal the typed URL that appeared first.
            if (at >= 0 && at < bestAt) {
                bestAt = at;
                best = new NamedSource("https://" + host, host);
            }
        }

        Matcher hm = HANDLE.matcher(text);
        if (hm.find() && hm.start() < bestAt) {
            String handle = hm.group(1);
            best = new NamedSource("https://x.com/" + handle, "@" + handle);
        }

        return best;
    }

    /** The fetchable URL, or {@code null}. */
    public static String fetchUrlIn(String text) {
        NamedSource src = extract(text);
        return src == null ? null : src.fetchUrl;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
