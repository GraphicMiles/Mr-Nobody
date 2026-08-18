package com.mrnobody.agent.planner;

import com.mrnobody.agent.util.Hosts;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The site or account a user pointed at, found by structure, not vocabulary.
 */
public final class NamedSource {

    private static final Pattern URL = Pattern.compile(
            "(https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern HANDLE = Pattern.compile(
            "(?<![A-Za-z0-9.])@([A-Za-z0-9_]{1,30})\\b");

    public final String fetchUrl;
    public final String written;

    NamedSource(String fetchUrl, String written) {
        this.fetchUrl = fetchUrl;
        this.written = written;
    }

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

    public static String fetchUrlIn(String text) {
        NamedSource src = extract(text);
        return src == null ? null : src.fetchUrl;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
