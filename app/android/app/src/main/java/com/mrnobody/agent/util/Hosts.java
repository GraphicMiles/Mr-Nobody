package com.mrnobody.agent.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds hostnames written in ordinary prose.
 *
 * <p>Its own class because two places needed it and each had grown its own
 * half-right version. The planner only recognised a site if the user typed a
 * scheme, so "download it from nkiri.ink" named a site the agent never opened.
 * The verifier only recognised a host if its suffix was one of nine hardcoded
 * strings, so an answer could refer to <em>nkiri.ink</em>, <em>evil.xyz</em> or
 * <em>evil.ru</em> and be reported as citing nothing off-source.
 *
 * <p>The two failures compounded: the agent skipped the page the user asked
 * for, answered from three unrelated pages, and the check that exists to catch
 * exactly that could not see the host it had missed.
 *
 * <p>A bare token is only treated as a host when its last label is a suffix we
 * recognise. That is deliberately a list rather than a pattern: "Reacher
 * .S04E01.1080p.mkv", "config.json" and "version 3.14" all look like domains to
 * a naive matcher, and a false positive here is worse than a miss — it would
 * put a scary warning on a correct answer.
 */
public final class Hosts {

    /**
     * Public suffixes we accept on a bare, scheme-less token. Generic and
     * common country-code TLDs, plus the newer generics that show up on
     * download and streaming sites. Not exhaustive — no such list is — but it
     * fails toward "not a host", which is the safe direction.
     */
    private static final Set<String> KNOWN_TLDS = new HashSet<>(Arrays.asList(
            // classic generics
            "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz",
            "name", "pro", "mobi", "asia", "coop", "aero", "jobs", "travel",
            // newer generics
            "app", "dev", "page", "cloud", "site", "online", "store", "shop",
            "live", "stream", "video", "tv", "media", "news", "blog", "wiki",
            "tech", "digital", "tools", "work", "world", "zone", "space",
            "group", "network", "host", "email", "link", "click", "fun",
            "one", "plus", "art", "life", "ltd", "run", "show", "today",
            "ninja", "guru", "xyz", "top", "club", "vip", "icu", "ink",
            "download", "win", "bid", "loan", "men", "lol", "buzz", "monster",
            "quest", "sbs", "cyou", "cfd", "rest", "wtf", "gdn", "pics",
            // country codes in common use
            "io", "co", "uk", "us", "ca", "au", "de", "fr", "it", "es", "nl",
            "se", "no", "fi", "dk", "pl", "ru", "ua", "cn", "jp", "kr", "in",
            "br", "mx", "za", "ng", "ke", "gh", "eg", "ma", "tr", "gr", "pt",
            "cz", "sk", "hu", "ro", "bg", "hr", "rs", "si", "ie", "be", "at",
            "ch", "il", "ae", "sa", "ir", "pk", "bd", "lk", "np", "my", "sg",
            "ph", "th", "vn", "id", "hk", "tw", "nz", "cl", "ar", "pe", "ve",
            // small ccTLDs widely used as generics
            "to", "cc", "me", "ws", "is", "li", "sh", "st", "pw", "tk", "ml",
            "ga", "cf", "gq", "su", "nu", "fm", "am", "gg", "ai", "im", "la",
            "ly", "sc", "vc", "cx", "mn", "ee", "lv", "lt", "by", "kz", "md"));

    /**
     * Suffixes that are almost always a file, never a host. Checked first, so
     * a release name like {@code Show.S04E01.mkv} or a source path like
     * {@code MrNobodyWebView.java} is never mistaken for a domain.
     */
    private static final Set<String> FILE_SUFFIXES = new HashSet<>(Arrays.asList(
            "txt", "json", "java", "kt", "html", "htm", "xml", "csv", "md",
            "py", "js", "ts", "jsx", "tsx", "css", "scss", "sh", "bat", "exe",
            "apk", "aab", "zip", "tar", "gz", "rar", "7z", "iso", "dmg", "pdf",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico",
            "mp3", "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4a",
            "wav", "flac", "srt", "sub", "vtt",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
            "log", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
            "gradle", "jar", "war", "class", "dart", "swift", "rb", "go",
            "rs", "c", "cpp", "h", "hpp", "php", "sql", "db", "sqlite",
            "bak", "tmp", "lock", "sum", "mod", "pro"));

    /**
     * A full URL, or a bare dotted token. The bare branch requires each label
     * to start and end alphanumerically, which rejects "4.Episode" and ".."
     * without needing a second pass.
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "\\bhttps?://[^\\s)\\]},\"'<>]+"
                    + "|\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,24}\\b",
            Pattern.CASE_INSENSITIVE);

    private Hosts() {
    }

    /**
     * Every distinct host named in {@code text}, in order of appearance,
     * lower-cased with any leading {@code www.} removed.
     */
    public static Set<String> findIn(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return found;

        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            String host = normalise(m.group());
            if (!host.isEmpty()) found.add(host);
        }
        return found;
    }

    /**
     * The first host named in {@code text}, or {@code null}. Used by the
     * planner to honour "…from example.com" whether or not a scheme was typed.
     */
    public static String firstIn(String text) {
        for (String host : findIn(text)) return host;
        return null;
    }

    /** Reduce one matched token to a host, or "" if it is not one. */
    private static String normalise(String token) {
        String host = token.toLowerCase(Locale.ROOT);

        boolean hadScheme = host.startsWith("http://") || host.startsWith("https://");
        if (hadScheme) {
            host = host.replaceFirst("^https?://", "");
            int cut = indexOfAny(host, "/?#");
            if (cut >= 0) host = host.substring(0, cut);
            int at = host.indexOf('@');           // strip any userinfo
            if (at >= 0) host = host.substring(at + 1);
            int colon = host.indexOf(':');        // strip any port
            if (colon >= 0) host = host.substring(0, colon);
        }

        host = host.replaceFirst("^www\\.", "");
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || !host.contains(".")) return "";

        String tld = host.substring(host.lastIndexOf('.') + 1);
        if (FILE_SUFFIXES.contains(tld)) return "";

        // An explicit scheme is the author saying "this is a URL", so we take
        // their word for the suffix. A bare token has to earn it.
        if (!hadScheme && !KNOWN_TLDS.contains(tld)) return "";

        return host;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int at = s.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }
}
